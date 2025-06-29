package org.frostbyte.datanode.controllers;


import jakarta.servlet.annotation.MultipartConfig;
import org.frostbyte.datanode.models.configModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import org.frostbyte.datanode.utils.FolderSizeChecker;


@RestController
public class Datanode_controller {
    private static final Logger log = Logger.getLogger(Datanode_controller.class.getName());
    private static final String API_HEADER = "X-API-Key";
    private configModel config;

    @Autowired
    public Datanode_controller(configModel config) {
        this.config = config;
    }


    @PostMapping("/datanode/upload")
    public ResponseEntity<?> uploadChunk(
            @RequestHeader(value = API_HEADER, required = true) String apiKey,
            @RequestParam("chunk") MultipartFile file) {

        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded.");
        }

        try {
            double currentUsed = FolderSizeChecker.getFolderSize(Paths.get(config.getChunkFolder()));
            if (currentUsed >= config.getSize()) {
                return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of(
                        "status", "error",
                        "chunkName", file.getName(),
                        "message", "Space insufficient on node"));
            }

            // Save the file
            Path chunkFolder = Paths.get(config.getChunkFolder());
            Files.createDirectories(chunkFolder);

            Path targetFile = chunkFolder.resolve(Objects.requireNonNull(file.getOriginalFilename()));
            if (targetFile.toFile().exists()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        Map.of( "status", "error",
                                "chunkName", file.getName(),
                                "message", "Chunk with same name already exists"));
            }
            file.transferTo(targetFile);

            log.info("Received chunk: " + targetFile.getFileName());
            return ResponseEntity.ok(Map.of("status", "success",
                                            "chunkName", file.getOriginalFilename(),
                                            "message", "Chunk uploaded successfully"));

        } catch (Exception e) {
            log.warning("Upload failed" + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/datanode/download")
    public ResponseEntity<?> post(@RequestHeader(value = API_HEADER, required = true) String apiKey,
                                       @RequestParam(value = "chunk_name", required = true) String fileName) {

        // look up folder and send back the file
        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        try {
            Path chunkFolder = Paths.get(config.getChunkFolder());
            Path filePath = chunkFolder.resolve(fileName).normalize();

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Chunk not found.");
            }

            InputStream fileStream = Files.newInputStream(filePath);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());

            return new ResponseEntity<>(new InputStreamResource(fileStream), headers, HttpStatus.OK);

        } catch (IOException e) {
            log.warning("Failed to download chunk {}" + fileName + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to read chunk: " + e.getMessage());
        }
    }

    @GetMapping("/datanode/storage")
    public ResponseEntity<?> getStorage(@RequestHeader(value = API_HEADER, required = true) String apiKey) throws IOException {

        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        try {
            double currentUsed = FolderSizeChecker.getFolderSize(Paths.get(config.getChunkFolder()));
            double fillPercent = 100.0 * currentUsed / (double) config.getSize();

            return ResponseEntity.status(HttpStatus.OK).body(Map.of("currentUsedGB", currentUsed, "fillPercent", fillPercent));
        } catch (IOException e) {
            log.warning("Failed Query space " + config.getChunkFolder() + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed Query space " + e.getMessage());
        }

    }

}
