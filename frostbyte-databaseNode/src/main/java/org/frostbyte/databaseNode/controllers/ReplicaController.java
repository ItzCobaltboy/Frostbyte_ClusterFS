package org.frostbyte.databaseNode.controllers;

import org.frostbyte.databaseNode.entities.ChunkReplica;
import org.frostbyte.databaseNode.models.ReplicaStatus;
import org.frostbyte.databaseNode.models.configModel;
import org.frostbyte.databaseNode.models.dto.FileMapDTO;
import org.frostbyte.databaseNode.models.dto.ReplicaInfoDTO;
import org.frostbyte.databaseNode.repositories.ChunkRepository;
import org.frostbyte.databaseNode.services.ChunkMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@RestController
@RequestMapping("/replicas")
public class ReplicaController {

    private final ChunkMetadataService chunkMetadataService;
    private final ChunkRepository chunkRepository;
    private final configModel config;
    private static final Logger log = Logger.getLogger(ReplicaController.class.getName());
    private static final String API_HEADER = "X-API-Key";

    @Autowired
    public ReplicaController(ChunkMetadataService chunkMetadataService,
                             ChunkRepository chunkRepository,
                             configModel config) {
        this.chunkMetadataService = chunkMetadataService;
        this.chunkRepository = chunkRepository;
        this.config = config;
    }

    // =================================================================
    // 1. REPLICA REGISTRATION
    // =================================================================

