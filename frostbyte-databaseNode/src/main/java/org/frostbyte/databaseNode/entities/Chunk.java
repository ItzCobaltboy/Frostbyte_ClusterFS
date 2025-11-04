package org.frostbyte.databaseNode.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "chunks")
@Data
public class Chunk {

    @Id
    @Column(name = "chunk_id", columnDefinition = "uuid")
    private UUID chunkId;

    @Column(name = "file_id", columnDefinition = "uuid", nullable = false)
    private UUID fileId;

    @Column(name = "chunk_number", nullable = false)
    private int chunkNumber;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "crc32")
    private String crc32;

    @Column(name = "created_at", updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;
}