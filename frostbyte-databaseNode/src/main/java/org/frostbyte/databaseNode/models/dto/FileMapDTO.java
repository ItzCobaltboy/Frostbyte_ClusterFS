package org.frostbyte.databaseNode.models.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class FileMapDTO {
    private UUID fileId;
    private String fileName;
    private int totalChunks;
    private List<ChunkMapDTO> chunks;
}