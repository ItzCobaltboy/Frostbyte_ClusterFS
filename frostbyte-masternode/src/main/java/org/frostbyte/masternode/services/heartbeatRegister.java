package org.frostbyte.masternode.services;

import lombok.Getter;
import org.frostbyte.masternode.models.BalancerNode;
import org.frostbyte.masternode.models.DataNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Vector;


@Getter
@Component
public class heartbeatRegister {

    // Getters (if needed elsewhere)
    private Vector<DataNode> datanodes = new Vector<>();
    private Vector<BalancerNode> balancers = new Vector<>();

    public void addDataNode(DataNode datanode) {
        datanode.setLastUpdateTime(LocalDateTime.now());
        datanode.setRegisterTime(LocalDateTime.now());
        datanode.setAlive(true);
        datanodes.add(datanode);
    }

    public void addBalancerNode(BalancerNode balancerNode) {
        balancerNode.setLastUpdateTime(LocalDateTime.now());
        balancerNode.setRegisterTime(LocalDateTime.now());
        balancerNode.setAlive(true);
        balancers.add(balancerNode);
    }

    public boolean updateDataNode(String nodeName) {
        for (DataNode node : datanodes) {
            if (node.getNodeName().equals(nodeName)) {
                node.setLastUpdateTime(LocalDateTime.now());
                node.setAlive(true);
                return true;
            }
        }
        return false;
    }

    public boolean updateBalancerNode(String nodeName) {
        for (BalancerNode node : balancers) {
            if (node.getNodeName().equals(nodeName)) {
                node.setLastUpdateTime(LocalDateTime.now());
                node.setAlive(true);
                return true;
            }
        }
        return false;
    }

    // Called periodically to remove dead nodes
    @Scheduled(fixedRate = 120_000)  // Remove dead nodes every 2 min
    public void markDeadNodes() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        for (DataNode node : datanodes) {
            if (node.getLastUpdateTime().isBefore(cutoff)) {
                node.setAlive(false);
            }
        }

        for (BalancerNode node : balancers) {
            if (node.getLastUpdateTime().isBefore(cutoff)) {
                node.setAlive(false);
            }
        }
    }

    public Vector<BalancerNode> getAliveBalancers() {
        Vector<BalancerNode> aliveBalancers = new Vector<>();
        for (BalancerNode balancerNode : balancers) {
            if (balancerNode.getAlive() == true) {
                aliveBalancers.add(balancerNode);
            }
        }
        return aliveBalancers;
    }

    public Vector<DataNode> getAliveDataNodes() {
        Vector<DataNode> aliveDataNodes = new Vector<>();
        for (DataNode node : datanodes) {
            if (node.getAlive() == true) {
                aliveDataNodes.add(node);
            }
        }
        return aliveDataNodes;
    }
}