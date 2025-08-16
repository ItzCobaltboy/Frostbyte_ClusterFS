package org.frostbyte.clientnode.models;

import lombok.Data;

@Data
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
    private int chunkSize;

}