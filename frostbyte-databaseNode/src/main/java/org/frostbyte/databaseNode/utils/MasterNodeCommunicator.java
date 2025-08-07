package org.frostbyte.databaseNode.utils;

import org.frostbyte.databaseNode.models.configModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.logging.Logger;

@Component
public class MasterNodeCommunicator {

    private final configModel config;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Logger log = Logger.getLogger(getClass().getName());

    public MasterNodeCommunicator(configModel config) {
        this.config = config;
    }

    public void registerWithMasters() {
        for (String node : config.getMasterNodes()) {
            try {
                String url = "http://" + node + "/database/register"; // Use the new endpoint

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-Key", config.getMasterAPIKey());

                Map<String, Object> body = Map.of(
                        "ip", String.format("%s:%d", config.getHost(), config.getPort()),
                        "nodeName", config.getNodeName(),
                        "nodeType", "DatabaseNode" // Specify the correct type
                );

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

                restTemplate.postForEntity(url, entity, String.class);
                log.info("Registered with " + node);

            } catch (Exception e) {
                log.warning("Failed to register with " + node + ": " + e.getMessage());
            }
        }
    }

    public void pingMasters() {
        for (String node : config.getMasterNodes()) {
            try {
                // Use the new endpoint and pass the nodeName as a query parameter
                String url = "http://" + node + "/database/heartbeat?nodeName=" + config.getNodeName();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-Key", config.getMasterAPIKey());
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                restTemplate.postForEntity(url, entity, String.class);
                log.info("Pinged " + node);

            } catch (Exception e) {
                log.warning("Ping failed for " + node + ": " + e.getMessage());
            }
        }
    }
}