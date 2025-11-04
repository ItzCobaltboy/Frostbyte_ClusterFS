package org.frostbyte.clientnode.models;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "frostbyte.clientnode")
public class configModel {
    // Server params
    private String host;
    private int port;
    private String nodeName;

    // Masternode list
    private String[] masterNodes;

    private String masterAPIKey;

    // Client node params
    private int maxThreadPool;
    private int chunkSizeMB;

    // Storage params
    private String snowflakeStorageFolder;

}