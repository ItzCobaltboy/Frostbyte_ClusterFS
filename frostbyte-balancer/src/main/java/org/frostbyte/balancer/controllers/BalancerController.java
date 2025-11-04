package org.frostbyte.balancer.controllers;

import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.frostbyte.balancer.services.DataNodeService;
import org.frostbyte.balancer.services.DatabaseNodeService;
import org.frostbyte.balancer.services.ReplicaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@RestController
@RequestMapping("/balancer")
public class BalancerController {

    private final configModel config;
    private final DataNodeService dataNodeService;
    private final ReplicaService replicaService;
    private final DatabaseNodeService databaseNodeService;
    private static final Logger log = Logger.getLogger(BalancerController.class.getName());
    private static final String API_HEADER = "X-API-Key";

    // Counter for round-robin datanode selection offset
    private final AtomicInteger chunkCounter = new AtomicInteger(0);

    @Autowired
    public BalancerController(configModel config,
                              DataNodeService dataNodeService,
                              ReplicaService replicaService,
                              DatabaseNodeService databaseNodeService) {
        this.config = config;
        this.dataNodeService = dataNodeService;
        this.replicaService = replicaService;
        this.databaseNodeService = databaseNodeService;
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }

    /**
     * Upload snowflake endpoint
     * Accepts an encrypted chunk (snowflake), creates replicas according to configuration,
     * and registers them in the database
     *
     * POST /balancer/upload/snowflake
     *
     * Request Parameters:
     * - snowflake: MultipartFile containing the encrypted chunk data
     * - chunkId: UUID of the chunk (from DatabaseNode)
     * - fileName: Original snowflake filename (e.g., "{snowflakeUuid}.snowflake")
     *
     * Response:
     * - status: success/failure
     * - message: Description of the operation
     * - replicasCreated: Number of replicas successfully created
     * - replicaLocations: List of datanode hosts where replicas were stored
     */
    @PostMapping("/upload/snowflake")
    public ResponseEntity<?> uploadSnowflake(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestParam("snowflake") MultipartFile snowflakeFile,
            @RequestParam("chunkId") String chunkId,
            @RequestParam(value = "fileName", required = false) String fileName) {

        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized snowflake upload attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (snowflakeFile.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No snowflake file provided"));
        }

        if (chunkId == null || chunkId.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID is required"));
        }

        try {
            // Use provided filename or generate from original filename
            String snowflakeFileName = fileName != null ? fileName : snowflakeFile.getOriginalFilename();
            if (snowflakeFileName == null || snowflakeFileName.isEmpty()) {
                snowflakeFileName = chunkId + ".snowflake";
            }

            log.info("Processing upload for snowflake: " + snowflakeFileName + " (chunk: " + chunkId + ")");

            // Step 1: Fetch available datanodes from MasterNode
            List<DataNodeInfo> availableNodes = dataNodeService.fetchAvailableDataNodes();

            if (availableNodes.isEmpty()) {
                log.severe("No datanodes available for upload");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "error", "No datanodes available",
                                "message", "Cannot upload snowflake - no storage nodes online"
                        ));
            }

            // Step 2: Select datanodes for replicas
            int replicaCount = config.getReplicaCount();
            int offset = chunkCounter.getAndIncrement(); // Different offset for each chunk
            List<DataNodeInfo> selectedNodes = dataNodeService.selectDataNodesForReplicas(
                    availableNodes, replicaCount, offset);

            if (selectedNodes.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Could not select datanodes for replication"));
            }

            log.info("Selected " + selectedNodes.size() + " datanodes for replication");

            // Step 3: Read snowflake data
            byte[] snowflakeData = snowflakeFile.getBytes();
            log.info("Snowflake size: " + snowflakeData.length + " bytes");

            // Step 4: Distribute replicas to selected datanodes
            List<String> successfulNodes = replicaService.distributeReplicas(
                    selectedNodes, snowflakeData, snowflakeFileName);

            if (successfulNodes.isEmpty()) {
                log.severe("Failed to upload snowflake to any datanode");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "error", "Failed to upload snowflake to any datanode",
                                "replicasCreated", 0
                        ));
            }

            // Step 5: Fetch DatabaseNode URL
            String databaseNodeUrl = databaseNodeService.fetchDatabaseNodeUrl();
            if (databaseNodeUrl == null) {
                log.severe("No database node available - replicas uploaded but not registered!");
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .body(Map.of(
                                "warning", "Replicas uploaded but not registered - no database node available",
                                "replicasCreated", successfulNodes.size(),
                                "replicaLocations", successfulNodes
                        ));
            }

            // Step 6: Register replicas in DatabaseNode
            boolean registered = replicaService.registerReplicasInDatabase(
                    chunkId, successfulNodes, databaseNodeUrl);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("chunkId", chunkId);
            response.put("replicasCreated", successfulNodes.size());
            response.put("replicasRequested", replicaCount);
            response.put("replicaLocations", successfulNodes);
            response.put("registered", registered);

            if (!registered) {
                response.put("warning", "Replicas uploaded but registration in database failed");
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
            }

            response.put("message", "Snowflake uploaded and replicas registered successfully");
            log.info("Snowflake upload completed: " + successfulNodes.size() + " replicas created for chunk " + chunkId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.severe("Error processing snowflake upload: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process snowflake upload",
                            "message", e.getMessage()
                    ));
        }
    }

    /**
     * Get available datanodes - utility endpoint for diagnostics
     *
     * GET /balancer/datanodes/available
     */
    @GetMapping("/datanodes/available")
    public ResponseEntity<?> getAvailableDataNodes(@RequestHeader(value = API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            List<DataNodeInfo> nodes = dataNodeService.fetchAvailableDataNodes();
            return ResponseEntity.ok(Map.of(
                    "count", nodes.size(),
                    "datanodes", nodes,
                    "replicaCount", config.getReplicaCount()
            ));
        } catch (Exception e) {
            log.severe("Error fetching available datanodes: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch datanodes"));
        }
    }

    /**
     * Health check endpoint
     *
     * GET /balancer/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "nodeName", config.getNodeName(),
                "replicaCount", config.getReplicaCount()
        ));
    }
}

