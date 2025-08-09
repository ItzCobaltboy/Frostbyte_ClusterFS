package org.frostbyte.masternode.config;

import org.frostbyte.masternode.models.configModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test") // Only active during tests
public class TestConfig {

    @Bean
    public configModel configModel() { // Same name as production bean
        configModel config = new configModel();
        config.setHost("localhost");
        config.setPort(7000);
        config.setNodeName("TestMasterNode");
        config.setMasterAPIKey("TEST_KEY_123");
        return config;
    }
}