package org.frostbyte.masternode.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class DataNode extends Node {
    String nodeType = "DataNode";
}
