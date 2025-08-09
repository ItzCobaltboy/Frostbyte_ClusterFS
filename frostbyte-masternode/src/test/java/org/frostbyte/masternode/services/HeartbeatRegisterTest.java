package org.frostbyte.masternode.services;

import org.frostbyte.masternode.models.DataNode;
import org.frostbyte.masternode.models.DatabaseNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

public class HeartbeatRegisterTest {

    private heartbeatRegister register;

    @BeforeEach
    void setUp() {
        register = new heartbeatRegister();
    }

    @Test
    void testAddAndGetDataNode() {
        DataNode node = new DataNode();
        node.setHost("192.168.1.100:8080");
        node.setNodeName("TestDataNode1");

        register.addDataNode(node);

        Vector<DataNode> aliveNodes = register.getAliveDataNodes();
        assertEquals(1, aliveNodes.size());
        assertEquals("TestDataNode1", aliveNodes.getFirst().getNodeName());
        assertTrue(aliveNodes.getFirst().getAlive());
    }

    @Test
    void testUpdateDataNode() {
        DataNode node = new DataNode();
        node.setHost("192.168.1.100:8080");
        node.setNodeName("TestDataNode1");

        register.addDataNode(node);
        LocalDateTime originalTime = node.getLastUpdateTime();

        // Wait a bit to ensure time difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // ignore
        }

        boolean updated = register.updateDataNode("TestDataNode1");

        assertTrue(updated);
        assertTrue(node.getLastUpdateTime().isAfter(originalTime));
    }

    @Test
    void testUpdateNonExistentNode() {
        boolean updated = register.updateDataNode("NonExistent");
        assertFalse(updated);
    }

    @Test
    void testMarkDeadNodes() {
        DataNode node = new DataNode();
        node.setHost("192.168.1.100:8080");
        node.setNodeName("TestDataNode1");
        node.setLastUpdateTime(LocalDateTime.now().minusMinutes(10)); // Old timestamp
        node.setAlive(true);

        register.getDatanodes().add(node);

        // Run the scheduled task
        register.markDeadNodes();

        assertFalse(node.getAlive());

        // Alive nodes should return empty
        Vector<DataNode> aliveNodes = register.getAliveDataNodes();
        assertEquals(0, aliveNodes.size());
    }

    @Test
    void testMultipleNodeTypes() {
        // Add DataNode
        DataNode dataNode = new DataNode();
        dataNode.setHost("192.168.1.100:8080");
        dataNode.setNodeName("DataNode1");
        register.addDataNode(dataNode);

        // Add DatabaseNode
        DatabaseNode dbNode = new DatabaseNode();
        dbNode.setHost("192.168.1.200:8090");
        dbNode.setNodeName("DBNode1");
        register.addDatabaseNode(dbNode);

        assertEquals(1, register.getAliveDataNodes().size());
        assertEquals(1, register.getAliveDatabaseNodes().size());
    }
}