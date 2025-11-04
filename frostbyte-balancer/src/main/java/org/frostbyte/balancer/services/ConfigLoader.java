package org.frostbyte.balancer.services;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Logger;

@Configuration
public class ConfigLoader {
    private static final String CONFIG_FILE = "application.properties";
    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());

    @PostConstruct
    public void init() {
        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            try {
                createTemplateConfig(configFile);
                System.out.println("========================================");
                System.out.println("Template application.properties created!");
                System.out.println("Please configure it and restart the application.");
                System.out.println("Location: " + configFile.getAbsolutePath());
                System.out.println("========================================");
                System.exit(0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create template application.properties", e);
            }
        }
    }

    private void createTemplateConfig(File file) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("# Frostbyte Balancer Configuration\n");
            writer.write("# Server Configuration\n");
            writer.write("frostbyte.balancer.host=127.0.0.1\n");
            writer.write("frostbyte.balancer.port=8999\n");
            writer.write("frostbyte.balancer.node-name=Balancernode_1\n");
            writer.write("\n");
            writer.write("# Master Nodes (comma-separated)\n");
            writer.write("frostbyte.balancer.master-nodes=127.0.0.1:7001\n");
            writer.write("\n");
            writer.write("# Security Configuration\n");
            writer.write("frostbyte.balancer.master-api-key=ABCDEFEG\n");
            writer.write("\n");
            writer.write("# Replication Configuration\n");
            writer.write("frostbyte.balancer.replica-count=3\n");
        }
    }
}