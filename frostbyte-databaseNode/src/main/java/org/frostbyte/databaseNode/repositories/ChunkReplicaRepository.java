package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.ChunkReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChunkReplicaRepository extends JpaRepository<ChunkReplica, Long> {
    // Methods for finding replicas
    List<ChunkReplica> findByChunkId(UUID chunkId);
    List<ChunkReplica> findByDatanodeId(String datanodeId);
    Optional<ChunkReplica> findByChunkIdAndDatanodeId(UUID chunkId, String datanodeId);

    boolean existsByChunkIdAndDatanodeId(UUID chunkId, String datanodeId);

    long countByChunkId(UUID chunkId);

    void deleteByDatanodeId(String datanodeId);
}