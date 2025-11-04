package org.frostbyte.databaseNode.entities;

import jakarta.persistence.*;
import lombok.Data;
import org.frostbyte.databaseNode.models.UploadStatus;

import java.sql.Timestamp;
import java.util.UUID;


@Entity
@Table(name = "files")
@Data
public class File {
    @Id
    @Column(name = "file_id", columnDefinition = "uuid")
    private UUID fileId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size")
    private long fileSize;

    @Column(name = "total_chunks")
    private int totalChunks;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status")
    private UploadStatus uploadStatus;

    @Column(name = "session_id", columnDefinition = "uuid")
    private UUID sessionId;  // Just the UUID!

    @Column(name = "created_at", updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;
}