package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.ChunkReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChunkReplicaRepository extends JpaRepository<ChunkReplica, Long> {
}