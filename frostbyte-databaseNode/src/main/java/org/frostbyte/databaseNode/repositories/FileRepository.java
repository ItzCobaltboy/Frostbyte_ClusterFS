package org.frostbyte.databaseNode.repositories;

import org.frostbyte.databaseNode.entities.File;
import org.frostbyte.databaseNode.models.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<File, UUID> {
    Optional<File> findBySessionId(UUID sessionId);

    long countByUploadStatus(UploadStatus status);

    boolean existsByFileName(String fileName);

    List<File> findByUploadStatus(UploadStatus status);

}