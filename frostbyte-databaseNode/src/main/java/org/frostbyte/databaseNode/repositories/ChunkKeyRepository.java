package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.KeyPair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChunkKeyRepository extends JpaRepository<KeyPair, UUID> {

}
