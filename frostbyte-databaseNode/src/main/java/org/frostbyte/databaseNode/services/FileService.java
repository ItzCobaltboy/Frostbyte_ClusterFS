package org.frostbyte.databaseNode.services;

import org.frostbyte.databaseNode.entities.*;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.frostbyte.databaseNode.models.dto.FileMetadataDTO;
import org.frostbyte.databaseNode.repositories.FileRepository;
import org.frostbyte.databaseNode.repositories.ChunkRepository;
import org.frostbyte.databaseNode.repositories.ChunkReplicaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class FileService {

    private static final Logger log = Logger.getLogger(FileService.class.getName());

    private final FileRepository fileRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;

    @Autowired
    public FileService(FileRepository fileRepository,
                       ChunkRepository chunkRepository,
                       ChunkReplicaRepository chunkReplicaRepository) {
        this.fileRepository = fileRepository;
        this.chunkRepository = chunkRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
    }

    // =================================================================
    // 1. CREATE FILE FUNCTION
    // =================================================================

    /**
     * Creates a new file record with metadata
     * Called by UploadSessionService during session initialization
     */
    @Transactional
    public File createFile(FileMetadataDTO fileMetadata, UUID sessionId) {
        log.info("Creating file record: " + fileMetadata.getFileName() +
                " (Size: " + fileMetadata.getFileSize() + " bytes, Chunks: " + fileMetadata.getTotalChunks() + ")");

        // ========== VALIDATIONS ==========

        // 1. Validate file metadata
        if (fileMetadata.getFileName() == null || fileMetadata.getFileName().trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }

        if (fileMetadata.getFileSize() <= 0) {
            throw new IllegalArgumentException("File size must be greater than 0");
        }

        if (fileMetadata.getTotalChunks() <= 0) {
            throw new IllegalArgumentException("Total chunks must be greater than 0");
        }

        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID cannot be null");
        }

        // 2. Check for duplicate filename (optional business rule)
        // Uncomment if you want unique filenames per system
        /*
        if (fileRepository.existsByFileName(fileMetadata.getFileName())) {
            throw new IllegalArgumentException("File with name already exists: " + fileMetadata.getFileName());
        }
        */

        // ========== CREATE FILE RECORD ==========

        File file = new File();
        file.setFileId(UUID.randomUUID());
        file.setFileName(fileMetadata.getFileName());
        file.setFileSize(fileMetadata.getFileSize());
        file.setTotalChunks(fileMetadata.getTotalChunks());
        file.setUploadStatus(UploadStatus.UPLOADING); // Start in UPLOADING state
        file.setSessionId(sessionId);

        File savedFile = fileRepository.save(file);
        log.info("File created successfully: " + savedFile.getFileId());

        return savedFile;
    }

    // =================================================================
    // 2. UPDATE FILE STATUS FUNCTION
    // =================================================================

    /**
     * Updates file upload status
     * Called by UploadSessionService when session status changes
     */
    @Transactional
    public File updateFileStatus(UUID fileId, UploadStatus newStatus) {
        log.info("Updating file status: " + fileId + " to " + newStatus);

        // ========== VALIDATIONS ==========

        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        // Validate status transition (optional business logic)
        UploadStatus currentStatus = file.getUploadStatus();
        if (!isValidStatusTransition(currentStatus, newStatus)) {
            throw new IllegalStateException("Invalid status transition from " + currentStatus + " to " + newStatus);
        }

        // ========== UPDATE STATUS ==========

        file.setUploadStatus(newStatus);
        File updatedFile = fileRepository.save(file);

        log.info("File status updated: " + fileId + " -> " + newStatus);
        return updatedFile;
    }

    /**
     * Updates file status by session ID
     * Helper method for UploadSessionService
     */
    @Transactional
    public File updateFileStatusBySession(UUID sessionId, UploadStatus newStatus) {
        log.info("Updating file status by session: " + sessionId + " to " + newStatus);

        File file = fileRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("File not found for session: " + sessionId));

        return updateFileStatus(file.getFileId(), newStatus);
    }

    // =================================================================
    // 3. FETCH FILE FUNCTIONS
    // =================================================================

    /**
     * Get file by ID
     */
    @Transactional(readOnly = true)
    public File getFile(UUID fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
    }

    /**
     * Get file by session ID
     */
    @Transactional(readOnly = true)
    public File getFileBySession(UUID sessionId) {
        return fileRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("File not found for session: " + sessionId));
    }


    // =================================================================
    // 4. DELETE FILE FUNCTION (CASCADE DELETE)
    // =================================================================

    /**
     * Deletes file and ALL associated chunks and replicas
     * DANGEROUS OPERATION - use with caution!
     */
    @Transactional
    public void deleteFile(UUID fileId) {
        log.warning("DELETING FILE AND ALL DATA: " + fileId);

        // ========== VALIDATIONS ==========

        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        // ========== CASCADE DELETE IN CORRECT ORDER ==========

        // 1. Delete all chunk replicas first (foreign key dependency)
        chunkRepository.findByFileIdOrderByChunkNumberAsc(fileId).forEach(chunk -> {
            List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunk.getChunkId());
            if (!replicas.isEmpty()) {
                chunkReplicaRepository.deleteAll(replicas);
                log.info("Deleted " + replicas.size() + " replicas for chunk: " + chunk.getChunkId());
            }
        });

        // 2. Delete all chunks for this file
        chunkRepository.deleteByFileId(fileId);
        log.info("Deleted all chunks for file: " + fileId);

        // 3. Finally delete the file record
        fileRepository.delete(file);
        log.warning("FILE DELETED: " + file.getFileName() + " (" + fileId + ")");
    }

    // =================================================================
    // 5. VALIDATION & UTILITY FUNCTIONS
    // =================================================================

    /**
     * Check if file upload is complete (all chunks received)
     */
    @Transactional(readOnly = true)
    public boolean isFileUploadComplete(UUID fileId) {
        File file = getFile(fileId);
        long registeredChunks = chunkRepository.countByFileId(fileId);

        boolean isComplete = registeredChunks == file.getTotalChunks();
        log.info("File upload completeness check: " + fileId +
                " - " + registeredChunks + "/" + file.getTotalChunks() +
                " chunks (" + (isComplete ? "COMPLETE" : "INCOMPLETE") + ")");

        return isComplete;
    }

    /**
     * Get file upload progress percentage
     */
    @Transactional(readOnly = true)
    public double getFileUploadProgress(UUID fileId) {
        File file = getFile(fileId);
        long registeredChunks = chunkRepository.countByFileId(fileId);

        if (file.getTotalChunks() == 0) return 0.0;

        double progress = (double) registeredChunks / file.getTotalChunks() * 100.0;
        return Math.round(progress * 100.0) / 100.0; // Round to 2 decimal places
    }

    /**
     * Validate if status transition is allowed
     */
    private boolean isValidStatusTransition(UploadStatus from, UploadStatus to) {
        if (from == null) return true; // Allow any initial status

        return switch (from) {
            case UPLOADING -> to == UploadStatus.COMPLETED || to == UploadStatus.FAILED;
            case COMPLETED -> to == UploadStatus.FAILED; // Allow marking completed files as failed (rare case)
            case FAILED -> to == UploadStatus.UPLOADING; // Allow retry
            default -> false;
        };
    }

    /**
     * Check if file exists
     */
    @Transactional(readOnly = true)
    public boolean fileExists(UUID fileId) {
        return fileRepository.existsById(fileId);
    }

    /**
     * Check if filename exists (for duplicate checking)
     */
    @Transactional(readOnly = true)
    public boolean fileNameExists(String fileName) {
        return fileRepository.existsByFileName(fileName);
    }

    /**
     * Get total file count by status
     */
    @Transactional(readOnly = true)
    public long getFileCountByStatus(UploadStatus status) {
        return fileRepository.countByUploadStatus(status);
    }
}