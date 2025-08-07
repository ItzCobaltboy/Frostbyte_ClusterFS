package org.frostbyte.masternode.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class DatabaseNode extends Node {
    String nodeType = "DatabaseNode";
}