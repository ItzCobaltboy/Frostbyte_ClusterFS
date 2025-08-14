package org.frostbyte.masternode.controllers;


import jakarta.validation.Valid;
import org.frostbyte.masternode.models.*;
import org.frostbyte.masternode.services.heartbeatRegister;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Logger;

@RestController
public class Masternode_controller {

    private final heartbeatRegister hr;
    private final configModel config;

    private static final Logger log = Logger.getLogger(Masternode_controller.class.getName());
    private static final String API_HEADER = "X-API-Key";

    @Autowired
    public Masternode_controller(heartbeatRegister hr, configModel config) {
        this.hr = hr;
        this.config = config;
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }

    @PostMapping("/datanode/register")
    public ResponseEntity<?> registerDN(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);


        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.DataNode) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = DataNode", "success", "false"));
        }

        DataNode dn = new DataNode();
        dn.setHost(req.getIp());
        dn.setNodeName(req.getNodeName());
        dn.setRegisterTime(LocalDateTime.now());
        dn.setLastUpdateTime(LocalDateTime.now());

        hr.addDataNode(dn);
        log.info("New data node added: " + dn);
        log.fine("New node IP: " + dn.getHost());
        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }

    @PostMapping("/balancer/register")
    public ResponseEntity<?> registerBN(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);

        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.Balancer) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = BalancerNode", "success", "false"));
        }

        BalancerNode bn = new BalancerNode();
        bn.setHost(req.getIp());
        bn.setNodeName(req.getNodeName());
        bn.setRegisterTime(LocalDateTime.now());
        bn.setLastUpdateTime(LocalDateTime.now());

        hr.addBalancerNode(bn);

        log.info("New balancer node added: " + bn);
        log.fine("New balancer IP: " + bn.getHost());


        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }

    @PostMapping("/database/register")
    public ResponseEntity<?> registerDB(@RequestHeader(API_HEADER) String apiKey,
                                        @RequestBody @Valid registerRequest req) {
        log.info("Incoming register: " + req);

        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        if (req.getNodeType() != nodeType.DatabaseNode) {
            log.warning("Invalid node type");
            return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeType = DatabaseNode", "success", "false"));
        }

        DatabaseNode dbNode = new DatabaseNode();
        dbNode.setHost(req.getIp());
        dbNode.setNodeName(req.getNodeName());
        dbNode.setRegisterTime(LocalDateTime.now());
        dbNode.setLastUpdateTime(LocalDateTime.now());

        hr.addDatabaseNode(dbNode);
        log.info("New database node added: " + dbNode);
        return ResponseEntity.ok(Map.of("nodeType", req.getNodeType(), "success", "true"));
    }


    @PostMapping("/datanode/heartbeat")
    public ResponseEntity<?> dataNodeHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                               @RequestParam("nodeName") String nodeName) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        boolean updated = hr.updateDataNode(nodeName);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeName = DataNode", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }

    @PostMapping("/balancer/heartbeat")
    public ResponseEntity<?> balancerHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                               @RequestParam("nodeName") String nodeName) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        boolean updated = hr.updateBalancerNode(nodeName);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Expected nodeName = BalancerNode", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }

    @PostMapping("/database/heartbeat")
    public ResponseEntity<?> databaseNodeHeartbeat(@RequestHeader(API_HEADER) String apiKey,
                                                   @RequestParam("nodeName") String nodeName) {
        if (!isAuthorized(apiKey)) return ResponseEntity.badRequest().body(Map.of("INFO", "Invalid API Key", "success", "false"));

        boolean updated = hr.updateDatabaseNode(nodeName);
        if (!updated) return ResponseEntity.badRequest().body(Map.of("INFO", "Node not found", "success", "false"));

        return ResponseEntity.ok(Map.of("nodeName", nodeName, "success", "true"));
    }


    @GetMapping("/datanode/getAlive")
    public ResponseEntity<?> getAlive(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<DataNode> aliveNodes = hr.getAliveDataNodes();

        class DataNodeList {
            String host;
            String nodeName;
        }

        Vector<DataNodeList> output = new Vector<>();

        if (aliveNodes.isEmpty())
            return ResponseEntity.status(200).body(Map.of("aliveNodes", "NULL", "Timestamp", LocalDateTime.now()));

        for  (DataNode dn : aliveNodes) {
            DataNodeList nl = new DataNodeList();
            nl.host = dn.getHost();
            nl.nodeName = dn.getNodeName();

            output.add(nl);
        }

        return ResponseEntity.status(200).body(Map.of("aliveNodes", output, "Timestamp", LocalDateTime.now()));
    }

    @GetMapping("/balancer/getAlive")
    public ResponseEntity<?> getAliveBalancers(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<BalancerNode> aliveNodes = hr.getAliveBalancers();

        class BalancerNodeList {
            String host;
            String nodeName;
        }

        Vector<BalancerNodeList> output = new Vector<>();

        if (aliveNodes.isEmpty())
            return ResponseEntity.status(200).body(Map.of("aliveNodes", "NULL", "Timestamp", LocalDateTime.now()));

        for (BalancerNode bn : aliveNodes) {
            BalancerNodeList nl = new BalancerNodeList();
            nl.host = bn.getHost();
            nl.nodeName = bn.getNodeName();

            output.add(nl);
        }

        return ResponseEntity.status(200).body(Map.of("aliveNodes", output, "Timestamp", LocalDateTime.now()));
    }



    @GetMapping("/database/getAlive")
    public ResponseEntity<?> getAliveDatabaseNodes(@RequestHeader(API_HEADER) String apiKey) {
        if (!isAuthorized(apiKey)) return ResponseEntity.status(403).body("Invalid API key");

        Vector<DatabaseNode> aliveNodes = hr.getAliveDatabaseNodes();
        return ResponseEntity.status(200).body(Map.of("aliveNodes", aliveNodes, "Timestamp", LocalDateTime.now()));
    }

}
