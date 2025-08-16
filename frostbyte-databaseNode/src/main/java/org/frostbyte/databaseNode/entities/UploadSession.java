package org.frostbyte.databaseNode.entities;

import jakarta.persistence.*;
import lombok.Data;
import org.frostbyte.databaseNode.models.UploadStatus;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "upload_sessions")
@Data
public class UploadSession {

    @Id
    @Column(name = "session_id", columnDefinition = "uuid")
    private UUID sessionId;

    @Column(name = "client_node")
    private String clientNode;

    @Column(name = "balancer_node")
    private String balancerNode;

    @Column(name = "started_at", nullable = false, updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp startedAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UploadStatus status;

    @Column(name = "chunks_received")
    private int chunksReceived;
}