package org.frostbyte.masternode.models;


import lombok.Data;

@Data
public class configModel {
    // Server host data
    private String host;
    private int port;
    private String nodeName;

    // File address
    private String masterAPIKey;
}
