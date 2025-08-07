package org.frostbyte.databaseNode.services;

import org.frostbyte.databaseNode.utils.MasterNodeCommunicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements CommandLineRunner {

    private final MasterNodeCommunicator communicator;
    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    public StartupRunner(MasterNodeCommunicator communicator) {
        this.communicator = communicator;
    }

    @Override
    public void run(String... args) {
        log.info("Frostbyte DatabaseNode is starting up... Registering with MasterNode(s).");
        communicator.registerWithMasters();
    }

    @Scheduled(fixedRate = 30000) // Ping every 30 seconds
    public void sendHeartbeat() {
        log.info("Sending heartbeat to MasterNode(s)...");
        communicator.pingMasters();
    }
}