package org.frostbyte.masternode.models;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "frostbyte.masternode")
public class configModel {
    // Server host data
    private String host;
    private int port;
    private String nodeName;

    // File address
    private String masterAPIKey;
}
