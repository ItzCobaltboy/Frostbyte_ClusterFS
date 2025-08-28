package org.frostbyte.databaseNode.models.dto;

import lombok.Data;

@Data
public class ReplicaLocationDTO {
    private String datanodeId;
    private String host; // The actual network address
}