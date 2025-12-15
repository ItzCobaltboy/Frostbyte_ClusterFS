package org.frostbyte.balancer.models;

import lombok.Data;

@Data
public class DataNodeInfo {
    private String host;
    private String nodeName;

    // Capacity metrics
    private double currentUsedGB = 0.0;
    private double totalCapacityGB = 0.0;
    private double fillPercent = 0.0;

    // For load balancing - tracks projected load during allocation
    private double projectedFillPercent = 0.0;
}
