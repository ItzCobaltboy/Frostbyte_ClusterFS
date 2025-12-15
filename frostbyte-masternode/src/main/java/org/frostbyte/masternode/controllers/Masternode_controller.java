package org.frostbyte.masternode.controllers;


import jakarta.validation.Valid;
import org.frostbyte.masternode.models.*;
import org.frostbyte.masternode.services.heartbeatRegister;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Logger;

/**
 * MasterNode controller for node registry, heartbeat management, and cluster coordination.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register new nodes (DataNode, BalancerNode, DatabaseNode) on startup</li>
 *   <li>Process heartbeat pings from all node types</li>
 *   <li>Track node capacity metrics (storage usage, fill percentage)</li>
 *   <li>Provide alive node lists to ClientNode and BalancerNode</li>
 *   <li>Mark nodes as dead when heartbeats stop (5-minute timeout)</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /datanode/register - Register new DataNode</li>
 *   <li>POST /balancer/register - Register new BalancerNode</li>
 *   <li>POST /database/register - Register new DatabaseNode</li>
 *   <li>POST /datanode/heartbeat - DataNode heartbeat ping (includes capacity metrics)</li>
 *   <li>POST /balancer/heartbeat - BalancerNode heartbeat ping</li>
 *   <li>POST /database/heartbeat - DatabaseNode heartbeat ping</li>
 *   <li>GET /datanode/getAlive - Get list of alive DataNodes with capacity info</li>
 *   <li>GET /balancer/getAlive - Get list of alive BalancerNodes</li>
 *   <li>GET /database/getAlive - Get list of alive DatabaseNodes</li>
 * </ul>
 *
 * <p>Heartbeat system: Nodes marked dead if no heartbeat for 5 minutes.
 * Dead nodes removed automatically by scheduled task every 2 minutes.
 *
 * @see heartbeatRegister
 * @see org.frostbyte.masternode.models.DataNode
 * @see org.frostbyte.masternode.models.BalancerNode
 * @see org.frostbyte.masternode.models.DatabaseNode
 * @since 0.1.0
 */
@RestController
public class Masternode_controller {

    private final heartbeatRegister hr;
    private final configModel config;

    private static final Logger log = Logger.getLogger(Masternode_controller.class.getName());
    private static final String API_HEADER = "X-API-Key";

    @Autowired
    public Masternode_controller(heartbeatRegister hr, configModel config) {
        this.hr = hr;
        this.config = config;
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }

    // =================================================================
    // 1. NODE REGISTRATION
    // =================================================================

