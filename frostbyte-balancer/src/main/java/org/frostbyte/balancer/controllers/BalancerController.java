package org.frostbyte.balancer.controllers;

import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.frostbyte.balancer.services.DataNodeService;
import org.frostbyte.balancer.services.DatabaseNodeService;
import org.frostbyte.balancer.services.DownloadService;
import org.frostbyte.balancer.services.ReplicaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/*
*   PRIMARY CONTROLLER FOR BALANCER NODE
* */
@RestController
@RequestMapping("/balancer")
public class BalancerController {

    private final configModel config;
    private final DataNodeService dataNodeService;
    private final ReplicaService replicaService;
    private final DatabaseNodeService databaseNodeService;
    private final DownloadService downloadService;
    private static final Logger log = Logger.getLogger(BalancerController.class.getName());
    private static final String API_HEADER = "X-API-Key";

    // Counter for round-robin datanode selection offset
    private final AtomicInteger chunkCounter = new AtomicInteger(0);

    @Autowired
    public BalancerController(configModel config,
                              DataNodeService dataNodeService,
                              ReplicaService replicaService,
                              DatabaseNodeService databaseNodeService,
                              DownloadService downloadService) {
        this.config = config;
        this.dataNodeService = dataNodeService;
        this.replicaService = replicaService;
        this.databaseNodeService = databaseNodeService;
        this.downloadService = downloadService;
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }


    // CHUNK UPLOAD & REPLICATION
    @PostMapping("/upload/snowflake")
    public ResponseEntity<?> uploadSnowflake(
            @RequestHeader(value = API_HEADER) String apiKey, // ask for API key in header
            @RequestParam("snowflake") MultipartFile snowflakeFile, // snowflake file upload
            @RequestParam("chunkId") String chunkId, // chunk ID
            @RequestParam(value = "fileName", required = false) String fileName) { // optional original filename

        // HTTP ERROR HANDLING and AUTH
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

            // Step 2: Select datanodes for replicas using capacity-aware heap algorithm

            /*
                * Greedy based Latin Rectangle Algorithm for Replica Selection
                * - Uses min-heap based on projectedFillPercent to select least-loaded nodes
                * - Ensures no duplicate nodes for the same chunk (Latin rectangle property)
                *
             */
            int replicaCount = config.getReplicaCount();
            log.fine("Selecting "+ replicaCount +" datanodes for replicas from" + availableNodes.size() + "available nodes");
            int offset = chunkCounter.getAndIncrement(); // Different offset for each chunk (legacy param)
            List<DataNodeInfo> selectedNodes = dataNodeService.selectDataNodesForReplicas(
                    availableNodes, replicaCount, offset);

            if (selectedNodes.isEmpty()) {
                log.severe("No datanodes selected - all nodes may be at capacity or unavailable");
                return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                        .body(Map.of(
                                "error", "No datanodes available with sufficient capacity",
                                "message", "All storage nodes are at or near capacity (>95% full)"
                        ));
            }

            // Warn if we couldn't get the requested number of replicas
            if (selectedNodes.size() < replicaCount) {
                log.warning(String.format("Partial replica allocation: selected %d/%d replicas due to capacity constraints",
                        selectedNodes.size(), replicaCount));
            }

            log.info(String.format("Selected %d datanodes for replication (requested: %d)",
                    selectedNodes.size(), replicaCount));

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
                log.warning("Failed to register replicas in database");
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
            }

            // Warn if we created fewer replicas than requested due to capacity constraints
            if (successfulNodes.size() < replicaCount) {
                response.put("warning", String.format(
                        "Partial replication: created %d/%d replicas due to capacity constraints",
                        successfulNodes.size(), replicaCount));
                response.put("message", "Snowflake uploaded with reduced replication factor");
                log.warning(String.format("Upload completed with reduced replication: %d/%d replicas for chunk %s",
                        successfulNodes.size(), replicaCount, chunkId));
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

    // 2. DIAGNOSTIC ENDPOINTS
    /**
     * Endpoint to fetch available DataNodes from MasterNode
     *
     * @param apiKey Internal API key for authentication
     * @return JSON with count, datanodes list, and configured replicaCount
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
     * Health check endpoint for monitoring BalancerNode status.
     *
     * @return JSON with status, nodeName, and configured replicaCount
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "nodeName", config.getNodeName(),
                "replicaCount", config.getReplicaCount()
        ));
    }

    // =================================================================
    // 3. CHUNK DOWNLOAD
    // =================================================================

    /**
     * Download chunk from DataNode with automatic replica failover.
     * Called by ClientNode for each chunk during file download.
     *
     * <p>Process:
     * <ol>
     *   <li>Query DatabaseNode for chunk replica locations</li>
     *   <li>Select random replica from available locations</li>
     *   <li>Download from selected DataNode</li>
     *   <li>Validate CRC32 checksum</li>
     *   <li>If validation fails or node unavailable, retry with next replica</li>
     * </ol>
     *
     * @param apiKey Internal API key for authentication
     * @param requestBody JSON with fileId, chunkId, chunkNumber fields
     * @return Binary snowflake data (metadata + encrypted chunk), 404 if no replicas available,
     *         500 if all replicas fail CRC validation
     */
    @PostMapping(value = "/download/chunk", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> downloadChunk(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody Map<String, Object> requestBody) {

        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized chunk download attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: Invalid API key".getBytes());
        }

        // Validate request body
        if (!requestBody.containsKey("fileId") || !requestBody.containsKey("chunkId") || !requestBody.containsKey("chunkNumber")) {
            return ResponseEntity.badRequest()
                    .body("Missing required fields: fileId, chunkId, chunkNumber".getBytes());
        }

        String fileId = requestBody.get("fileId").toString();
        String chunkId = requestBody.get("chunkId").toString();
        int chunkNumber = Integer.parseInt(requestBody.get("chunkNumber").toString());

        log.info(String.format("[CHUNK-REQUEST] fileId=%s chunkId=%s chunkNumber=%d", fileId, chunkId, chunkNumber));

        try {
            // Download chunk with automatic replica selection and failover
            byte[] snowflakeBytes = downloadService.downloadChunk(fileId, chunkId, chunkNumber);

            log.info(String.format("[CHUNK-RESPONSE] chunkId=%s snowflakeSize=%d", chunkId, snowflakeBytes.length));

            // Return encrypted snowflake as binary data
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(snowflakeBytes.length)
                    .body(snowflakeBytes);

        } catch (DownloadService.ChunkDownloadException e) {
            log.severe(String.format("[CHUNK-DOWNLOAD-FAILED] chunkId=%s error=%s", chunkId, e.getMessage()));

            if (e.getMessage().contains("No replicas found") || e.getMessage().contains("No available replicas")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("Chunk not found or no replicas available: " + e.getMessage()).getBytes());
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Chunk download failed: " + e.getMessage()).getBytes());
        } catch (Exception e) {
            log.severe(String.format("[CHUNK-DOWNLOAD-ERROR] chunkId=%s error=%s", chunkId, e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Internal server error: " + e.getMessage()).getBytes());
        }
    }
}

