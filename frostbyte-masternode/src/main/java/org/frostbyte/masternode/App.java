package org.frostbyte.masternode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.masternode.models.configModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
@SpringBootApplication
@EnableScheduling
public class App {
    public static void main(String[] args) throws IOException {
        // Open config.json, otherwise copy from resources to root folder
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
        JsonNode root;
        try {
            root = mapper.readTree(file);
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
        
        System.setProperty("server.address", root.get("host").asText());
        System.setProperty("server.port", root.get("port").asText());

        SpringApplication.run(App.class, args);
    }
}