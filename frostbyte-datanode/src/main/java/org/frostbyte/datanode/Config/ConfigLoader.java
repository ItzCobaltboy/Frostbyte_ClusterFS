package org.frostbyte.datanode.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.datanode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.File;


@Configuration
public class ConfigLoader {
    private static final String CONFIG_NAME = "config.json";

    @Bean
    public configModel configModel() {
        try {
            File configFile = new File(CONFIG_NAME);
            ObjectMapper mapper = new ObjectMapper();

            return mapper.readValue(configFile, configModel.class);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to load config.json", e);
        }

    }
}
