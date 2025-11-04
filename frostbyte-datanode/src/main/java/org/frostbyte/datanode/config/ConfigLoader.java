package org.frostbyte.datanode.config;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Configuration
public class ConfigLoader {
    private static final String CONFIG_FILE = "application.properties";

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
            writer.write("# Frostbyte DataNode Configuration\n");
            writer.write("# Server Configuration\n");
            writer.write("frostbyte.datanode.host=127.0.0.1\n");
            writer.write("frostbyte.datanode.port=6960\n");
            writer.write("frostbyte.datanode.node-name=Datanode_1\n");
            writer.write("\n");
            writer.write("# Master Nodes (comma-separated)\n");
            writer.write("frostbyte.datanode.master-nodes=127.0.0.1:7000\n");
            writer.write("\n");
            writer.write("# Storage Configuration\n");
            writer.write("frostbyte.datanode.snowflake-folder=chunks\n");
            writer.write("frostbyte.datanode.master-api-key=ABCDEFEG\n");
            writer.write("frostbyte.datanode.size=5\n");
            writer.write("\n");
            writer.write("# Spring Configuration\n");
            writer.write("spring.servlet.multipart.max-file-size=5GB\n");
            writer.write("spring.servlet.multipart.max-request-size=5GB\n");
        }
    }
}
