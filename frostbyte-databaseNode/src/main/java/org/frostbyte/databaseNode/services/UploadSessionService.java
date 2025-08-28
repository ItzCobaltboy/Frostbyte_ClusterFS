package org.frostbyte.databaseNode.services;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.frostbyte.databaseNode.entities.File;
import org.frostbyte.databaseNode.entities.UploadSession;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.frostbyte.databaseNode.models.dto.FileMetadataDTO;
import org.frostbyte.databaseNode.repositories.UploadSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class UploadSessionService {

    private static final Logger log = Logger.getLogger(UploadSessionService.class.getName());

    private final UploadSessionRepository uploadSessionRepository;
    private final FileService fileService; // Injected FileService

    @Autowired
    public UploadSessionService(UploadSessionRepository uploadSessionRepository,
                                FileService fileService) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.fileService = fileService;
    }

    // =================================================================
    // 1. INITIALIZE UPLOAD SESSION (CREATES SESSION + FILE)
    // =================================================================

    /**
     * Initializes a new upload session and creates associated file record
     * This is the main entry point for starting file uploads
     * Returns both session and file information
     */
    @Transactional
    public UploadSessionResponse initializeUploadSession(FileMetadataDTO fileMetadata) {
        log.info("Initializing upload session for file: " + fileMetadata.getFileName() +
                " from client: " + fileMetadata.getClientNodeId());

        // ========== VALIDATIONS ==========

        if (fileMetadata.getClientNodeId() == null || fileMetadata.getClientNodeId().trim().isEmpty()) {
            throw new IllegalArgumentException("Client node ID cannot be null or empty");
        }

        // ========== CREATE UPLOAD SESSION ==========

        UploadSession session = new UploadSession();
        session.setSessionId(UUID.randomUUID());
        session.setClientNode(fileMetadata.getClientNodeId());
        session.setStatus(UploadStatus.UPLOADING);
        session.setChunksReceived(0);
        // Note: balancerNode will be set later when ClientNode allocates chunks

        UploadSession savedSession = uploadSessionRepository.save(session);
        log.info("Upload session created: " + savedSession.getSessionId());

        // ========== CREATE ASSOCIATED FILE RECORD (via FileService) ==========

        File createdFile = fileService.createFile(fileMetadata, savedSession.getSessionId());
        log.info("File record created: " + createdFile.getFileId() + " linked to session: " + savedSession.getSessionId());

        // ========== RETURN RESPONSE ==========

        UploadSessionResponse response = new UploadSessionResponse();
        response.setSessionId(savedSession.getSessionId());
        response.setFileId(createdFile.getFileId());
        response.setStatus(savedSession.getStatus());
        response.setExpectedChunks(createdFile.getTotalChunks());

        log.info("Upload session initialized successfully: " + response.getSessionId());
        return response;
    }

    // =================================================================
    // 2. UPDATE SESSION STATUS
    // =================================================================

    /**
     * Updates session status and cascades to file status via FileService
     */
    @Transactional
    public UploadSession updateSessionStatus(UUID sessionId, UploadStatus newStatus) {
        log.info("Updating session status: " + sessionId + " to " + newStatus);

        // ========== VALIDATIONS ==========

        UploadSession session = uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        // Validate status transition
        UploadStatus currentStatus = session.getStatus();
        if (!isValidSessionStatusTransition(currentStatus, newStatus)) {
            throw new IllegalStateException("Invalid session status transition from " + currentStatus + " to " + newStatus);
        }

        // ========== UPDATE SESSION ==========

        session.setStatus(newStatus);
        session.setUpdatedAt(Timestamp.from(Instant.now()));
        UploadSession updatedSession = uploadSessionRepository.save(session);

        // ========== CASCADE UPDATE TO FILE (via FileService) ==========

        fileService.updateFileStatusBySession(sessionId, newStatus);
        log.info("Session and file status updated: " + sessionId + " -> " + newStatus);

        return updatedSession;
    }

    /**
     * Set balancer node for session (called when ClientNode gets allocation)
     */
    @Transactional
    public UploadSession setBalancerNode(UUID sessionId, String balancerNodeId) {
        log.info("Setting balancer node for session: " + sessionId + " -> " + balancerNodeId);

        UploadSession session = uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setBalancerNode(balancerNodeId);
        session.setUpdatedAt(Timestamp.from(Instant.now()));

        return uploadSessionRepository.save(session);
    }

    // =================================================================
    // 3. CHUNK PROGRESS TRACKING
    // =================================================================

    /**
     * Increment chunk counter (called by ChunkMetadataService)
     * NOTE: This method is called FROM ChunkMetadataService, so we don't call it here
     * This is just for manual updates if needed
     */
    @Transactional
    public UploadSession incrementChunkCounter(UUID sessionId) {
        log.info("Incrementing chunk counter for session: " + sessionId);

        UploadSession session = uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setChunksReceived(session.getChunksReceived() + 1);
        session.setUpdatedAt(Timestamp.from(Instant.now()));

        UploadSession updatedSession = uploadSessionRepository.save(session);
        log.info("Chunk counter incremented: " + sessionId + " -> " + updatedSession.getChunksReceived());

        return updatedSession;
    }

    /**
     * Check if session is complete (all chunks received)
     */
    @Transactional(readOnly = true)
    public boolean isSessionComplete(UUID sessionId) {
        UploadSession session = getSession(sessionId);
        File file = fileService.getFileBySession(sessionId);

        boolean isComplete = session.getChunksReceived() >= file.getTotalChunks();
        log.info("Session completeness check: " + sessionId +
                " - " + session.getChunksReceived() + "/" + file.getTotalChunks() +
                " chunks (" + (isComplete ? "COMPLETE" : "INCOMPLETE") + ")");

        return isComplete;
    }

    // =================================================================
    // 4. COMPLETE/FINALIZE SESSION
    // =================================================================

    /**
     * Complete upload session (validates all chunks received)
     */
    @Transactional
    public UploadSession completeSession(UUID sessionId) {
        log.info("Attempting to complete session: " + sessionId);

        // ========== VALIDATIONS ==========

        UploadSession session = getSession(sessionId);
        File file = fileService.getFileBySession(sessionId);

        // Check if all chunks are received
        if (session.getChunksReceived() < file.getTotalChunks()) {
            throw new IllegalStateException("Cannot complete session - missing chunks. " +
                    "Received: " + session.getChunksReceived() + ", Expected: " + file.getTotalChunks());
        }

        // Verify file upload is actually complete (double-check with FileService)
        if (!fileService.isFileUploadComplete(file.getFileId())) {
            throw new IllegalStateException("Cannot complete session - file upload not complete according to FileService");
        }

        // ========== COMPLETE SESSION ==========

        UploadSession completedSession = updateSessionStatus(sessionId, UploadStatus.COMPLETED);
        log.info("Session completed successfully: " + sessionId);

        return completedSession;
    }

    /**
     * Fail session (mark as failed)
     */
    @Transactional
    public UploadSession failSession(UUID sessionId, String reason) {
        log.warning("Failing session: " + sessionId + " - Reason: " + reason);

        UploadSession failedSession = updateSessionStatus(sessionId, UploadStatus.FAILED);
        // Could add a reason field to UploadSession entity if needed

        return failedSession;
    }

    // =================================================================
    // 5. FETCH SESSION FUNCTIONS
    // =================================================================

    /**
     * Get session by ID
     */
    @Transactional(readOnly = true)
    public UploadSession getSession(UUID sessionId) {
        return uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    /**
     * Get sessions by status
     */
    @Transactional(readOnly = true)
    public List<UploadSession> getSessionsByStatus(UploadStatus status) {
        return uploadSessionRepository.findByStatus(status);
    }

    /**
     * Get sessions by client node
     */
    @Transactional(readOnly = true)
    public List<UploadSession> getSessionsByClientNode(String clientNodeId) {
        return uploadSessionRepository.findByClientNode(clientNodeId);
    }

    /**
     * Get session progress percentage
     */
    @Transactional(readOnly = true)
    public double getSessionProgress(UUID sessionId) {
        UploadSession session = getSession(sessionId);
        File file = fileService.getFileBySession(sessionId);

        if (file.getTotalChunks() == 0) return 0.0;

        double progress = (double) session.getChunksReceived() / file.getTotalChunks() * 100.0;
        return Math.round(progress * 100.0) / 100.0; // Round to 2 decimal places
    }

    // =================================================================
    // 6. DELETE SESSION (CLEANUP)
    // =================================================================

    /**
     * Delete session and associated file (CASCADE DELETE)
     * DANGEROUS OPERATION - use with caution!
     */
    @Transactional
    public void deleteSession(UUID sessionId) {
        log.warning("DELETING SESSION AND ALL DATA: " + sessionId);

        UploadSession session = getSession(sessionId);
        File file = fileService.getFileBySession(sessionId);

        // Delete file first (cascades to chunks and replicas via FileService)
        fileService.deleteFile(file.getFileId());

        // Then delete session
        uploadSessionRepository.delete(session);
        log.warning("SESSION DELETED: " + sessionId);
    }

    // =================================================================
    // 7. VALIDATION & UTILITY FUNCTIONS
    // =================================================================

    /**
     * Validate session status transitions
     */
    private boolean isValidSessionStatusTransition(UploadStatus from, UploadStatus to) {
        if (from == null) return true; // Allow any initial status

        switch (from) {
            case UPLOADING:
                return to == UploadStatus.COMPLETED || to == UploadStatus.FAILED;
            case COMPLETED:
                return to == UploadStatus.FAILED; // Allow marking completed as failed (rare case)
            case FAILED:
                return to == UploadStatus.UPLOADING; // Allow retry
            default:
                return false;
        }
    }

    /**
     * Check if session exists
     */
    @Transactional(readOnly = true)
    public boolean sessionExists(UUID sessionId) {
        return uploadSessionRepository.existsById(sessionId);
    }

    // =================================================================
    // 8. RESPONSE DTO CLASS
    // =================================================================

    /**
     * Response DTO for session initialization
     */
    @Data
    public static class UploadSessionResponse {
        // Getters and setters
        private UUID sessionId;
        private UUID fileId;
        private UploadStatus status;
        private int expectedChunks;

    }
}