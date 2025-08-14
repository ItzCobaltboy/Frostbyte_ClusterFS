package org.frostbyte.masternode.services;

import lombok.Getter;
import org.frostbyte.masternode.models.BalancerNode;
import org.frostbyte.masternode.models.DataNode;
import org.frostbyte.masternode.models.DatabaseNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Vector;


@Getter
@Component
public class heartbeatRegister {

    // Getters (if needed elsewhere)
    @SuppressWarnings("FieldMayBeFinal")
    private Vector<DataNode> datanodes = new Vector<>();
    @SuppressWarnings("FieldMayBeFinal")
    private Vector<BalancerNode> balancers = new Vector<>();
    private final Vector<DatabaseNode> databasenodes = new Vector<>(); // Add new list


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

    public void addDatabaseNode(DatabaseNode databaseNode) {
        databaseNode.setLastUpdateTime(LocalDateTime.now());
        databaseNode.setRegisterTime(LocalDateTime.now());
        databaseNode.setAlive(true);
        databasenodes.add(databaseNode);
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

    public boolean updateDatabaseNode(String nodeName) {
        for (DatabaseNode node : databasenodes) {
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

        for (DatabaseNode node : databasenodes) {
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

    public Vector<DatabaseNode> getAliveDatabaseNodes() {
        Vector<DatabaseNode> aliveDatabaseNodes = new Vector<>();
        for (DatabaseNode node : databasenodes) {
            if (node.getAlive()) {
                aliveDatabaseNodes.add(node);
            }
        }
        return aliveDatabaseNodes;
    }
}