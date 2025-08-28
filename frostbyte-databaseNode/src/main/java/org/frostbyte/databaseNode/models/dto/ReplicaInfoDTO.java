package org.frostbyte.databaseNode.models.dto;

import lombok.Data;
import org.frostbyte.databaseNode.models.ReplicaStatus;

@Data
public class ReplicaInfoDTO {
    private String datanodeId;
    private ReplicaStatus status;
}