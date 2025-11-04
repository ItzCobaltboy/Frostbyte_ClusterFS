package org.frostbyte.balancer.services;

import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class DataNodeService {

    private final configModel config;
    private final RestTemplate restTemplate;
    private static final Logger log = Logger.getLogger(DataNodeService.class.getName());

    public DataNodeService(configModel config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Fetches available DataNodes from MasterNode
     * This is an on-demand call that queries the master for currently alive datanodes
     *
     * @return List of available DataNode information
     */
    public List<DataNodeInfo> fetchAvailableDataNodes() {
        List<DataNodeInfo> allDataNodes = new ArrayList<>();

        // Try each master node until we get a successful response
        for (String masterNode : config.getMasterNodes()) {
            try {
                String url = "http://" + masterNode + "/datanode/getAlive";

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-Key", config.getMasterAPIKey());
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<Map> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        Map.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Object aliveNodesObj = response.getBody().get("aliveNodes");

                    // Check if response is "NULL" or a list
                    if (aliveNodesObj instanceof String && "NULL".equals(aliveNodesObj)) {
                        log.warning("No alive datanodes available from " + masterNode);
                        continue;
                    }

                    if (aliveNodesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> nodesList = (List<Map<String, String>>) aliveNodesObj;

                        for (Map<String, String> nodeMap : nodesList) {
                            DataNodeInfo info = new DataNodeInfo();
                            info.setHost(nodeMap.get("host"));
                            info.setNodeName(nodeMap.get("nodeName"));
                            allDataNodes.add(info);
                        }

                        log.info("Fetched " + allDataNodes.size() + " alive datanodes from " + masterNode);
                        return allDataNodes; // Return on first successful fetch
                    }
                }
            } catch (Exception e) {
                log.warning("Failed to fetch datanodes from " + masterNode + ": " + e.getMessage());
            }
        }

        if (allDataNodes.isEmpty()) {
            log.severe("No datanodes available from any master node!");
        }

        return allDataNodes;
    }

    /**
     * Select N datanodes for chunk replication using a simple round-robin approach
     * In production, this could implement more sophisticated algorithms like Latin rectangle
     *
     * @param availableNodes List of available datanodes
     * @param count Number of replicas needed
     * @param offset Offset for selection to ensure different nodes for different chunks
     * @return List of selected datanodes
     */
    public List<DataNodeInfo> selectDataNodesForReplicas(List<DataNodeInfo> availableNodes, int count, int offset) {
        if (availableNodes.isEmpty()) {
            return new ArrayList<>();
        }

        List<DataNodeInfo> selected = new ArrayList<>();
        int nodeCount = availableNodes.size();

        // Select different nodes in a round-robin fashion with offset
        for (int i = 0; i < Math.min(count, nodeCount); i++) {
            int index = (i + offset) % nodeCount;
            selected.add(availableNodes.get(index));
        }

        return selected;
    }
}

