package org.frostbyte.databaseNode.controllers;

import org.frostbyte.databaseNode.entities.File;
import org.frostbyte.databaseNode.entities.UploadSession;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.frostbyte.databaseNode.models.configModel;
import org.frostbyte.databaseNode.models.dto.ChunkMetadataDTO;
import org.frostbyte.databaseNode.models.dto.FileMetadataDTO;
import org.frostbyte.databaseNode.services.ChunkMetadataService;
import org.frostbyte.databaseNode.services.FileService;
import org.frostbyte.databaseNode.services.UploadSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final UploadSessionService uploadSessionService;
    private final FileService fileService;
    private final ChunkMetadataService chunkMetadataService;
    private final configModel config;
    private static final Logger log = Logger.getLogger(UploadController.class.getName());
    private static final String API_HEADER = "X-API-Key";

    @Autowired
    public UploadController(UploadSessionService uploadSessionService,
                            FileService fileService,
                            ChunkMetadataService chunkMetadataService,
                            configModel config) {
        this.uploadSessionService = uploadSessionService;
        this.fileService = fileService;
        this.chunkMetadataService = chunkMetadataService;
        this.config = config;
    }

    // =================================================================
    // 1. UPLOAD SESSION INITIALIZATION
    // =================================================================

    /**
     * Initialize upload session and create file record
     * Called by ClientNode at the start of upload process
     */
    @PostMapping("/initialize")
    public ResponseEntity<?> initializeUpload(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody FileMetadataDTO fileMetadata) {

        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized upload initialization attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        // Validate request
        if (fileMetadata == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File metadata is required"));
        }

        if (fileMetadata.getFileName() == null || fileMetadata.getFileName().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File name is required"));
        }

        if (fileMetadata.getClientNodeId() == null || fileMetadata.getClientNodeId().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Client node ID is required"));
        }

        try {
            UploadSessionService.UploadSessionResponse response = uploadSessionService.initializeUploadSession(fileMetadata);

            log.info("Upload session initialized: " + response.getSessionId() +
                    " for file: " + fileMetadata.getFileName());

            return ResponseEntity.ok(Map.of(
                    "sessionId", response.getSessionId(),
                    "fileId", response.getFileId(),
                    "status", response.getStatus(),
                    "expectedChunks", response.getExpectedChunks(),
                    "message", "Upload session initialized successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to initialize upload session: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to initialize upload: " + e.getMessage()));
        }
    }

    // =================================================================
    // 2. CHUNK REGISTRATION
    // =================================================================

    /**
     * Register chunk metadata during upload
     * Called by ClientNode after each chunk is processed and uploaded to DataNodes
     */
    @PostMapping("/chunk/register")
    public ResponseEntity<?> registerChunk(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody ChunkMetadataDTO chunkData) {

        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized chunk registration attempt");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        // Validate request
        if (chunkData == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk metadata is required"));
        }

        if (chunkData.getChunkId() == null || chunkData.getFileId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID and File ID are required"));
        }

        try {
            var registeredChunk = chunkMetadataService.registerChunk(chunkData);

            log.info("Chunk registered: " + registeredChunk.getChunkId() +
                    " (chunk " + registeredChunk.getChunkNumber() + " of file " + registeredChunk.getFileId() + ")");

            return ResponseEntity.ok(Map.of(
                    "chunkId", registeredChunk.getChunkId(),
                    "chunkNumber", registeredChunk.getChunkNumber(),
                    "fileId", registeredChunk.getFileId(),
                    "status", "registered",
                    "message", "Chunk registered successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("Chunk registration validation failed: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.severe("Failed to register chunk: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to register chunk: " + e.getMessage()));
        }
    }

    // =================================================================
    // 3. SESSION STATUS MANAGEMENT
    // =================================================================

    /**
     * Update session status
     * Called by ClientNode to mark session as completed/failed
     */
    @PutMapping("/session/{sessionId}/status")
    public ResponseEntity<?> updateSessionStatus(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, String> statusUpdate) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (!statusUpdate.containsKey("status")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Status field is required"));
        }

        try {
            UploadStatus newStatus = UploadStatus.valueOf(statusUpdate.get("status").toUpperCase());
            UploadSession updatedSession = uploadSessionService.updateSessionStatus(sessionId, newStatus);

            log.info("Session status updated: " + sessionId + " -> " + newStatus);

            return ResponseEntity.ok(Map.of(
                    "sessionId", updatedSession.getSessionId(),
                    "status", updatedSession.getStatus(),
                    "chunksReceived", updatedSession.getChunksReceived(),
                    "message", "Session status updated successfully"
            ));

        } catch (IllegalArgumentException e) {
            log.warning("Invalid status update: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.severe("Failed to update session status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update session status: " + e.getMessage()));
        }
    }

    /**
     * Complete upload session
     * Called by ClientNode when all chunks are uploaded and registered
     */
    @PostMapping("/session/{sessionId}/complete")
    public ResponseEntity<?> completeSession(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID sessionId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            UploadSession completedSession = uploadSessionService.completeSession(sessionId);

            log.info("Upload session completed: " + sessionId);

            return ResponseEntity.ok(Map.of(
                    "sessionId", completedSession.getSessionId(),
                    "status", completedSession.getStatus(),
                    "chunksReceived", completedSession.getChunksReceived(),
                    "message", "Upload completed successfully"
            ));

        } catch (IllegalStateException e) {
            log.warning("Cannot complete session: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.severe("Failed to complete session: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to complete session: " + e.getMessage()));
        }
    }

    /**
     * Set balancer node for session
     * Called by ClientNode after getting balancer allocation
     */
    @PutMapping("/session/{sessionId}/balancer")
    public ResponseEntity<?> setBalancerNode(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, String> balancerInfo) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (!balancerInfo.containsKey("balancerNodeId")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Balancer node ID is required"));
        }

        try {
            UploadSession updatedSession = uploadSessionService.setBalancerNode(
                    sessionId, balancerInfo.get("balancerNodeId"));

            log.info("Balancer node set for session: " + sessionId + " -> " + balancerInfo.get("balancerNodeId"));

            return ResponseEntity.ok(Map.of(
                    "sessionId", updatedSession.getSessionId(),
                    "balancerNode", updatedSession.getBalancerNode(),
                    "message", "Balancer node set successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to set balancer node: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to set balancer node: " + e.getMessage()));
        }
    }

    // =================================================================
    // 4. QUERY ENDPOINTS
    // =================================================================

    /**
     * Get session status and progress
     */
    @GetMapping("/session/{sessionId}/status")
    public ResponseEntity<?> getSessionStatus(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID sessionId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            UploadSession session = uploadSessionService.getSession(sessionId);
            double progress = uploadSessionService.getSessionProgress(sessionId);
            boolean isComplete = uploadSessionService.isSessionComplete(sessionId);

            return ResponseEntity.ok(Map.of(
                    "sessionId", session.getSessionId(),
                    "status", session.getStatus(),
                    "chunksReceived", session.getChunksReceived(),
                    "progress", progress,
                    "isComplete", isComplete,
                    "clientNode", session.getClientNode(),
                    "balancerNode", session.getBalancerNode() != null ? session.getBalancerNode() : "Not assigned"
            ));

        } catch (Exception e) {
            log.warning("Session not found: " + sessionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found: " + sessionId));
        }
    }

    /**
     * Get file information
     */
    @GetMapping("/file/{fileId}")
    public ResponseEntity<?> getFileInfo(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID fileId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            File file = fileService.getFile(fileId);
            double progress = fileService.getFileUploadProgress(fileId);
            boolean isComplete = fileService.isFileUploadComplete(fileId);

            return ResponseEntity.ok(Map.of(
                    "fileId", file.getFileId(),
                    "fileName", file.getFileName(),
                    "fileSize", file.getFileSize(),
                    "totalChunks", file.getTotalChunks(),
                    "uploadStatus", file.getUploadStatus(),
                    "progress", progress,
                    "isComplete", isComplete,
                    "sessionId", file.getSessionId()
            ));

        } catch (Exception e) {
            log.warning("File not found: " + fileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "File not found: " + fileId));
        }
    }

    /**
     * Get sessions by status
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getSessionsByStatus(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestParam(required = false) String status) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            if (status != null) {
                UploadStatus uploadStatus = UploadStatus.valueOf(status.toUpperCase());
                List<UploadSession> sessions = uploadSessionService.getSessionsByStatus(uploadStatus);
                return ResponseEntity.ok(Map.of(
                        "sessions", sessions,
                        "count", sessions.size(),
                        "filter", "status=" + status
                ));
            } else {
                // If no status provided, return count by status
                return ResponseEntity.ok(Map.of(
                        "uploading", fileService.getFileCountByStatus(UploadStatus.UPLOADING),
                        "completed", fileService.getFileCountByStatus(UploadStatus.COMPLETED),
                        "failed", fileService.getFileCountByStatus(UploadStatus.FAILED)
                ));
            }

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid status: " + status));
        } catch (Exception e) {
            log.severe("Failed to get sessions: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get sessions: " + e.getMessage()));
        }
    }

    // =================================================================
    // 5. DELETE OPERATIONS
    // =================================================================

    /**
     * Delete file and all associated data
     * DANGEROUS OPERATION - cascades to chunks and replicas
     */
    @DeleteMapping("/file/{fileId}")
    public ResponseEntity<?> deleteFile(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID fileId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            File file = fileService.getFile(fileId); // Get file info before deletion
            fileService.deleteFile(fileId);

            log.warning("FILE DELETED: " + file.getFileName() + " (" + fileId + ")");

            return ResponseEntity.ok(Map.of(
                    "fileId", fileId,
                    "fileName", file.getFileName(),
                    "message", "File and all associated data deleted successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to delete file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete file: " + e.getMessage()));
        }
    }

    /**
     * Delete session and associated file
     * DANGEROUS OPERATION - cascades to file, chunks, and replicas
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<?> deleteSession(
            @RequestHeader(value = API_HEADER) String apiKey,
            @PathVariable UUID sessionId) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        try {
            UploadSession session = uploadSessionService.getSession(sessionId); // Get session info before deletion
            uploadSessionService.deleteSession(sessionId);

            log.warning("SESSION DELETED: " + sessionId);

            return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "clientNode", session.getClientNode(),
                    "message", "Session and all associated data deleted successfully"
            ));

        } catch (Exception e) {
            log.severe("Failed to delete session: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete session: " + e.getMessage()));
        }
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }
}