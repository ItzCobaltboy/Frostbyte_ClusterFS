package org.frostbyte.clientnode.services;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class StartupRunner implements CommandLineRunner {
    private static final Logger log = Logger.getLogger(StartupRunner.class.getName());
    private final MasterNodeDiscoveryService discoveryService;

    public StartupRunner(MasterNodeDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Override
    public void run(String... args) {
        Instant start = Instant.now();
        log.info("[STARTUP] ClientNode starting up - discovering DatabaseNode...");

        try {
            // Discover DatabaseNode address from MasterNode(s)
            String dbNode = discoveryService.discoverDatabaseNode();
            Duration duration = Duration.between(start, Instant.now());
            log.info(String.format("[STARTUP] ✓ DatabaseNode discovered successfully in %d ms: %s", duration.toMillis(), dbNode));
        } catch (IllegalStateException e) {
            Duration duration = Duration.between(start, Instant.now());
            log.log(Level.SEVERE, String.format("[STARTUP] ✗ Failed to discover DatabaseNode after %d ms: %s", duration.toMillis(), e.getMessage()), e);
            // Application can continue but will fail when trying to use DatabaseNode
        }
    }
}