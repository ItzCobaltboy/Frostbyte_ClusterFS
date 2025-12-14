package org.frostbyte.clientnode.controllers;

import org.frostbyte.clientnode.services.*;
import org.frostbyte.clientnode.models.Snowflake;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/public")
public class ClientController {

    private static final Logger log = Logger.getLogger(ClientController.class.getName());

    private final configModel config;
    private final KeyClient keyClient;
    private final AsyncUploadService asyncUploadService;
    private final SessionManager sessionManager;
    private final MasterNodeDiscoveryService discoveryService;
    private final BalancerNodeClient balancerClient;
    private final DatabaseNodeClient databaseNodeClient;

    public ClientController(configModel config, KeyClient keyClient, AsyncUploadService asyncUploadService,
                            SessionManager sessionManager, MasterNodeDiscoveryService discoveryService,
                            BalancerNodeClient balancerClient, DatabaseNodeClient databaseNodeClient) {
        this.config = config;
        this.keyClient = keyClient;
        this.asyncUploadService = asyncUploadService;
        this.sessionManager = sessionManager;
        this.discoveryService = discoveryService;
        this.balancerClient = balancerClient;
        this.databaseNodeClient = databaseNodeClient;
    }

    /**
     * Single upload endpoint: initializes session on DatabaseNode, performs chunking + per-chunk key requests,
     * and sends snowflakes directly to balancer (no local storage).
     * Accepts multipart file upload and streams/chunks it server-side.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(name = "totalChunks", required = false, defaultValue = "0") int totalChunks) {
        Instant start = Instant.now();
        if (file == null || file.isEmpty()) {
            log.warning("[UPLOAD-REQUEST] empty or missing file");
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded-file";
        long fileSize = file.getSize();

        // Determine chunk size (in bytes) - use config or default to 1MB
        int configuredChunkSizeMB = config.getChunkSizeMB() > 0 ? config.getChunkSizeMB() : 1;
        long chunkSizeBytes = (long) configuredChunkSizeMB * 1024 * 1024;

        // Calculate totalChunks from file size if not provided
        if (totalChunks <= 0) {
            totalChunks = (int) Math.ceil((double) fileSize / chunkSizeBytes);
        }

        log.info(String.format("[UPLOAD-REQUEST] filename=%s size=%d chunkSize=%dMB totalChunks=%d",
                originalFilename, fileSize, configuredChunkSizeMB, totalChunks));

        String sessionId = null; // Initialize to handle early exceptions
        String fileId;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try (InputStream in = file.getInputStream()) {
            // 1) Initialize upload session on DatabaseNode
            Map<String, Object> initResp = keyClient.initializeUploadSession(originalFilename, fileSize, totalChunks);
            sessionId = initResp.get("sessionId").toString();
            fileId = initResp.get("fileId").toString();

            log.info(String.format("[SESSION-INIT] sessionId=%s fileId=%s filename=%s", sessionId, fileId, originalFilename));

            // 2) Discover a balancer node to send snowflakes to
            String selectedBalancer = discoveryService.discoverBalancerNode();
            if (selectedBalancer == null || selectedBalancer.isEmpty()) {
                log.severe("[BALANCER-DISCOVERY-FAILED] No balancer node available");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No balancer node available"));
            }
            log.info(String.format("[BALANCER-SELECTED] balancer=%s for fileId=%s", selectedBalancer, fileId));

            // 3) Create ephemeral RSA keypair for this session and store it
            KeyPair kp = keyClient.generateClientKeyPair();
            sessionManager.createSession(fileId, originalFilename, totalChunks, configuredChunkSizeMB, kp);
            String clientPublicKey = keyClient.publicKeyToBase64(kp.getPublic());
            log.fine(String.format("[SESSION-KEY] generated ephemeral RSA keypair for session fileId=%s publicKeyLen=%d",
                    fileId, clientPublicKey.length()));

            log.info(String.format("[SESSION-READY] sessionId=%s fileId=%s filename=%s", sessionId, fileId, originalFilename));

            // 4) Stream and chunk the file
            byte[] buffer = new byte[(int) chunkSizeBytes];
            int read;
            int chunkNumber = 0;
            final String fixedFileId = fileId; // for lambdas
            final String fixedBalancer = selectedBalancer; // for lambdas

            while ((read = in.read(buffer)) != -1) {
                byte[] chunkBytes = (read == buffer.length) ? buffer.clone() : Arrays.copyOf(buffer, read);

                log.fine(String.format("[CHUNK-READ] fileId=%s chunkNumber=%d bytes=%d", fileId, chunkNumber, chunkBytes.length));

                // Request a per-chunk AES key from DatabaseNode
                Map<String, Object> keyResp = keyClient.requestKeyFromKeyService(clientPublicKey);
                Object chunkIdObj = keyResp.get("chunkId");
                Object encryptedKeyObj = keyResp.get("encryptedKey");
                if (chunkIdObj == null || encryptedKeyObj == null) {
                    throw new IllegalStateException("Key service did not return expected fields");
                }
                String chunkId = chunkIdObj.toString();
                String encryptedKey = encryptedKeyObj.toString();

                // Decrypt AES key using session private key
                String base64AesKey = keyClient.decryptWithPrivateKey(kp.getPrivate(), encryptedKey);

                final int currentChunkNumber = chunkNumber;
                final String fixedChunkId = chunkId;

                // 5) Process chunk (encrypt + create snowflake) and immediately send to balancer
                CompletableFuture<Void> fut = asyncUploadService
                        .processChunk(chunkId, fileId, originalFilename, chunkNumber, totalChunks, chunkBytes, base64AesKey)
                        .thenCompose(snowflake -> {
                            // Send snowflake to balancer (no local storage)
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    String sfName = fixedFileId + "_" + currentChunkNumber + ".snowflake";
                                    Map<String, Object> resp = balancerClient.uploadSnowflakeToBalancer(
                                            fixedBalancer, fixedChunkId, snowflake, sfName);
                                    log.info(String.format("[BALANCER-UPLOADED] chunkId=%s chunkNumber=%d replicas=%s",
                                            fixedChunkId, currentChunkNumber, resp.get("replicasCreated")));
                                    return null;
                                } catch (Exception e) {
                                    log.log(Level.SEVERE, String.format("[BALANCER-UPLOAD-FAILED] chunkId=%s chunkNumber=%d",
                                            fixedChunkId, currentChunkNumber), e);
                                    throw new RuntimeException("Failed to upload chunk to balancer: " + e.getMessage(), e);
                                }
                            }, asyncUploadService.getExecutor());
                        });

                futures.add(fut);
                chunkNumber++;
            }

            // 6) Wait for all chunks to complete
            log.info(String.format("[WAITING] for %d chunk uploads to complete for fileId=%s", futures.size(), fileId));
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 7) Mark session as completed using completeSession
            keyClient.completeSession(sessionId);

            Duration duration = Duration.between(start, Instant.now());
            log.info(String.format("[UPLOAD-SUCCESS] fileId=%s totalChunks=%d durationMs=%d",
                    fileId, chunkNumber, duration.toMillis()));

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "fileId", fileId,
                    "sessionId", sessionId,
                    "filename", originalFilename,
                    "totalChunks", chunkNumber,
                    "durationMs", duration.toMillis()
            ));

        } catch (Exception e) {
            log.log(Level.SEVERE, "[UPLOAD-FAILED] upload failed", e);
            // Best effort mark failed
            try {
                if (sessionId != null) keyClient.updateSessionStatus(sessionId, "FAILED");
            } catch (Exception ignored) {}
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Download endpoint: retrieves file by fileId and streams it to the user
     * Steps:
     * 1. Query DatabaseNode for file chunk map
     * 2. Select a BalancerNode for download routing
     * 3. Generate ephemeral RSA keypair for session
     * 4. For each chunk (sequentially):
     *    a. Download encrypted snowflake from BalancerNode
     *    b. Retrieve AES key from DatabaseNode
     *    c. Decrypt chunk
     *    d. Stream plaintext to user
     *
     * @param fileId UUID of the file to download
     * @return Streaming response with file data
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable("fileId") String fileId) {
        log.info(String.format("[DOWNLOAD-REQUEST] fileId=%s", fileId));

        try {
            // Step 1a: Query DatabaseNode for file metadata (fileSize, uploadStatus)
            Map<String, Object> fileMetadata;
            try {
                fileMetadata = databaseNodeClient.getFileMetadata(fileId);
            } catch (DatabaseNodeClient.FileNotFoundException e) {
                log.warning(String.format("[DOWNLOAD-FILE-NOT-FOUND] fileId=%s", fileId));
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            String fileName = fileMetadata.get("fileName").toString();
            long fileSize = ((Number) fileMetadata.get("fileSize")).longValue();
            String uploadStatus = fileMetadata.get("uploadStatus").toString();

            log.info(String.format("[FILE-METADATA] fileId=%s fileName=%s fileSize=%d uploadStatus=%s",
                    fileId, fileName, fileSize, uploadStatus));

            // Validate file is in COMPLETED status
            if (!"COMPLETED".equalsIgnoreCase(uploadStatus)) {
                log.warning(String.format("[DOWNLOAD-FILE-NOT-READY] fileId=%s status=%s", fileId, uploadStatus));
                return ResponseEntity.status(HttpStatus.CONFLICT).body(null);
            }

            // Step 1b: Query DatabaseNode for file chunk map (chunks + replica locations)
            Map<String, Object> fileChunkMapResponse;
            try {
                fileChunkMapResponse = databaseNodeClient.getFileChunkMap(fileId);
            } catch (DatabaseNodeClient.FileNotFoundException e) {
                log.warning(String.format("[DOWNLOAD-CHUNK-MAP-NOT-FOUND] fileId=%s", fileId));
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Extract file map
            @SuppressWarnings("unchecked")
            Map<String, Object> fileMap = (Map<String, Object>) fileChunkMapResponse.get("fileMap");

            int totalChunks = ((Number) fileMap.get("totalChunks")).intValue();

            log.info(String.format("[FILE-CHUNK-MAP] fileId=%s totalChunks=%d", fileId, totalChunks));

            // Extract chunks list
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) fileMap.get("chunks");

            // Defensive sorting by chunkNumber
            chunks.sort(Comparator.comparingInt(chunk -> ((Number) chunk.get("chunkNumber")).intValue()));

            // Validate chunk continuity
            for (int i = 0; i < chunks.size(); i++) {
                int chunkNumber = ((Number) chunks.get(i).get("chunkNumber")).intValue();
                if (chunkNumber != i) {
                    throw new Exception("Chunk sequence gap detected: expected " + i + ", got " + chunkNumber);
                }
            }

            // Step 2: Select a BalancerNode for download routing
            String selectedBalancer = discoveryService.discoverBalancerNode();
            if (selectedBalancer == null || selectedBalancer.isEmpty()) {
                log.severe("[BALANCER-DISCOVERY-FAILED] No balancer node available for download");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
            }
            log.info(String.format("[BALANCER-SELECTED] balancer=%s for download fileId=%s", selectedBalancer, fileId));

            // Step 3: Generate ephemeral RSA keypair for this download session
            KeyPair downloadKeyPair = keyClient.generateClientKeyPair();
            String clientPublicKey = keyClient.publicKeyToBase64(downloadKeyPair.getPublic());
            log.info(String.format("[DOWNLOAD-SESSION-KEY] generated RSA keypair for fileId=%s publicKeyLen=%d",
                    fileId, clientPublicKey.length()));

            // Step 4: Create streaming response body
            StreamingResponseBody streamingResponseBody = outputStream -> {
                Instant downloadStart = Instant.now();
                long totalBytesStreamed = 0;

                try {
                    // Sequential chunk download loop
                    for (int i = 0; i < chunks.size(); i++) {
                        Map<String, Object> chunk = chunks.get(i);
                        String chunkId = chunk.get("chunkId").toString();
                        int chunkNumber = ((Number) chunk.get("chunkNumber")).intValue();

                        log.fine(String.format("[CHUNK-DOWNLOAD-START] chunkNumber=%d/%d chunkId=%s",
                                chunkNumber, totalChunks - 1, chunkId));

                        // 4a. Download encrypted snowflake from BalancerNode
                        byte[] encryptedSnowflakeBytes = balancerClient.downloadChunkFromBalancer(
                                selectedBalancer, fileId, chunkId, chunkNumber);

                        // Parse snowflake
                        Snowflake snowflake = Snowflake.fromByteArray(encryptedSnowflakeBytes);

                        // Validate chunk metadata
                        if (snowflake.getChunkNumber() != chunkNumber) {
                            throw new Exception("Chunk number mismatch: expected " + chunkNumber +
                                    ", got " + snowflake.getChunkNumber());
                        }

                        // 4b. Retrieve AES key from DatabaseNode
                        Map<String, Object> keyResponse = keyClient.requestKeyFromKeyService(
                                keyClient.publicKeyToBase64(downloadKeyPair.getPublic()));

                        // For download, we need to retrieve the existing key, not generate a new one
                        // Let's use a different approach - batch retrieve keys at the start
                        // For now, we'll retrieve individual keys (can optimize later)
                        String encryptedAesKey = retrieveChunkKey(chunkId, clientPublicKey);

                        // Decrypt AES key with session private key
                        String base64AesKey = keyClient.decryptWithPrivateKey(
                                downloadKeyPair.getPrivate(), encryptedAesKey);

                        // 4c. Decrypt chunk data
                        byte[] plaintext = ChunkEncryptionService.decrypt(
                                snowflake.getEncryptedData(), base64AesKey);

                        // 4d. Stream plaintext to user
                        outputStream.write(plaintext);
                        totalBytesStreamed += plaintext.length;

                        log.info(String.format("[CHUNK-STREAM] chunkNumber=%d plaintextSize=%d totalStreamed=%d",
                                chunkNumber, plaintext.length, totalBytesStreamed));
                    }

                    outputStream.flush();

                    Duration downloadDuration = Duration.between(downloadStart, Instant.now());
                    log.info(String.format("[DOWNLOAD-SUCCESS] fileId=%s fileName=%s totalChunks=%d totalBytes=%d durationMs=%d",
                            fileId, fileName, totalChunks, totalBytesStreamed, downloadDuration.toMillis()));

                } catch (Exception e) {
                    log.log(Level.SEVERE, "[DOWNLOAD-STREAM-FAILED] fileId=" + fileId, e);
                    throw new RuntimeException("Download stream failed: " + e.getMessage(), e);
                }
            };

            // Return streaming response with appropriate headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", fileName);
            headers.setContentLength(fileSize);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(streamingResponseBody);

        } catch (Exception e) {
            log.log(Level.SEVERE, "[DOWNLOAD-FAILED] download initialization failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Helper method to retrieve a single chunk's encryption key from DatabaseNode
     */
    private String retrieveChunkKey(String chunkId, String clientPublicKey) throws Exception {
        // This should call a key retrieval endpoint on DatabaseNode
        // For now, we'll use the existing requestKeyFromKeyService method
        // In production, this should be a dedicated /keys/retrieve endpoint

        // Using KeyClient's method to retrieve key for specific chunkId
        // Note: The existing requestKeyFromKeyService generates a NEW key,
        // so we need to add a proper retrieve method to KeyClient

        // For now, let's create the request manually
        String dbNodeUrl = discoveryService.discoverDatabaseNode();
        if (!dbNodeUrl.startsWith("http://") && !dbNodeUrl.startsWith("https://")) {
            dbNodeUrl = "http://" + dbNodeUrl;
        }
        String endpoint = dbNodeUrl + "/keys/retrieve";

        org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", config.getMasterAPIKey());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("chunkId", chunkId);
        requestBody.put("publicKey", clientPublicKey);

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
            new org.springframework.http.HttpEntity<>(requestBody, headers);

        org.springframework.http.ResponseEntity<Map> response = rest.postForEntity(
            endpoint, entity, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new Exception("Failed to retrieve chunk key: " + response.getStatusCode());
        }

        return response.getBody().get("encryptedKey").toString();
    }
}