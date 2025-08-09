package org.frostbyte.datanode;

/*
 * Frostbyte - Encrypted Distributed Object Storage
 * Copyright (c) 2025 Affan Pathan
 *
 * Licensed for non-commercial use only.
 * Commercial usage requires a separate license.
 * Contact: your-email@example.com
 */


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


@SpringBootApplication
@EnableScheduling

public class App {
    public static void main(String[] args) throws Exception {
        // Skip config loading if we're in test mode
        if (System.getProperty("spring.test.context") == null) {
            loadConfigAndSetProperties();
        }

        SpringApplication.run(App.class, args);
    }

    private static void loadConfigAndSetProperties() throws Exception {
        File file = new File("config.json");

        if (!file.exists()) {
            try (InputStream in = App.class.getClassLoader().getResourceAsStream("config.json")) {
                if (in == null) {
                    throw new RuntimeException("Sample config.json not found in resources!");
                }
                Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Sample config.json created. You can modify it and restart.");
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(file);

        System.setProperty("server.address", root.get("host").asText());
        System.setProperty("server.port", root.get("port").asText());
    }
}