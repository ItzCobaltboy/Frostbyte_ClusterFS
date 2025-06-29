package org.frostbyte.datanode.models;


import lombok.Data;

@Data
public class configModel {
    // Server host data
    private String host;
    private int port;
    private String nodeName;

    // Masternode list
    private String[] masterNodes;

    // File address
    private String masterAPIKey;
    private String chunkFolder;
    private int size;
}
