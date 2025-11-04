package org.frostbyte.balancer.models;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "frostbyte.balancer")
public class configModel {
    // Server params
    private String host;
    private int port;
    private String nodeName;

    // Masternode list
    private String[] masterNodes;

    // Security
    private String masterAPIKey;

    // Replication configuration
    private int replicaCount = 3; // Default 3 replicas per chunk

}