package org.frostbyte.clientnode.controllers;

import org.frostbyte.clientnode.services.AsyncUploadService;
import org.frostbyte.clientnode.services.BalancerNodeClient;
import org.frostbyte.clientnode.services.KeyClient;
import org.frostbyte.clientnode.services.SessionManager;
import org.frostbyte.clientnode.models.configModel;
import org.frostbyte.clientnode.services.MasterNodeDiscoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    public ClientController(configModel config, KeyClient keyClient, AsyncUploadService asyncUploadService,
                            SessionManager sessionManager, MasterNodeDiscoveryService discoveryService,
                            BalancerNodeClient balancerClient) {
        this.config = config;
        this.keyClient = keyClient;
        this.asyncUploadService = asyncUploadService;
        this.sessionManager = sessionManager;
        this.discoveryService = discoveryService;
        this.balancerClient = balancerClient;
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
}