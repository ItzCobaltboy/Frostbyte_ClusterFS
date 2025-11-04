package org.frostbyte.databaseNode.entities;

import jakarta.persistence.*;
import lombok.Data;
import org.frostbyte.databaseNode.models.ReplicaStatus;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "chunk_replicas")
@Data
public class ChunkReplica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id", columnDefinition = "uuid", nullable = false)
    private UUID chunkId;

    @Column(name = "datanode_id", nullable = false)
    private String datanodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ReplicaStatus status;

    @Column(name = "created_at", updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;
}