package org.frostbyte.balancer.services;

import org.frostbyte.balancer.utils.MasternodeCommunicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

@Service
public class HeartbeatService {

    private final MasternodeCommunicator masternodeCommunicator;
    private static final Logger log = Logger.getLogger(HeartbeatService.class.getName());

    public HeartbeatService(MasternodeCommunicator masternodeCommunicator) {
        this.masternodeCommunicator = masternodeCommunicator;
    }

    /**
     * Send periodic heartbeat to MasterNodes
     * Runs every 30 seconds
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        log.fine("Sending heartbeat to MasterNodes...");
        masternodeCommunicator.pingMasters();
    }
}

