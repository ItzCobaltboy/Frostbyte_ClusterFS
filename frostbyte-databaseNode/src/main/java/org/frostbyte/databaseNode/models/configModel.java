package org.frostbyte.databaseNode.models;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "frostbyte.databasenode")
public class configModel {
    private DatabaseDetails database;

    private String host;
    private int port;
    private String nodeName;

    // Masternode list
    private String[] masterNodes;

    // File address
    private String masterAPIKey;

    @Data
    public static class DatabaseDetails {
        private String host;
        private int port;
        private String name;
        private String username;
        private String password;
    }


}