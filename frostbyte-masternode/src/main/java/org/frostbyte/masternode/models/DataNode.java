package org.frostbyte.masternode.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class DataNode extends Node {
    String nodeType = "DataNode";

    // Capacity metrics
    private double currentUsedGB = 0.0;
    private double totalCapacityGB = 0.0;
    private double fillPercent = 0.0;
}
