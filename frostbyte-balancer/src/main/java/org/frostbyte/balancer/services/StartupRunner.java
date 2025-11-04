package org.frostbyte.balancer.services;

import org.frostbyte.balancer.utils.MasternodeCommunicator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

@Component
public class StartupRunner implements CommandLineRunner {

    private final MasternodeCommunicator masternodeCommunicator;
    private static final Logger log = Logger.getLogger(StartupRunner.class.getName());

    public StartupRunner(MasternodeCommunicator masternodeCommunicator) {
        this.masternodeCommunicator = masternodeCommunicator;
    }

    @Override
    public void run(String... args) {
        log.info("BalancerNode starting up...");
        log.info("Registering with MasterNodes...");

        masternodeCommunicator.registerWithMasters();

        log.info("BalancerNode startup complete");
    }
}

