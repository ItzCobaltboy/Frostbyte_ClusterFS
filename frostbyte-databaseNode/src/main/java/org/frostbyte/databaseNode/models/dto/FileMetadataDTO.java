package org.frostbyte.databaseNode.models.dto;

import lombok.Data;

@Data
public class FileMetadataDTO {
    private String fileName;
    private long fileSize;
    private int totalChunks;
    private String clientNodeId;
}