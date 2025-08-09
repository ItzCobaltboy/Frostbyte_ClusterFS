package org.frostbyte.masternode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.masternode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Configuration
public class ConfigLoader {
    private static final String CONFIG_NAME = "config.json";

    @Bean
    @Profile("!test") // This bean is NOT loaded during tests
    public configModel configModel() {
        try {
            File configFile = new File(CONFIG_NAME);

            // Create default config if doesn't exist
            if (!configFile.exists()) {
                try (InputStream in = ConfigLoader.class.getClassLoader()
                        .getResourceAsStream(CONFIG_NAME)) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(configFile, configModel.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

}