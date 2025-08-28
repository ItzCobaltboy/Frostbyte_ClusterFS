package org.frostbyte.databaseNode.models.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class ChunkMetadataDTO {
    private UUID chunkId;
    private UUID fileId;
    private int chunkNumber;
    private int chunkSize;
    private String crc32;
}