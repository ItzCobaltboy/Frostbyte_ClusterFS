package org.frostbyte.balancer.services;

import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
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
                        List<Map<String, Object>> nodesList = (List<Map<String, Object>>) aliveNodesObj;

                        for (Map<String, Object> nodeMap : nodesList) {
                            DataNodeInfo info = new DataNodeInfo();
                            info.setHost((String) nodeMap.get("host"));
                            info.setNodeName((String) nodeMap.get("nodeName"));

                            // Parse capacity metrics if available
                            if (nodeMap.containsKey("currentUsedGB")) {
                                info.setCurrentUsedGB(((Number) nodeMap.get("currentUsedGB")).doubleValue());
                            }
                            if (nodeMap.containsKey("totalCapacityGB")) {
                                info.setTotalCapacityGB(((Number) nodeMap.get("totalCapacityGB")).doubleValue());
                            }
                            if (nodeMap.containsKey("fillPercent")) {
                                info.setFillPercent(((Number) nodeMap.get("fillPercent")).doubleValue());
                            }

                            // Initialize projected fill percent to current fill percent
                            info.setProjectedFillPercent(info.getFillPercent());

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
     * Select N datanodes for chunk replication using a heap-based load balancing algorithm
     *
     * Algorithm:
     * - Uses min-heap based on projectedFillPercent to select least-loaded nodes
     * - Ensures no duplicate nodes for the same chunk (Latin rectangle property)
     * - Filters out nodes near capacity (>95% full)
     * - Handles edge cases where fewer nodes are available than requested replicas
     *
     * @param availableNodes List of available datanodes with capacity metrics
     * @param count Number of replicas needed (replication factor P)
     * @param offset Offset parameter (deprecated, kept for API compatibility)
     * @return List of selected datanodes
     * @throws InsufficientCapacityException if unable to select enough nodes with capacity
     */
    public List<DataNodeInfo> selectDataNodesForReplicas(List<DataNodeInfo> availableNodes, int count, int offset) {
        if (availableNodes.isEmpty()) {
            log.warning("No datanodes available for selection");
            return new ArrayList<>();
        }

        // Capacity threshold - reject nodes above 95% full
        final double CAPACITY_THRESHOLD = 95.0;

        // Build min-heap based on projectedFillPercent
        PriorityQueue<DataNodeInfo> minHeap = new PriorityQueue<>(
                Comparator.comparingDouble(DataNodeInfo::getProjectedFillPercent)
        );
        minHeap.addAll(availableNodes);

        Set<String> usedNodeNames = new HashSet<>();
        List<DataNodeInfo> selectedNodes = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = availableNodes.size() * 2;

        while (selectedNodes.size() < count && attempts < maxAttempts) {
            attempts++;

            if (minHeap.isEmpty()) {
                log.warning(String.format("Min-heap exhausted after %d attempts. Selected %d/%d replicas",
                        attempts, selectedNodes.size(), count));
                break;
            }

            DataNodeInfo node = minHeap.poll();

            // Check: not already used AND has capacity
            if (!usedNodeNames.contains(node.getNodeName()) &&
                    node.getProjectedFillPercent() < CAPACITY_THRESHOLD) {

                selectedNodes.add(node);
                usedNodeNames.add(node.getNodeName());

                log.fine(String.format("Selected node %s (fill: %.1f%%)",
                        node.getNodeName(), node.getProjectedFillPercent()));
            } else {
                log.fine(String.format("Skipped node %s (already used: %b, fill: %.1f%%)",
                        node.getNodeName(),
                        usedNodeNames.contains(node.getNodeName()),
                        node.getProjectedFillPercent()));
            }

            // Always re-insert for future iterations (heap will re-order)
            minHeap.offer(node);
        }

        // Log results
        if (selectedNodes.size() < count) {
            log.warning(String.format(
                    "Could not select enough nodes with capacity. Requested: %d, Selected: %d, Available: %d",
                    count, selectedNodes.size(), availableNodes.size()));
        } else {
            log.info(String.format("Selected %d datanodes for replication (capacity-aware heap algorithm)",
                    selectedNodes.size()));
        }

        return selectedNodes;
    }

    /**
     * Exception thrown when insufficient nodes with capacity are available
     */
    public static class InsufficientCapacityException extends RuntimeException {
        public InsufficientCapacityException(String message) {
            super(message);
        }
    }
}

