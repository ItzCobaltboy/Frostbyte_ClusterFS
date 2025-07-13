package org.frostbyte.masternode.models;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Node {

    private String host;
    private String nodeName;
    private LocalDateTime registerTime;
    private LocalDateTime lastUpdateTime;
    private Boolean alive = true;
}

