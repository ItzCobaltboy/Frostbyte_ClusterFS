package org.frostbyte.balancer.services;

import org.frostbyte.balancer.models.configModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class DatabaseNodeService {

    private final configModel config;
    private final RestTemplate restTemplate;
    private static final Logger log = Logger.getLogger(DatabaseNodeService.class.getName());

    public DatabaseNodeService(configModel config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Fetch an alive DatabaseNode URL from MasterNode
     *
     * @return DatabaseNode URL (e.g., "http://127.0.0.1:8082") or null if none available
     */
    public String fetchDatabaseNodeUrl() {
        for (String masterNode : config.getMasterNodes()) {
            try {
                String url = "http://" + masterNode + "/database/getAlive";

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

                    if (aliveNodesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> nodesList = (List<Map<String, String>>) aliveNodesObj;

                        if (!nodesList.isEmpty()) {
                            String dbHost = nodesList.get(0).get("host");
                            String dbUrl = "http://" + dbHost;
                            log.info("Found database node at: " + dbUrl);
                            return dbUrl;
                        }
                    }
                }
            } catch (Exception e) {
                log.warning("Failed to fetch database node from " + masterNode + ": " + e.getMessage());
            }
        }

        log.severe("No database node available from any master node!");
        return null;
    }
}

