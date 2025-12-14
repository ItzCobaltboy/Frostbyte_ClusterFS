package org.frostbyte.databaseNode.services;

import lombok.Getter;
import lombok.Setter;
import org.frostbyte.databaseNode.entities.Chunk;
import org.frostbyte.databaseNode.entities.ChunkReplica;
import org.frostbyte.databaseNode.entities.File;
import org.frostbyte.databaseNode.entities.UploadSession;
import org.frostbyte.databaseNode.models.ReplicaStatus;
import org.frostbyte.databaseNode.models.dto.*;
import org.frostbyte.databaseNode.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.logging.Logger;

@Service
public class ChunkMetadataService {

    private static final Logger log = Logger.getLogger(ChunkMetadataService.class.getName());

    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final FileRepository fileRepository;
    private final ChunkKeyRepository chunkKeyRepository;
    private final UploadSessionRepository uploadSessionRepository;

    @Autowired
    public ChunkMetadataService(ChunkRepository chunkRepository,
                                ChunkReplicaRepository chunkReplicaRepository,
                                FileRepository fileRepository,
                                ChunkKeyRepository chunkKeyRepository,
                                UploadSessionRepository uploadSessionRepository) {
        this.chunkRepository = chunkRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.fileRepository = fileRepository;
        this.chunkKeyRepository = chunkKeyRepository;
        this.uploadSessionRepository = uploadSessionRepository;
    }

    // =================================================================
    // 1. REGISTER CHUNK FUNCTION
    // =================================================================

