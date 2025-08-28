package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    List<Chunk> findByFileIdOrderByChunkNumberAsc(UUID fileId);

    long countByFileId(UUID fileId);

    void deleteByFileId(UUID fileId);
}