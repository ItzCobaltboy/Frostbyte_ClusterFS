package org.frostbyte.balancer.services;

import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class ReplicaService {

    private final configModel config;
    private final RestTemplate restTemplate;
    private static final Logger log = Logger.getLogger(ReplicaService.class.getName());

    public ReplicaService(configModel config) {
        this.config = config;

        // Configure RestTemplate with appropriate timeouts for large file uploads
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 30 seconds
        factory.setReadTimeout(600000);   // 10 minutes for large uploads
        this.restTemplate = new RestTemplate(factory);

        log.info("ReplicaService initialized with connectTimeout=30s, readTimeout=10m");
    }

    /**
     * Upload snowflake to a specific DataNode
     *
     * @param dataNodeInfo Target datanode information
     * @param snowflakeData Encrypted snowflake binary data
     * @param snowflakeFileName Unique filename for the snowflake
     * @return true if upload successful, false otherwise
     */
    public boolean uploadSnowflakeToDataNode(DataNodeInfo dataNodeInfo, byte[] snowflakeData, String snowflakeFileName) {
        try {
            String url = "http://" + dataNodeInfo.getHost() + "/datanode/upload";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("X-API-Key", config.getMasterAPIKey());

            // Create multipart request
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            // Wrap byte array as a resource
            ByteArrayResource resource = new ByteArrayResource(snowflakeData) {
                @Override
                public String getFilename() {
                    return snowflakeFileName;
                }
            };

            body.add("snowflake", resource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, requestEntity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully uploaded snowflake " + snowflakeFileName + " to " + dataNodeInfo.getNodeName());
                return true;
            } else {
                log.warning("Failed to upload snowflake to " + dataNodeInfo.getNodeName() + ": " + response.getStatusCode());
                return false;
            }

        } catch (Exception e) {
            log.severe("Error uploading snowflake to " + dataNodeInfo.getNodeName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Register replica information in DatabaseNode
     *
     * @param chunkId UUID of the chunk
     * @param datanodeIds List of datanode IDs where replicas are stored
     * @param databaseNodeUrl URL of the database node
     * @return true if registration successful
     */
    public boolean registerReplicasInDatabase(String chunkId, List<String> datanodeIds, String databaseNodeUrl) {
        try {
            String url = databaseNodeUrl + "/replicas/register/batch";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-Key", config.getMasterAPIKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("chunkId", chunkId);
            requestBody.put("datanodeIds", datanodeIds);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, requestEntity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully registered " + datanodeIds.size() + " replicas for chunk " + chunkId);
                return true;
            } else {
                log.warning("Failed to register replicas for chunk " + chunkId + ": " + response.getStatusCode());
                return false;
            }

        } catch (Exception e) {
            log.severe("Error registering replicas in database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create and distribute replicas across selected datanodes
     *
     * @param selectedNodes List of datanodes to receive replicas
     * @param snowflakeData Encrypted snowflake binary data
     * @param snowflakeFileName Unique filename for the snowflake
     * @return List of successful datanode IDs
     */
    public List<String> distributeReplicas(List<DataNodeInfo> selectedNodes, byte[] snowflakeData, String snowflakeFileName) {
        List<String> successfulNodes = new ArrayList<>();

        for (DataNodeInfo node : selectedNodes) {
            boolean success = uploadSnowflakeToDataNode(node, snowflakeData, snowflakeFileName);
            if (success) {
                successfulNodes.add(node.getHost());
            }
        }

        log.info("Successfully distributed " + successfulNodes.size() + " out of " + selectedNodes.size() + " replicas");
        return successfulNodes;
    }
}

