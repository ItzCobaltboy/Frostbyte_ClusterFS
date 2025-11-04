package org.frostbyte.databaseNode.services;

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

    public static void ensureConfigExists() {
        // Check for application.properties in the current working directory
        File configFile = new File(CONFIG_FILE);

        // Only generate if it doesn't exist in the working directory
        if (!configFile.exists()) {
            try {
                createTemplateConfig(configFile);
                log.info("========================================");
                log.info("Template application.properties created!");
                log.info("Please configure it and restart the application.");
                log.info("Location: " + configFile.getAbsolutePath());
                log.info("========================================");
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

    private static void createTemplateConfig(File file) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("# Frostbyte DatabaseNode Configuration\n");
            writer.write("# Server Configuration\n");
            writer.write("frostbyte.databasenode.host=127.0.0.1\n");
            writer.write("frostbyte.databasenode.port=8082\n");
            writer.write("frostbyte.databasenode.node-name=DatabaseNode-01\n");
            writer.write("\n");
            writer.write("# Master Nodes (comma-separated)\n");
            writer.write("frostbyte.databasenode.master-nodes=127.0.0.1:7000,127.0.0.1:7001\n");
            writer.write("\n");
            writer.write("# Security Configuration\n");
            writer.write("frostbyte.databasenode.master-api-key=ABCDEFEG\n");
            writer.write("\n");
            writer.write("# Database Configuration\n");
            writer.write("frostbyte.databasenode.database.host=localhost\n");
            writer.write("frostbyte.databasenode.database.port=5432\n");
            writer.write("frostbyte.databasenode.database.name=frostbyte_metadata_db\n");
            writer.write("frostbyte.databasenode.database.username=postgres_user\n");
            writer.write("frostbyte.databasenode.database.password=your_secure_password\n");
            writer.write("\n");
            writer.write("# Spring Database Configuration\n");
            writer.write("spring.datasource.url=jdbc:postgresql://${frostbyte.databasenode.database.host}:${frostbyte.databasenode.database.port}/${frostbyte.databasenode.database.name}\n");
            writer.write("spring.datasource.username=${frostbyte.databasenode.database.username}\n");
            writer.write("spring.datasource.password=${frostbyte.databasenode.database.password}\n");
            writer.write("spring.datasource.driver-class-name=org.postgresql.Driver\n");
            writer.write("\n");
            writer.write("# JPA / Hibernate Configuration\n");
            writer.write("spring.jpa.hibernate.ddl-auto=update\n");
            writer.write("spring.jpa.show-sql=true\n");
            writer.write("spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect\n");
        }
    }
}