package org.frostbyte.datanode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.datanode.models.configModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


@SpringBootApplication
@EnableScheduling
public class App{
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

        configModel test = mapper.readValue(new File("config.json"), configModel.class);
        System.out.println("Parsed: " + test.getSize());

        System.setProperty("server.address", root.get("host").asText());
        System.setProperty("server.port", root.get("port").asText());

        SpringApplication.run(App.class, args);
    }
}