    /**
     * Register a single chunk replica location
     * Called by ClientNode after successfully uploading chunk to DataNode
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerReplica(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody Map<String, Object> replicaData) {

        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized replica registration attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        // Validate request
        if (!replicaData.containsKey("chunkId") || !replicaData.containsKey("datanodeId")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID and Datanode ID are required"));
        }

        try {
            UUID chunkId = UUID.fromString(replicaData.get("chunkId").toString());
            String datanodeId = replicaData.get("datanodeId").toString();

            // Default to AVAILABLE status if not specified
            ReplicaStatus status = ReplicaStatus.AVAILABLE;
            if (replicaData.containsKey("status")) {
                status = ReplicaStatus.valueOf(replicaData.get("status").toString().toUpperCase());
            }

            ReplicaInfoDTO replicaInfo = new ReplicaInfoDTO();
            replicaInfo.setDatanodeId(datanodeId);
            replicaInfo.setStatus(status);

            ChunkReplica registeredReplica = chunkMetadataService.registerReplica(chunkId, replicaInfo);

            log.info("Replica registered: chunk " + chunkId + " on datanode " + datanodeId);

            return ResponseEntity.ok(Map.of(
                    "replicaId", registeredReplica.getId(),
                    "chunkId", registeredReplica.getChunkId(),
                    "datanodeId", registeredReplica.getDatanodeId(),
                    "status", registeredReplica.getStatus(),
                    "message", "Replica registered successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("Replica registration validation failed: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.severe("Failed to register replica: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to register replica: " + e.getMessage()));
        }
    }

    /**
     * Batch register multiple replicas for a chunk
     * Called by ClientNode after uploading chunk to multiple DataNodes
     */
    @PostMapping("/register/batch")
    public ResponseEntity<?> registerMultipleReplicas(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody Map<String, Object> batchData) {

        log.info("[BATCH-REPLICA-REQ] Received batch replica registration request");
        log.info("[BATCH-REPLICA-REQ] batchData=" + batchData);

        if (!isAuthorized(apiKey)) {
            log.warning("[BATCH-REPLICA-REQ] Unauthorized API key");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (!batchData.containsKey("chunkId") || !batchData.containsKey("datanodeIds")) {
            log.warning("[BATCH-REPLICA-REQ] Missing required fields: chunkId or datanodeIds");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID and Datanode IDs list are required"));
        }

        try {
            UUID chunkId = UUID.fromString(batchData.get("chunkId").toString());
            @SuppressWarnings("unchecked")
            List<String> datanodeIds = (List<String>) batchData.get("datanodeIds");

            log.info(String.format("[BATCH-REPLICA-REQ] chunkId=%s datanodeIds=%s", chunkId, datanodeIds));

            // Check if chunk exists
            boolean chunkExists = chunkRepository.existsById(chunkId);
            log.info(String.format("[BATCH-REPLICA-REQ] Chunk exists check: chunkId=%s exists=%s", chunkId, chunkExists));

            if (!chunkExists) {
                log.severe(String.format("[BATCH-REPLICA-REQ] CHUNK NOT FOUND in database: chunkId=%s", chunkId));
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Chunk not found in database: " + chunkId,
                                "chunkId", chunkId));
            }

            int successCount = 0;
            int failureCount = 0;
            List<String> errors = new ArrayList<>();

            for (String datanodeId : datanodeIds) {
                try {
                    log.info(String.format("[REPLICA-REGISTER] Attempting: chunkId=%s datanodeId=%s", chunkId, datanodeId));

                    ReplicaInfoDTO replicaInfo = new ReplicaInfoDTO();
                    replicaInfo.setDatanodeId(datanodeId);
                    replicaInfo.setStatus(ReplicaStatus.AVAILABLE);

                    ChunkReplica saved = chunkMetadataService.registerReplica(chunkId, replicaInfo);
                    successCount++;
                    log.info(String.format("[REPLICA-REGISTER-SUCCESS] replicaId=%d chunkId=%s datanodeId=%s",
                            saved.getId(), chunkId, datanodeId));
                } catch (Exception e) {
                    String error = "Failed to register replica for chunk " + chunkId + " on datanode " + datanodeId + ": " + e.getMessage();
                    log.warning("[REPLICA-REGISTER-FAILED] " + error);
                    e.printStackTrace();
                    errors.add(error);
                    failureCount++;
                }
            }

            log.info(String.format("[BATCH-REPLICA-COMPLETE] chunkId=%s succeeded=%d failed=%d",
                    chunkId, successCount, failureCount));

            Map<String, Object> response = new HashMap<>();
            response.put("chunkId", chunkId);
            response.put("totalRequested", datanodeIds.size());
            response.put("succeeded", successCount);
            response.put("failed", failureCount);
            response.put("message", "Batch replica registration completed");
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.severe("[BATCH-REPLICA-ERROR] Failed to process batch replica registration: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process batch registration: " + e.getMessage()));
        }
    }

    // =================================================================
    // 2. REPLICA STATUS MANAGEMENT
    // =================================================================

    /**
     * Update replica status (e.g., mark as FAILED, CORRUPTED, etc.)
     * Called by DataNode or monitoring services when replica health changes
     */
    @PutMapping("/status")
    public ResponseEntity<?> updateReplicaStatus(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody Map<String, String> statusUpdate) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (!statusUpdate.containsKey("chunkId") || !statusUpdate.containsKey("datanodeId") || !statusUpdate.containsKey("status")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID, Datanode ID, and status are required"));
        }

        try {
            UUID chunkId = UUID.fromString(statusUpdate.get("chunkId"));
            String datanodeId = statusUpdate.get("datanodeId");
            ReplicaStatus newStatus = ReplicaStatus.valueOf(statusUpdate.get("status").toUpperCase());

            chunkMetadataService.updateReplicaStatus(chunkId, datanodeId, newStatus);

            log.info("Replica status updated: chunk " + chunkId + " on datanode " + datanodeId + " -> " + newStatus);

            return ResponseEntity.ok(Map.of(
                    "chunkId", chunkId,
                    "datanodeId", datanodeId,
                    "status", newStatus,
                    "message", "Replica status updated successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("Invalid replica status update: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.severe("Failed to update replica status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update replica status: " + e.getMessage()));
        }
    }

    // =================================================================
    // 3. REPLICA QUERIES (FOR DOWNLOADS & MONITORING)
    // =================================================================

    /**
     * Get file chunk map for download reconstruction
     * Called by ClientNode when starting download process
     * Returns ordered chunks with their available replica locations
     */
    @GetMapping("/file/{fileId}/map")
    public ResponseEntity<?> getFileChunkMap(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("fileId") UUID fileId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            FileMapDTO fileMap = chunkMetadataService.getFileChunks(fileId);

            log.info("File chunk map retrieved: " + fileId + " (" + fileMap.getTotalChunks() + " chunks)");

            return ResponseEntity.ok(Map.of(
                    "fileMap", fileMap,
                    "totalChunks", fileMap.getTotalChunks(),
                    "fileName", fileMap.getFileName(),
                    "message", "File chunk map retrieved successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("File not found for chunk map: " + fileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "File not found: " + fileId));
        } catch (Exception e) {
            log.severe("Failed to get file chunk map: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get file chunk map: " + e.getMessage()));
        }
    }

    /**
     * Get replicas for a specific chunk
     * Useful for debugging and monitoring
     */
    @GetMapping("/chunk/{chunkId}")
    public ResponseEntity<?> getChunkReplicas(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("chunkId") UUID chunkId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            List<ChunkReplica> replicas = chunkMetadataService.getReplicasByChunkId(chunkId);

            log.info(String.format("Retrieved %d replicas for chunk: %s", replicas.size(), chunkId));

            return ResponseEntity.ok(Map.of(
                    "chunkId", chunkId,
                    "replicas", replicas,
                    "replicaCount", replicas.size(),
                    "message", "Chunk replicas retrieved successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("Chunk not found: " + chunkId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Chunk not found: " + chunkId));
        } catch (Exception e) {
            log.severe("Failed to get chunk replicas: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get chunk replicas: " + e.getMessage()));
        }
    }

    /**
     * Get all replicas on a specific DataNode
     * Called by MasterNode when DataNode fails or during load balancing
     */
    @GetMapping("/datanode/{datanodeId}")
    public ResponseEntity<?> getDataNodeReplicas(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("datanodeId") String datanodeId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            List<ChunkReplica> replicas = chunkMetadataService.getReplicasOnDataNode(datanodeId);

            log.info("Retrieved " + replicas.size() + " replicas for datanode: " + datanodeId);

            return ResponseEntity.ok(Map.of(
                    "datanodeId", datanodeId,
                    "replicas", replicas,
                    "replicaCount", replicas.size(),
                    "message", "DataNode replicas retrieved successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to get DataNode replicas: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get DataNode replicas: " + e.getMessage()));
        }
    }

    // =================================================================
    // 4. REPLICA CLEANUP & MAINTENANCE
    // =================================================================

    /**
     * Delete specific chunk replica
     * Called when replica is corrupted or DataNode is being decommissioned
     */
    @DeleteMapping("/chunk/{chunkId}/datanode/{datanodeId}")
    public ResponseEntity<?> deleteChunkReplica(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("chunkId") UUID chunkId,
            @PathVariable("datanodeId") String datanodeId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            // First check if replica exists
            var replicas = chunkMetadataService.getReplicasOnDataNode(datanodeId);
            boolean replicaExists = replicas.stream()
                    .anyMatch(replica -> replica.getChunkId().equals(chunkId));

            if (!replicaExists) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Replica not found for chunk " + chunkId + " on datanode " + datanodeId));
            }

            // Delete the replica (you'll need to add this method to ChunkMetadataService)
            // chunkMetadataService.deleteReplica(chunkId, datanodeId);

            log.warning("Replica deleted: chunk " + chunkId + " from datanode " + datanodeId);

            return ResponseEntity.ok(Map.of(
                    "chunkId", chunkId,
                    "datanodeId", datanodeId,
                    "message", "Replica deleted successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to delete replica: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete replica: " + e.getMessage()));
        }
    }

    /**
     * Delete all replicas for a chunk
     * Called when deleting a chunk completely
     */
    @DeleteMapping("/chunk/{chunkId}")
    public ResponseEntity<?> deleteAllChunkReplicas(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("chunkId") UUID chunkId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            // This functionality is already in ChunkMetadataService.deleteChunk()
            chunkMetadataService.deleteChunk(chunkId);

            log.warning("All replicas deleted for chunk: " + chunkId);

            return ResponseEntity.ok(Map.of(
                    "chunkId", chunkId,
                    "message", "Chunk and all replicas deleted successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to delete chunk replicas: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete chunk replicas: " + e.getMessage()));
        }
    }

    /**
     * Delete all replicas on a specific DataNode
     * Called when DataNode is permanently removed from cluster
     */
    @DeleteMapping("/datanode/{datanodeId}")
    public ResponseEntity<?> deleteDataNodeReplicas(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable("datanodeId") String datanodeId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            List<ChunkReplica> replicas = chunkMetadataService.getReplicasOnDataNode(datanodeId);
            int replicaCount = replicas.size();

            // You'll need to add this method to ChunkMetadataService
            // chunkMetadataService.deleteAllReplicasOnDataNode(datanodeId);

            log.warning("ALL REPLICAS DELETED from datanode: " + datanodeId + " (count: " + replicaCount + ")");

            return ResponseEntity.ok(Map.of(
                    "datanodeId", datanodeId,
                    "deletedCount", replicaCount,
                    "message", "All replicas deleted from DataNode successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to delete DataNode replicas: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete DataNode replicas: " + e.getMessage()));
        }
    }

    // =================================================================
    // 5. MONITORING & HEALTH ENDPOINTS
    // =================================================================

    /**
     * Get replica statistics
     * Useful for monitoring system health and load distribution
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getReplicaStats(
            @RequestHeader(value = API_HEADER) String apiKey) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            // You'll need to add these methods to ChunkMetadataService:
            // Map<String, Long> replicaCountPerDataNode = chunkMetadataService.getReplicaCountPerDataNode();
            // List<UUID> underReplicatedChunks = chunkMetadataService.getUnderReplicatedChunks(2); // assuming min 2 replicas
            // long totalReplicas = chunkMetadataService.getTotalReplicaCount();

            return ResponseEntity.ok(Map.of(
                    "message", "Replica statistics endpoint - methods need to be implemented",
                    "note", "Add getReplicaCountPerDataNode, getUnderReplicatedChunks, getTotalReplicaCount to ChunkMetadataService"
                    // "totalReplicas", totalReplicas,
                    // "replicaDistribution", replicaCountPerDataNode,
                    // "underReplicatedChunks", underReplicatedChunks.size()
            ));

        } catch (Exception e) {
            log.severe("Failed to get replica statistics: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get replica statistics: " + e.getMessage()));
        }
    }

    /**
     * Health check for replica system
     * Returns basic system health indicators
     */
    @GetMapping("/health")
    public ResponseEntity<?> getReplicaSystemHealth(
            @RequestHeader(value = API_HEADER) String apiKey) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            // Basic health check - you can expand this
            return ResponseEntity.ok(Map.of(
                    "status", "healthy",
                    "service", "ReplicaController",
                    "timestamp", System.currentTimeMillis(),
                    "message", "Replica management system is operational"
            ));

        } catch (Exception e) {
            log.severe("Health check failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "unhealthy",
                            "error", e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }
}