package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.UploadSession;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    List<UploadSession> findByStatus(UploadStatus status);

    List<UploadSession> findByClientNode(String clientNodeId);
}