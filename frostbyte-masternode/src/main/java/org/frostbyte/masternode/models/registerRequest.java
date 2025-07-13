package org.frostbyte.masternode.models;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class registerRequest {

    @NotNull
    private String ip;

    @NotNull
    private String nodeName;

    @NotNull
    private nodeType nodeType;
}

