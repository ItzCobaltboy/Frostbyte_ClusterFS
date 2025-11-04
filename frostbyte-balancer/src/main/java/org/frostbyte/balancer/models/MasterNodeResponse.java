package org.frostbyte.balancer.models;

import lombok.Data;

@Data
public class MasterNodeResponse {
    private Object aliveNodes; // Can be "NULL" string or List<DataNodeInfo>
    private String Timestamp;
}

