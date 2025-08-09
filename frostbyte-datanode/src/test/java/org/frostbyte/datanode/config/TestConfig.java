package org.frostbyte.datanode.config;

import org.frostbyte.datanode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test") // Only during tests
public class TestConfig {

    @Bean
    public configModel configModel() {
        configModel config = new configModel();
        config.setHost("localhost");
        config.setPort(6960);
        config.setNodeName("TestDataNode");
        config.setMasterAPIKey("TEST_KEY_123");
        config.setMasterNodes(new String[]{"localhost:7000"});
        config.setSnowflakeFolder("test-chunks");
        config.setSize(100); // 100 GB
        return config;
    }
}