package org.frostbyte.clientnode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.clientnode.models.Snowflake;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class BalancerNodeClient {
    private static final Logger log = Logger.getLogger(BalancerNodeClient.class.getName());

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final configModel config;

    public BalancerNodeClient(configModel config) {
        this.config = config;

        // Configure RestTemplate with appropriate timeouts for large file uploads
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 30 seconds
        factory.setReadTimeout(600000);   // 10 minutes for large uploads
        this.rest = new RestTemplate(factory);

        log.info("BalancerNodeClient initialized with connectTimeout=30s, readTimeout=10m");
    }

    /**
     * Upload a Snowflake object directly to a specific BalancerNode (in-memory, no disk I/O).
     * Balancer will distribute replicas to DataNodes and register replica metadata.
     *
     * @param balancerHost host:port of the balancer (with or without http://)
     * @param chunkId UUID string of the chunk
     * @param snowflake the Snowflake object containing encrypted data and metadata
     * @param fileName file name to report to balancer (e.g., fileId_chunkNum.snowflake)
     * @return response JSON as Map
     */
    public Map<String, Object> uploadSnowflakeToBalancer(String balancerHost, String chunkId, Snowflake snowflake, String fileName) throws Exception {
        if (snowflake == null) {
            throw new IllegalArgumentException("Snowflake object cannot be null");
        }

        String host = balancerHost;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "balancer/upload/snowflake";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (config.getMasterAPIKey() != null) headers.set("X-API-Key", config.getMasterAPIKey());

        // Convert Snowflake to bytes using its built-in serialization (fully in-memory)
        byte[] snowflakeBytes;
        try {
            snowflakeBytes = snowflake.toByteArray();
            log.fine(String.format("[SNOWFLAKE-SERIALIZED] chunkId=%s size=%d bytes", chunkId, snowflakeBytes.length));
        } catch (IOException e) {
            log.log(Level.SEVERE, "[SNOWFLAKE-SERIALIZATION-FAILED] chunkId=" + chunkId, e);
            throw new RuntimeException("Failed to serialize snowflake", e);
        }

        // Create a ByteArrayResource that provides a filename for multipart upload
        ByteArrayResource resource = new ByteArrayResource(snowflakeBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("snowflake", resource);
        body.add("chunkId", chunkId);
        body.add("fileName", fileName);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            Instant start = Instant.now();
            ResponseEntity<String> resp = rest.postForEntity(endpoint, entity, String.class);
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info(String.format("[BALANCER-UPLOAD] POST %s status=%d timeMs=%d size=%d file=%s",
                    endpoint, resp.getStatusCode().value(), ms, snowflakeBytes.length, fileName));
            if (!resp.getStatusCode().is2xxSuccessful()) {
                String msg = "Balancer upload failed with status " + resp.getStatusCode().value() + " body=" + resp.getBody();
                log.severe("[BALANCER-UPLOAD-ERR] " + msg);
                throw new RuntimeException(msg);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = mapper.readValue(resp.getBody(), Map.class);
            return bodyMap;
        } catch (Exception e) {
            log.log(Level.SEVERE, "[BALANCER-UPLOAD-EX] Failed to upload snowflake to balancer", e);
            throw e;
        }
    }
}
