package org.frostbyte.datanode.controllers;

import org.frostbyte.datanode.models.configModel;
import org.frostbyte.datanode.utils.FolderSizeChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/*
    * Datanode_controller
    * Handles chunk (snowflake) upload/download and storage monitoring
    * Communicates with BalancerNode and MasterNode
 */

@RestController
public class Datanode_controller {
    private static final Logger log = Logger.getLogger(Datanode_controller.class.getName());
    private static final String API_HEADER = "X-API-Key";
    private final configModel config;

    @Autowired
    public Datanode_controller(configModel config) {
        this.config = config;
    }

    // 1. CHUNK STORAGE OPERATIONS

    /**
     * Upload and store encrypted chunk (snowflake file).
     * Called by BalancerNode when distributing chunk replicas
     * Validates capacity before accepting upload (returns 507 if >capacity).
     * Prevents duplicate uploads (returns 409 if file already exists).
     *
     * @param apiKey Internal API key for authentication
     * @param file Multipart snowflake file to store
     * @return 200 OK with success message, 507 INSUFFICIENT_STORAGE if at capacity,
     *         409 CONFLICT if chunk already exists
     */
    @PostMapping("/datanode/upload")
    public ResponseEntity<?> uploadsnowflake(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestParam("snowflake") MultipartFile file) {

        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded.");
        }

        try {
            double currentUsed = FolderSizeChecker.getFolderSize(Paths.get(config.getSnowflakeFolder()));
            if (currentUsed >= config.getSize()) {
                return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of(
                        "status", "error",
                        "snowflakeName", file.getName(),
                        "message", "Space insufficient on node"));
            }

            // Save the file
            Path snowflakeFolder = Paths.get(config.getSnowflakeFolder());
            Files.createDirectories(snowflakeFolder);

            String originalFilename = file.getOriginalFilename();
            log.info(String.format("[UPLOAD-REQUEST] originalFilename=%s size=%d", originalFilename, file.getSize()));

            Path targetFile = snowflakeFolder.resolve(Objects.requireNonNull(originalFilename));
            log.info(String.format("[UPLOAD-SAVING] targetFile=%s", targetFile));

            if (targetFile.toFile().exists()) {
                log.info("File " + targetFile + " already exists.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        Map.of( "status", "error",
                                "message", "snowflake with same name already exists"));
            }
            file.transferTo(targetFile);

            log.info(String.format("[UPLOAD-SUCCESS] saved as: %s", targetFile.getFileName()));
            return ResponseEntity.ok(Map.of("status", "success",
                                            "snowflakeName", file.getOriginalFilename(),
                                            "message", "snowflake uploaded successfully"));

        } catch (Exception e) {
            log.warning("Upload failed" + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Upload failed: " + e.getMessage());
        }
    }

    /**
     * Download chunk by snowflake filename.
     * Called by BalancerNode when servicing download requests.
     * Returns binary snowflake data (metadata + encrypted chunk).
     * Logs detailed debugging info if file not found.
     *
     * @param apiKey Internal API key for authentication
     * @param fileName Snowflake filename to retrieve (e.g., "{chunkId}.snowflake")
     * @return 200 OK with binary snowflake data, 404 NOT_FOUND if chunk doesn't exist
     */
    @PostMapping("/datanode/download")
    public ResponseEntity<?> post(@RequestHeader(value = API_HEADER) String apiKey,
                                       @RequestParam(value = "snowflake_name") String fileName) {

        // look up folder and send back the file
        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        try {
            Path snowflakeFolder = Paths.get(config.getSnowflakeFolder());
            Path filePath = snowflakeFolder.resolve(fileName).normalize();

            log.info(String.format("[DOWNLOAD-REQUEST] snowflake_name=%s resolvedPath=%s", fileName, filePath));

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                // Log what files actually exist for debugging
                try {
                    log.warning(String.format("[FILE-NOT-FOUND] requested=%s folder=%s", fileName, snowflakeFolder));
                    if (Files.exists(snowflakeFolder)) {
                        log.info("[EXISTING-FILES] Files in folder:");
                        Files.list(snowflakeFolder).forEach(f -> log.info("  - " + f.getFileName()));
                    }
                } catch (Exception listEx) {
                    log.warning("Could not list files: " + listEx.getMessage());
                }
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("snowflake not found.");
            }

            InputStream fileStream = Files.newInputStream(filePath);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());

            return new ResponseEntity<>(new InputStreamResource(fileStream), headers, HttpStatus.OK);

        } catch (IOException e) {
            log.warning("Failed to download snowflake {}" + fileName + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to read snowflake: " + e.getMessage());
        }
    }

    // =================================================================
    // 2. CAPACITY MONITORING
    // =================================================================

    /**
     * Get current storage usage metrics.
     * Called by MasterNode during heartbeat processing to track node capacity.
     * Calculates real-time disk usage of snowflake storage folder.
     *
     * @param apiKey Internal API key for authentication
     * @return JSON with currentUsedGB and fillPercent fields
     */
    @GetMapping("/datanode/storage")
    public ResponseEntity<?> getStorage(@RequestHeader(value = API_HEADER) String apiKey){

        if (!config.getMasterAPIKey().equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key.");
        }

        try {
            double currentUsed = FolderSizeChecker.getFolderSize(Paths.get(config.getSnowflakeFolder()));
            double fillPercent = 100.0 * currentUsed / (double) config.getSize();

            return ResponseEntity.status(HttpStatus.OK).body(Map.of("currentUsedGB", currentUsed, "fillPercent", fillPercent));
        } catch (IOException e) {
            log.warning("Failed Query space " + config.getSnowflakeFolder() + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed Query space " + e.getMessage());
        }

    }

}
