package org.frostbyte.clientnode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Service to discover DatabaseNode addresses from MasterNode(s) with fallback logic.
 * Tries each MasterNode in sequence until one responds successfully.
 * Caches result for a configurable TTL to reduce load on MasterNodes.
 */

@Service
public class MasterNodeDiscoveryService {
    private static final Logger log = Logger.getLogger(MasterNodeDiscoveryService.class.getName());
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final configModel config;

    // Cached DatabaseNode address
    private String cachedDatabaseNodeAddress;
    private long lastDiscoveryTime;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public MasterNodeDiscoveryService(configModel config) {
        this.config = config;
    }

    /**
     * Discover DatabaseNode address from MasterNode(s) with fallback logic.
     * Tries each MasterNode in sequence until one responds successfully.
     * Caches result for CACHE_TTL_MS.
     *
     * @return DatabaseNode address (host:port format)
     * @throws IllegalStateException if no MasterNode can be reached or no DatabaseNode found
     */
    public synchronized String discoverDatabaseNode() throws IllegalStateException {
        // Check if cache is still valid
        if (cachedDatabaseNodeAddress != null &&
            (System.currentTimeMillis() - lastDiscoveryTime) < CACHE_TTL_MS) {
            log.fine("[DB-DISCOVERY] Using cached DatabaseNode address: " + cachedDatabaseNodeAddress);
            return cachedDatabaseNodeAddress;
        }

        if (config == null || config.getMasterNodes() == null || config.getMasterNodes().length == 0) {
            String err = "No masterNodes configured";
            log.severe("[DB-DISCOVERY] " + err);
            throw new IllegalStateException(err);
        }

        String[] masterNodes = config.getMasterNodes();
        String lastError = null;

        // Try each MasterNode in sequence
        for (int i = 0; i < masterNodes.length; i++) {
            String masterNode = masterNodes[i];
            try {
                log.info(String.format("[DB-DISCOVERY] Attempting to discover DatabaseNode from MasterNode[%d]: %s", i, masterNode));
                String result = queryMasterNodeForDatabaseNode(masterNode);
                if (result != null && !result.isEmpty()) {
                    cachedDatabaseNodeAddress = result;
                    lastDiscoveryTime = System.currentTimeMillis();
                    log.info("[DB-DISCOVERY] Successfully discovered DatabaseNode: " + cachedDatabaseNodeAddress);
                    return cachedDatabaseNodeAddress;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warning(String.format("[DB-DISCOVERY] MasterNode[%d] %s failed: %s", i, masterNode, lastError));
                // Continue to next MasterNode
            }
        }

        String err = "Failed to discover DatabaseNode from all MasterNodes. Last error: " + lastError;
        log.severe("[DB-DISCOVERY] " + err);
        throw new IllegalStateException(err);
    }

    /**
     * Query a single MasterNode for alive DatabaseNodes.
     * Expects response format: { "aliveNodes": [{ "host": "...", "nodeName": "..." }], ... }
     *
     * @param masterNode MasterNode address (host:port)
     * @return DatabaseNode address in host:port format
     */
    private String queryMasterNodeForDatabaseNode(String masterNode) throws Exception {
        Instant start = Instant.now();

        String host = masterNode;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "database/getAlive";

        HttpHeaders headers = new HttpHeaders();
        if (config.getMasterAPIKey() != null) {
            headers.set("X-API-Key", config.getMasterAPIKey());
        }

        HttpEntity<String> entity = new HttpEntity<>("", headers);

        log.fine(String.format("[DB-DISCOVERY-HTTP] GET %s", endpoint));
        ResponseEntity<String> resp = rest.exchange(endpoint, org.springframework.http.HttpMethod.GET, entity, String.class);
        long durationMs = Duration.between(start, Instant.now()).toMillis();

        log.fine(String.format("[DB-DISCOVERY-RESP] status=%d timeMs=%d", resp.getStatusCode().value(), durationMs));

        if (resp.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("MasterNode returned status: " + resp.getStatusCode().value());
        }

        // Parse response and extract first DatabaseNode
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = mapper.readValue(resp.getBody(), java.util.Map.class);

        if (body == null) {
            throw new RuntimeException("Empty response from MasterNode");
        }

        Object aliveNodesObj = body.get("aliveNodes");
        if (aliveNodesObj == null) {
            throw new RuntimeException("No 'aliveNodes' field in MasterNode response");
        }

        // Handle case where aliveNodes is a string "NULL"
        if (aliveNodesObj instanceof String && "NULL".equals(aliveNodesObj)) {
            throw new RuntimeException("No alive DatabaseNodes reported by MasterNode");
        }

        if (!(aliveNodesObj instanceof java.util.List)) {
            throw new RuntimeException("aliveNodes is not a list");
        }

        java.util.List<?> aliveNodesList = (java.util.List<?>) aliveNodesObj;
        if (aliveNodesList.isEmpty()) {
            throw new RuntimeException("No alive DatabaseNodes in list");
        }

        // Get first DatabaseNode's address
        Object firstNodeObj = aliveNodesList.get(0);
        if (!(firstNodeObj instanceof java.util.Map)) {
            throw new RuntimeException("Node entry is not a map");
        }

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> firstNode = (java.util.Map<String, Object>) firstNodeObj;
        String nodeHost = (String) firstNode.get("host");

        if (nodeHost == null || nodeHost.isEmpty()) {
            throw new RuntimeException("DatabaseNode has no host address");
        }

        log.fine("[DB-DISCOVERY] Extracted DatabaseNode address: " + nodeHost);
        return nodeHost;
    }

    /**
     * Discover BalancerNode address from MasterNode(s).
     * @return host:port for a balancer
     */
    public synchronized String discoverBalancerNode() {
        if (config == null || config.getMasterNodes() == null || config.getMasterNodes().length == 0) {
            throw new IllegalStateException("No masterNodes configured");
        }
        String lastError = null;
        String[] masterNodes = config.getMasterNodes();
        for (int i = 0; i < masterNodes.length; i++) {
            String masterNode = masterNodes[i];
            try {
                String host = masterNode;
                if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
                String endpoint = host + (host.endsWith("/") ? "" : "/") + "balancer/getAlive";

                HttpHeaders headers = new HttpHeaders();
                if (config.getMasterAPIKey() != null) headers.set("X-API-Key", config.getMasterAPIKey());
                HttpEntity<String> entity = new HttpEntity<>("", headers);

                ResponseEntity<String> resp = rest.exchange(endpoint, org.springframework.http.HttpMethod.GET, entity, String.class);
                if (resp.getStatusCode() != HttpStatus.OK) throw new RuntimeException("MasterNode status=" + resp.getStatusCode().value());

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body = mapper.readValue(resp.getBody(), java.util.Map.class);
                Object aliveNodesObj = body.get("aliveNodes");
                if (aliveNodesObj instanceof String && "NULL".equals(aliveNodesObj)) continue;
                if (!(aliveNodesObj instanceof java.util.List)) continue;
                java.util.List<?> list = (java.util.List<?>) aliveNodesObj;
                if (list.isEmpty()) continue;
                Object first = list.get(0);
                if (!(first instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> node = (java.util.Map<String, Object>) first;
                String nodeHost = (String) node.get("host");
                if (nodeHost != null && !nodeHost.isEmpty()) {
                    log.info("[BALANCER-DISCOVERY] Discovered balancer: " + nodeHost);
                    return nodeHost;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warning(String.format("[BALANCER-DISCOVERY] MasterNode[%d] %s failed: %s", i, masterNode, lastError));
            }
        }
        throw new IllegalStateException("Failed to discover any balancer node. Last error: " + lastError);
    }

    /**
     * Invalidate cache to force rediscovery on next call.
     * Useful after detecting a DatabaseNode failure.
     */
    public synchronized void invalidateCache() {
        log.info("[DB-DISCOVERY] Cache invalidated, will rediscover on next access");
        cachedDatabaseNodeAddress = null;
        lastDiscoveryTime = 0;
    }

    /**
     * Get cached DatabaseNode address without rediscovering.
     * Returns null if not yet discovered or cache expired.
     */
    public synchronized String getCachedDatabaseNode() {
        if (cachedDatabaseNodeAddress != null &&
            (System.currentTimeMillis() - lastDiscoveryTime) < CACHE_TTL_MS) {
            return cachedDatabaseNodeAddress;
        }
        return null;
    }
}