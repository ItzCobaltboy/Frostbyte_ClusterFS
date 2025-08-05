package org.frostbyte.databaseNode.models;

import lombok.Data;

@Data
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