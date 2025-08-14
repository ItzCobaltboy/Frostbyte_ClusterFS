package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.KeyPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ChunkKeyRepository extends JpaRepository<KeyPair, UUID> {

}