    /**
     * Register a new DataNode in the cluster.
     * Called by DataNode on startup via StartupRunner.
     *
     * Validates node type and adds to registry with current timestamp.
     *
     * @param apiKey Internal API key for authentication
     * @param req Registration request containing node IP, name, and type
     * @return 200 OK with success status, 400 BAD_REQUEST if wrong node type or invalid API key
     */
    @PostMapping("/datanode/register")
    public ResponseEntity<?> registerDN(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);


        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.DataNode) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = DataNode", "success", "false"));
        }

        DataNode dn = new DataNode();
        dn.setHost(req.getIp());
        dn.setNodeName(req.getNodeName());
        dn.setRegisterTime(LocalDateTime.now());
        dn.setLastUpdateTime(LocalDateTime.now());

        hr.addDataNode(dn);
        log.info("New data node added: " + dn);
        log.fine("New node IP: " + dn.getHost());
        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }

    /**
     * Register a new BalancerNode in the cluster.
     * Called by BalancerNode on startup via StartupRunner.
     *
     * @param apiKey Internal API key for authentication
     * @param req Registration request containing node IP, name, and type
     * @return 200 OK with success status, 400 BAD_REQUEST if wrong node type or invalid API key
     */
    @PostMapping("/balancer/register")
    public ResponseEntity<?> registerBN(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);

        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.Balancer) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = BalancerNode", "success", "false"));
        }

        BalancerNode bn = new BalancerNode();
        bn.setHost(req.getIp());
        bn.setNodeName(req.getNodeName());
        bn.setRegisterTime(LocalDateTime.now());
        bn.setLastUpdateTime(LocalDateTime.now());

        hr.addBalancerNode(bn);

        log.info("New balancer node added: " + bn);
        log.fine("New balancer IP: " + bn.getHost());


        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }

    /**
     * Register a new DatabaseNode in the cluster.
     * Called by DatabaseNode on startup via StartupRunner.
     *
     * @param apiKey Internal API key for authentication
     * @param req Registration request containing node IP, name, and type
     * @return 200 OK with success status, 400 BAD_REQUEST if wrong node type or invalid API key
     */
    @PostMapping("/database/register")
    public ResponseEntity<?> registerDB(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);

        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.DatabaseNode) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = DatabaseNode", "success", "false"));
        }

        DatabaseNode dbNode = new DatabaseNode();
        dbNode.setHost(req.getIp());
        dbNode.setNodeName(req.getNodeName());
        dbNode.setRegisterTime(LocalDateTime.now());
        dbNode.setLastUpdateTime(LocalDateTime.now());

        hr.addDatabaseNode(dbNode);
        log.info("New database node added: " + dbNode);
        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }

    // =================================================================
    // 2. HEARTBEAT MANAGEMENT
    // =================================================================

    /**
     * Process heartbeat ping from DataNode.
     * Called periodically by DataNode (every 2 minutes) to signal it's alive.
     *
     * Accepts capacity metrics (currentUsedGB, totalCapacityGB, fillPercent)
     * and updates node status + capacity in registry.
     *
     * @param apiKey Internal API key for authentication
     * @param nodeName Name of the DataNode sending heartbeat
     * @param capacityMetrics Optional map with currentUsedGB, totalCapacityGB, fillPercent
     * @return 200 OK with success status, 400 BAD_REQUEST if node not found
     */
    @PostMapping("/datanode/heartbeat")
    public ResponseEntity<?> dataNodeHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                               @RequestParam("nodeName") String nodeName,
                                               @RequestBody(required = false) Map<String, Object> capacityMetrics) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        // Extract capacity metrics if provided
        double currentUsedGB = 0.0;
        double totalCapacityGB = 0.0;
        double fillPercent = 0.0;

        if (capacityMetrics != null) {
            if (capacityMetrics.containsKey("currentUsedGB")) {
                currentUsedGB = ((Number) capacityMetrics.get("currentUsedGB")).doubleValue();
            }
            if (capacityMetrics.containsKey("totalCapacityGB")) {
                totalCapacityGB = ((Number) capacityMetrics.get("totalCapacityGB")).doubleValue();
            }
            if (capacityMetrics.containsKey("fillPercent")) {
                fillPercent = ((Number) capacityMetrics.get("fillPercent")).doubleValue();
            }
        }

        boolean updated = hr.updateDataNode(nodeName, currentUsedGB, totalCapacityGB, fillPercent);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeName = DataNode", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }

    /**
     * Process heartbeat ping from BalancerNode.
     * Called periodically by BalancerNode (every 2 minutes) to signal it's alive.
     *
     * @param apiKey Internal API key for authentication
     * @param nodeName Name of the BalancerNode sending heartbeat
     * @return 200 OK with success status, 400 BAD_REQUEST if node not found
     */
    @PostMapping("/balancer/heartbeat")
    public ResponseEntity<?> balancerHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                               @RequestParam("nodeName") String nodeName) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        boolean updated = hr.updateBalancerNode(nodeName);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeName = BalancerNode", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }

    /**
     * Process heartbeat ping from DatabaseNode.
     * Called periodically by DatabaseNode (every 2 minutes) to signal it's alive.
     *
     * @param apiKey Internal API key for authentication
     * @param nodeName Name of the DatabaseNode sending heartbeat
     * @return 200 OK with success status, 400 BAD_REQUEST if node not found
     */
    @PostMapping("/database/heartbeat")
    public ResponseEntity<?> databaseNodeHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                                   @RequestParam("nodeName") String nodeName) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        boolean updated = hr.updateDatabaseNode(nodeName);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Node not found", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }

    // =================================================================
    // 3. QUERY ENDPOINTS (ALIVE NODE DISCOVERY)
    // =================================================================

    /**
     * Get list of alive DataNodes with capacity metrics.
     * Called by ClientNode and BalancerNode to discover available storage nodes.
     *
     * Returns node host, name, and capacity info (currentUsedGB, totalCapacityGB, fillPercent).
     * Used by BalancerNode's heap-based load balancing algorithm for chunk allocation.
     *
     * @param apiKey Internal API key for authentication
     * @return JSON with aliveNodes array (or "NULL" string if none alive) and timestamp
     */
    @GetMapping("/datanode/getAlive")
    public ResponseEntity<?> getAlive(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<DataNode> aliveNodes = hr.getAliveDataNodes();

        if (aliveNodes.isEmpty()) {
            return ResponseEntity.status(200).body(Map.of(
                    "aliveNodes", "NULL",
                    "Timestamp", LocalDateTime.now()
            ));
        }

        var output = new Vector<Map<String, Object>>();
        for (DataNode dn : aliveNodes) {
            output.add(Map.of(
                    "host", dn.getHost(),
                    "nodeName", dn.getNodeName(),
                    "currentUsedGB", dn.getCurrentUsedGB(),
                    "totalCapacityGB", dn.getTotalCapacityGB(),
                    "fillPercent", dn.getFillPercent()
            ));
        }

        return ResponseEntity.status(200).body(Map.of(
                "aliveNodes", output,
                "Timestamp", LocalDateTime.now()
        ));
    }

    /**
     * Get list of alive BalancerNodes.
     * Called by ClientNode to discover available balancer nodes for upload/download requests.
     *
     * @param apiKey Internal API key for authentication
     * @return JSON with aliveNodes array (or "NULL" string if none alive) and timestamp
     */
    @GetMapping("/balancer/getAlive")
    public ResponseEntity<?> getAliveBalancers(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<BalancerNode> aliveNodes = hr.getAliveBalancers();

        if (aliveNodes.isEmpty()) {
            return ResponseEntity.status(200).body(Map.of(
                    "aliveNodes", "NULL",
                    "Timestamp", LocalDateTime.now()
            ));
        }

        // build list of node info
        var output = new Vector<Map<String, String>>();
        for (BalancerNode bn : aliveNodes) {
            output.add(Map.of(
                    "host", bn.getHost(),
                    "nodeName", bn.getNodeName()
            ));
        }

        return ResponseEntity.status(200).body(Map.of(
                "aliveNodes", output,
                "Timestamp", LocalDateTime.now()
        ));
    }

    /**
     * Get list of alive DatabaseNodes.
     * Called by ClientNode to discover available database nodes for metadata operations.
     *
     * @param apiKey Internal API key for authentication
     * @return JSON with aliveNodes array and timestamp
     */
    @GetMapping("/database/getAlive")
    public ResponseEntity<?> getAliveDatabaseNodes(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<DatabaseNode> aliveNodes = hr.getAliveDatabaseNodes();
        return ResponseEntity.status(200).body(Map.of("aliveNodes", aliveNodes, "Timestamp", LocalDateTime.now()));
    }

}
