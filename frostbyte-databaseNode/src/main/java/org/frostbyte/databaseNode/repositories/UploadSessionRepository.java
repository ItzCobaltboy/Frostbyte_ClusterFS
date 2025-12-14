package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.UploadSession;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    List<UploadSession> findByStatus(UploadStatus status);

    List<UploadSession> findByClientNode(String clientNodeId);

    /**
     * Atomically increment chunksReceived counter for an upload session.
     * This prevents race conditions when multiple chunks are uploaded concurrently.
     *
     * @param sessionId The session ID to update
     * @param updatedAt Timestamp to set as updated_at
     * @return Number of rows updated (should be 1)
     */
    @Modifying
    @Query("UPDATE UploadSession s SET s.chunksReceived = s.chunksReceived + 1, s.updatedAt = :updatedAt WHERE s.sessionId = :sessionId")
    int incrementChunksReceived(@Param("sessionId") UUID sessionId, @Param("updatedAt") Timestamp updatedAt);
}