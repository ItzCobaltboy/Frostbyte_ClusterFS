package org.frostbyte.databaseNode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.databaseNode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Configuration
public class ConfigLoader {
    private static final String CONFIG_FILE = "config.json";

    @Bean
    public configModel configModel() {
        try {
            File configFile = new File(CONFIG_FILE);
            // If config.json doesn't exist, copy the template from resources
            if (!configFile.exists()) {
                try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                    if (in == null) {
                        throw new RuntimeException("Default config.json not found in resources!");
                    }
                    Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Default " + CONFIG_FILE + " created. Please configure it and restart.");
                }
            }

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(configFile, configModel.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + CONFIG_FILE, e);
        }
    }
}