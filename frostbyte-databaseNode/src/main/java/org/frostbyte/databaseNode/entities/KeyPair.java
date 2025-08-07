package org.frostbyte.databaseNode.entities;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "chunk_keys")
@Data
public class KeyPair {

    @Id
    @Column(name = "chunk_id", columnDefinition = "uuid")
    private UUID chunkId;

    @Column(name = "key", nullable = false)
    private String key;  // We'll store AES key as base64 string
}
