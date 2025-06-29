package org.frostbyte.datanode.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.datanode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Configuration
public class ConfigLoader {
    private static final String CONFIG_NAME = "config.json";

    @Bean
    public configModel configModel() {
        try {
            File configFile = new File(CONFIG_NAME);
            ObjectMapper mapper = new ObjectMapper();

            if (!configFile.exists()) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.json")) {
                    if (in == null) {
                        throw new RuntimeException("Sample config.json not found in resources!");
                    }
                    Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Sample config.json created. You can modify it and restart.");
                }
            }

            return mapper.readValue(configFile, configModel.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.json", e);
        }

    }
}