    /**
     * Registers chunk metadata and updates upload session progress
     * Validates: chunk UUID exists in KeyPair, file UUID exists in files table
     */
    @Transactional
    public Chunk registerChunk(ChunkMetadataDTO chunkData) {
        log.info("Registering chunk: " + chunkData.getChunkId() + " for file: " + chunkData.getFileId());

        // ========== VALIDATIONS ==========

        // 1. Validate chunk UUID exists in KeyPair table (key was generated)
        if (!chunkKeyRepository.existsById(chunkData.getChunkId())) {
            throw new IllegalArgumentException("Chunk key not found in KeyPair table: " + chunkData.getChunkId());
        }

        // 2. Validate file UUID exists in files table
        File file = fileRepository.findById(chunkData.getFileId())
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + chunkData.getFileId()));

        // 3. Check for duplicate chunk numbers for the same file
        List<Chunk> existingChunks = chunkRepository.findByFileIdOrderByChunkNumberAsc(chunkData.getFileId());
        boolean chunkNumberExists = existingChunks.stream()
                .anyMatch(chunk -> chunk.getChunkNumber() == chunkData.getChunkNumber());

        if (chunkNumberExists) {
            throw new IllegalArgumentException("Chunk number " + chunkData.getChunkNumber() +
                    " already exists for file: " + chunkData.getFileId());
        }

        // 4. Validate chunk number is within expected range
        if (chunkData.getChunkNumber() < 0 || chunkData.getChunkNumber() >= file.getTotalChunks()) {
            throw new IllegalArgumentException("Invalid chunk number " + chunkData.getChunkNumber() +
                    ". Expected range: 0 to " + (file.getTotalChunks() - 1));
        }

        // ========== CREATE CHUNK ENTRY ==========

        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkData.getChunkId());
        chunk.setFileId(chunkData.getFileId());
        chunk.setChunkNumber(chunkData.getChunkNumber());
        chunk.setChunkSize(chunkData.getChunkSize());
        chunk.setCrc32(chunkData.getCrc32());

        Chunk savedChunk = chunkRepository.save(chunk);
        log.info("Chunk saved: " + savedChunk.getChunkId());

        // ========== UPDATE UPLOAD SESSION PROGRESS ==========

        // Atomically increment chunks received counter (prevents race condition)
        Timestamp now = Timestamp.from(Instant.now());
        int rowsUpdated = uploadSessionRepository.incrementChunksReceived(file.getSessionId(), now);

        if (rowsUpdated == 0) {
            throw new IllegalArgumentException("Upload session not found: " + file.getSessionId());
        }

        // Fetch updated session to log progress
        UploadSession session = uploadSessionRepository.findById(file.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + file.getSessionId()));

        log.info("Upload session updated. Chunks received: " + session.getChunksReceived() +
                "/" + file.getTotalChunks());

        return savedChunk;
    }

    // =================================================================
    // 2. REGISTER REPLICA FUNCTION (ATOMIC)
    // =================================================================

    /**
     * Registers a single chunk replica location
     * Validates: chunk entry exists in chunks table
     */
    @Transactional
    public ChunkReplica registerReplica(UUID chunkId, ReplicaInfoDTO replicaInfo) {
        log.info("Registering replica for chunk: " + chunkId + " on datanode: " + replicaInfo.getDatanodeId());

        // ========== VALIDATIONS ==========

        // 1. Validate chunk exists in chunks table
        if (!chunkRepository.existsById(chunkId)) {
            throw new IllegalArgumentException("Chunk not found in chunks table: " + chunkId);
        }

        // 2. Check for duplicate replica (same chunk on same datanode)
        boolean replicaExists = chunkReplicaRepository.existsByChunkIdAndDatanodeId(chunkId, replicaInfo.getDatanodeId());
        if (replicaExists) {
            throw new IllegalArgumentException("Replica already exists for chunk " + chunkId +
                    " on datanode: " + replicaInfo.getDatanodeId());
        }

        // ========== CREATE REPLICA ENTRY ==========

        ChunkReplica replica = new ChunkReplica();
        replica.setChunkId(chunkId);
        replica.setDatanodeId(replicaInfo.getDatanodeId());
        replica.setStatus(replicaInfo.getStatus() != null ? replicaInfo.getStatus() : ReplicaStatus.AVAILABLE);

        ChunkReplica savedReplica = chunkReplicaRepository.save(replica);
        log.info("Replica registered: " + savedReplica.getId() + " for chunk: " + chunkId);

        return savedReplica;
    }

    // =================================================================
    // 3. FETCH CHUNKS FUNCTION (FILE RECONSTRUCTION MAP)
    // =================================================================

    /**
     * Fetches all chunk replica locations by file UUID for download reconstruction
     * Returns complete FileMapDTO with ordered chunks and their replica locations
     */
    @Transactional(readOnly = true)
    public FileMapDTO getFileChunks(UUID fileId) {
        log.info("Fetching file chunks map for fileId: " + fileId);

        // ========== VALIDATIONS ==========

        // 1. Validate file exists
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        // ========== FETCH CHUNKS WITH REPLICAS ==========

        // 2. Get all chunks for this file, ordered by chunk number
        List<Chunk> chunks = chunkRepository.findByFileIdOrderByChunkNumberAsc(fileId);

        if (chunks.isEmpty()) {
            throw new IllegalStateException("No chunks found for file: " + fileId);
        }

        // 3. For each chunk, get its available replica locations
        List<ChunkMapDTO> chunkMaps = chunks.stream().map(chunk -> {
            ChunkMapDTO chunkMapDTO = new ChunkMapDTO();
            chunkMapDTO.setChunkId(chunk.getChunkId());
            chunkMapDTO.setChunkNumber(chunk.getChunkNumber());

            // Get available replicas for this chunk
            List<ReplicaLocationDTO> replicaLocations = chunkReplicaRepository.findByChunkId(chunk.getChunkId())
                    .stream()
                    .filter(replica -> replica.getStatus() == ReplicaStatus.AVAILABLE) // Only healthy replicas
                    .map(replica -> {
                        ReplicaLocationDTO locationDTO = new ReplicaLocationDTO();
                        locationDTO.setDatanodeId(replica.getDatanodeId());
                        // Note: 'host' field will be populated by ClientNode from MasterNode registry
                        return locationDTO;
                    })
                    .collect(Collectors.toList());

            if (replicaLocations.isEmpty()) {
                log.warning("No available replicas found for chunk: " + chunk.getChunkId());
            }

            chunkMapDTO.setReplicas(replicaLocations);
            return chunkMapDTO;
        }).collect(Collectors.toList());

        // ========== BUILD FILE MAP RESPONSE ==========

        FileMapDTO fileMap = new FileMapDTO();
        fileMap.setFileId(file.getFileId());
        fileMap.setFileName(file.getFileName());
        fileMap.setTotalChunks(file.getTotalChunks());
        fileMap.setChunks(chunkMaps);

        log.info("File map built successfully. File: " + file.getFileName() +
                ", Chunks: " + chunkMaps.size() + "/" + file.getTotalChunks());

        return fileMap;
    }

    // =================================================================
    // 4. DELETE CHUNK FUNCTION (CASCADE DELETE)
    // =================================================================

    /**
     * Deletes chunk and all its replicas using chunk UUID
     * Handles relations carefully with cascade delete
     */
    @Transactional
    public void deleteChunk(UUID chunkId) {
        log.info("Deleting chunk and all replicas: " + chunkId);

        // ========== VALIDATIONS ==========

        // 1. Validate chunk exists
        if (!chunkRepository.existsById(chunkId)) {
            throw new IllegalArgumentException("Chunk not found: " + chunkId);
        }

        // ========== CASCADE DELETE ==========

        // 2. First delete all replicas for this chunk
        List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunkId);
        if (!replicas.isEmpty()) {
            chunkReplicaRepository.deleteAll(replicas);
            log.info("Deleted " + replicas.size() + " replicas for chunk: " + chunkId);
        }

        // 3. Then delete the chunk itself
        chunkRepository.deleteById(chunkId);
        log.info("Chunk deleted: " + chunkId);

        // Note: KeyPair entry remains for potential cleanup later
        // This allows for audit trails and debugging
    }

    // =================================================================
    // 5. UTILITY METHODS
    // =================================================================

    /**
     * Get chunk count for a specific file (useful for validation)
     */
    @Transactional(readOnly = true)
    public long getChunkCountForFile(UUID fileId) {
        return chunkRepository.countByFileId(fileId);
    }

    /**
     * Check if all chunks are registered for a file
     */
    @Transactional(readOnly = true)
    public boolean areAllChunksRegistered(UUID fileId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        long registeredChunks = chunkRepository.countByFileId(fileId);
        return registeredChunks == file.getTotalChunks();
    }

    /**
     * Get replicas for a specific DataNode (useful for DataNode failure handling)
     */
    @Transactional(readOnly = true)
    public List<ChunkReplica> getReplicasOnDataNode(String datanodeId) {
        return chunkReplicaRepository.findByDatanodeId(datanodeId);
    }

    // =================================================================
    // 6. MISSING METHODS FOR REPLICA CONTROLLER
    // =================================================================

    /**
     * Get all replicas for a specific chunk
     * Used by ReplicaController for chunk replica queries
     */
    @Transactional(readOnly = true)
    public List<ChunkReplica> getReplicasByChunkId(UUID chunkId) {
        log.info("Getting replicas for chunk: " + chunkId);

        if (!chunkRepository.existsById(chunkId)) {
            throw new IllegalArgumentException("Chunk not found: " + chunkId);
        }

        List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunkId);
        log.info("Found " + replicas.size() + " replicas for chunk: " + chunkId);

        return replicas;
    }

    /**
     * Delete a specific replica (chunk from specific DataNode)
     * Used for replica cleanup operations
     */
    @Transactional
    public void deleteReplica(UUID chunkId, String datanodeId) {
        log.info("Deleting replica: chunk " + chunkId + " from datanode " + datanodeId);

        // ========== VALIDATIONS ==========
        ChunkReplica replica = chunkReplicaRepository.findByChunkIdAndDatanodeId(chunkId, datanodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Replica not found for chunk " + chunkId + " on datanode " + datanodeId));

        // ========== DELETE REPLICA ==========
        chunkReplicaRepository.delete(replica);
        log.info("Replica deleted: " + replica.getId());

        // ========== WARNING FOR LOW REPLICATION ==========
        long remainingReplicas = chunkReplicaRepository.countByChunkId(chunkId);
        if (remainingReplicas < 2) { // Assuming minimum 2 replicas
            log.warning("LOW REPLICATION WARNING: Chunk " + chunkId + " now has only " + remainingReplicas + " replicas");
        }
    }

    /**
     * Delete all replicas on a specific DataNode
     * Critical for DataNode decommissioning and failure recovery
     */
    @Transactional
    public void deleteAllReplicasOnDataNode(String datanodeId) {
        log.warning("DELETING ALL REPLICAS on datanode: " + datanodeId);

        // ========== GET AFFECTED CHUNKS FIRST ==========
        List<ChunkReplica> replicas = chunkReplicaRepository.findByDatanodeId(datanodeId);

        if (replicas.isEmpty()) {
            log.info("No replicas found on datanode: " + datanodeId);
            return;
        }

        // ========== TRACK CHUNKS THAT WILL BECOME UNDER-REPLICATED ==========
        List<UUID> affectedChunks = replicas.stream()
                .map(ChunkReplica::getChunkId)
                .distinct()
                .toList();

        // ========== DELETE ALL REPLICAS ==========
        chunkReplicaRepository.deleteByDatanodeId(datanodeId);
        log.warning("Deleted " + replicas.size() + " replicas from datanode: " + datanodeId);

        // ========== CHECK FOR UNDER-REPLICATED CHUNKS ==========
        List<UUID> underReplicatedChunks = affectedChunks.stream()
                .filter(chunkId -> chunkReplicaRepository.countByChunkId(chunkId) < 2) // Assuming min 2 replicas
                .toList();

        if (!underReplicatedChunks.isEmpty()) {
            log.severe("CRITICAL: " + underReplicatedChunks.size() + " chunks are now under-replicated after removing datanode " + datanodeId);
            log.severe("Under-replicated chunks: " + underReplicatedChunks);
        }
    }

    /**
     * Get replica count distribution across DataNodes
     * Used for load balancing and monitoring
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getReplicaCountPerDataNode() {
        log.info("Getting replica distribution across DataNodes");

        List<ChunkReplica> allReplicas = chunkReplicaRepository.findAll();

        Map<String, Long> distribution = allReplicas.stream()
                .collect(Collectors.groupingBy(
                        ChunkReplica::getDatanodeId,
                        Collectors.counting()
                ));

        log.info("Replica distribution: " + distribution);
        return distribution;
    }

    /**
     * Find chunks with insufficient replication
     * Critical for system health monitoring
     */
    @Transactional(readOnly = true)
    public List<UUID> getUnderReplicatedChunks(int minReplicas) {
        log.info("Finding chunks with less than " + minReplicas + " replicas");

        // Get all chunks and their replica counts
        List<Chunk> allChunks = chunkRepository.findAll();

        List<UUID> underReplicated = allChunks.stream()
                .map(Chunk::getChunkId)
                .filter(chunkId -> {
                    long replicaCount = chunkReplicaRepository.countByChunkId(chunkId);
                    return replicaCount < minReplicas;
                })
                .collect(Collectors.toList());

        if (!underReplicated.isEmpty()) {
            log.warning("Found " + underReplicated.size() + " under-replicated chunks");
        }

        return underReplicated;
    }

    /**
     * Find chunks with excessive replication (over target)
     * Used for storage optimization
     */
    @Transactional(readOnly = true)
    public List<UUID> getOverReplicatedChunks(int maxReplicas) {
        log.info("Finding chunks with more than " + maxReplicas + " replicas");

        List<Chunk> allChunks = chunkRepository.findAll();

        List<UUID> overReplicated = allChunks.stream()
                .map(Chunk::getChunkId)
                .filter(chunkId -> {
                    long replicaCount = chunkReplicaRepository.countByChunkId(chunkId);
                    return replicaCount > maxReplicas;
                })
                .collect(Collectors.toList());

        log.info("Found " + overReplicated.size() + " over-replicated chunks");
        return overReplicated;
    }

    /**
     * Get total replica count across entire system
     * Used for storage statistics
     */
    @Transactional(readOnly = true)
    public long getTotalReplicaCount() {
        long totalReplicas = chunkReplicaRepository.count();
        log.info("Total replicas in system: " + totalReplicas);
        return totalReplicas;
    }

    /**
     * Get total unique chunk count
     * Used for system statistics
     */
    @Transactional(readOnly = true)
    public long getTotalChunkCount() {
        long totalChunks = chunkRepository.count();
        log.info("Total chunks in system: " + totalChunks);
        return totalChunks;
    }

    /**
     * Get average replication factor across all chunks
     * Used for monitoring replication health
     */
    @Transactional(readOnly = true)
    public double getAverageReplicationFactor() {
        long totalChunks = getTotalChunkCount();
        long totalReplicas = getTotalReplicaCount();

        if (totalChunks == 0) return 0.0;

        double averageReplication = (double) totalReplicas / totalChunks;
        log.info("Average replication factor: " + averageReplication);
        return Math.round(averageReplication * 100.0) / 100.0; // Round to 2 decimal places
    }

    // =================================================================
    // 7. SYSTEM HEALTH & MONITORING METHODS
    // =================================================================

    /**
     * Get comprehensive system health statistics
     * Used by monitoring and admin endpoints
     */
    @Transactional(readOnly = true)
    public SystemHealthStats getSystemHealthStats() {
        log.info("Generating system health statistics");

        SystemHealthStats stats = new SystemHealthStats();

        // Basic counts
        stats.setTotalChunks(getTotalChunkCount());
        stats.setTotalReplicas(getTotalReplicaCount());
        stats.setAverageReplicationFactor(getAverageReplicationFactor());

        // Replication health
        stats.setUnderReplicatedChunks(getUnderReplicatedChunks(2).size()); // Assuming min 2 replicas
        stats.setOverReplicatedChunks(getOverReplicatedChunks(3).size()); // Assuming max 3 replicas

        // DataNode distribution
        stats.setReplicaDistribution(getReplicaCountPerDataNode());

        // Health status
        if (stats.getUnderReplicatedChunks() > 0) {
            stats.setHealthStatus("DEGRADED");
        } else if (stats.getTotalChunks() == 0) {
            stats.setHealthStatus("EMPTY");
        } else {
            stats.setHealthStatus("HEALTHY");
        }

        log.info("System health: " + stats.getHealthStatus());
        return stats;
    }

    /**
     * Find orphaned chunks (chunks without any replicas)
     * Critical for data integrity monitoring
     */
    @Transactional(readOnly = true)
    public List<UUID> getOrphanedChunks() {
        log.info("Finding orphaned chunks (chunks without replicas)");

        List<Chunk> allChunks = chunkRepository.findAll();

        List<UUID> orphaned = allChunks.stream()
                .filter(chunk -> {
                    long replicaCount = chunkReplicaRepository.countByChunkId(chunk.getChunkId());
                    return replicaCount == 0;
                })
                .map(Chunk::getChunkId)
                .collect(Collectors.toList());

        if (!orphaned.isEmpty()) {
            log.severe("CRITICAL: Found " + orphaned.size() + " orphaned chunks (no replicas)");
            log.severe("Orphaned chunks: " + orphaned);
        }

        return orphaned;
    }

    /**
     * Find chunks that exist only on a single DataNode (single point of failure)
     * Critical for resilience monitoring
     */
    @Transactional(readOnly = true)
    public List<UUID> getSingleReplicaChunks() {
        log.info("Finding chunks with only one replica (single point of failure)");

        List<Chunk> allChunks = chunkRepository.findAll();

        List<UUID> singleReplica = allChunks.stream()
                .map(Chunk::getChunkId)
                .filter(chunkId -> {
                    long replicaCount = chunkReplicaRepository.countByChunkId(chunkId);
                    return replicaCount == 1;
                })
                .collect(Collectors.toList());

        if (!singleReplica.isEmpty()) {
            log.warning("Found " + singleReplica.size() + " chunks with only one replica");
        }

        return singleReplica;
    }

    public void updateReplicaStatus(UUID chunkId, String datanodeId, ReplicaStatus newStatus) {
    }

    // =================================================================
    // 8. SYSTEM HEALTH STATS DTO
    // =================================================================

    /**
     * DTO for system health statistics
     */
    @Setter
    @Getter
    public static class SystemHealthStats {
        // Getters and setters
        private long totalChunks;
        private long totalReplicas;
        private double averageReplicationFactor;
        private long underReplicatedChunks;
        private long overReplicatedChunks;
        private Map<String, Long> replicaDistribution;
        private String healthStatus;

    }
}