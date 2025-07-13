package org.frostbyte.masternode.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class BalancerNode extends Node {
    String nodeType = "Balancer";
}
