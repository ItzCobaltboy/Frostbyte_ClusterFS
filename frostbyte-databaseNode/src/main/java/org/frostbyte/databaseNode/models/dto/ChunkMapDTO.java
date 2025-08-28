package org.frostbyte.databaseNode.models.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class ChunkMapDTO {
    private UUID chunkId;
    private int chunkNumber;
    private List<ReplicaLocationDTO> replicas;
}