package org.frostbyte.clientnode.controllers;

import org.frostbyte.clientnode.services.AsyncUploadService;
import org.frostbyte.clientnode.services.KeyClient;
import org.frostbyte.clientnode.services.SessionManager;
import org.frostbyte.clientnode.models.configModel;
import org.frostbyte.clientnode.models.Snowflake;
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
@RequestMapping("/api")
public class ClientController {

    private static final Logger log = Logger.getLogger(ClientController.class.getName());

    private final configModel config;
    private final KeyClient keyClient;
    private final AsyncUploadService asyncUploadService;
    private final SessionManager sessionManager;

    public ClientController(configModel config, KeyClient keyClient, AsyncUploadService asyncUploadService, SessionManager sessionManager) {
        this.config = config;
        this.keyClient = keyClient;
        this.asyncUploadService = asyncUploadService;
        this.sessionManager = sessionManager;
    }

    /**
     * Single upload endpoint: initializes session on DatabaseNode and performs chunking + per-chunk key requests.
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

        try (InputStream in = file.getInputStream()) {
            // 1) Initialize upload session on DatabaseNode
            log.info("[SESSION-INIT] calling databaseNode to initialize upload session");
            Map<String, Object> initResp = keyClient.initializeUploadSession(originalFilename, fileSize, totalChunks);
            String sessionId = initResp.get("sessionId").toString();
            String fileId = initResp.get("fileId").toString();
            log.info(String.format("[SESSION-INIT-RESP] sessionId=%s fileId=%s response=%s", sessionId, fileId, initResp));

            // 2) Create ephemeral RSA keypair for this session and store it
            KeyPair kp = keyClient.generateClientKeyPair();
            sessionManager.createSession(fileId, originalFilename, totalChunks, configuredChunkSizeMB, kp);
            String clientPublicKey = keyClient.publicKeyToBase64(kp.getPublic());
            log.fine(String.format("[SESSION-KEY] generated ephemeral RSA keypair for session fileId=%s publicKeyLen=%d", fileId, clientPublicKey.length()));

            log.info(String.format("[SESSION-READY] sessionId=%s fileId=%s filename=%s", sessionId, fileId, originalFilename));

            // 3) Stream and chunk the file - use same chunkSizeBytes calculated above
            byte[] buffer = new byte[(int) chunkSizeBytes];
            int read;
            int chunkNumber = 0;
            List<CompletableFuture<Snowflake>> futures = new ArrayList<>();

            while ((read = in.read(buffer)) != -1) {
                byte[] chunkBytes = (read == buffer.length) ? buffer.clone() : Arrays.copyOf(buffer, read);

                log.fine(String.format("[CHUNK-READ] fileId=%s chunkNumber=%d bytes=%d", fileId, chunkNumber, chunkBytes.length));

                // 4) For each chunk, request a per-chunk AES key from DatabaseNode
                log.fine(String.format("[KEY-REQUEST] requesting AES key for chunkNumber=%d fileId=%s", chunkNumber, fileId));
                Map<String, Object> keyResp = keyClient.requestKeyFromKeyService(clientPublicKey);
                Object chunkIdObj = keyResp.get("chunkId");
                Object encryptedKeyObj = keyResp.get("encryptedKey");
                if (chunkIdObj == null || encryptedKeyObj == null) {
                    log.severe(String.format("[KEY-ERROR] invalid key response for chunkNumber=%d: %s", chunkNumber, keyResp));
                    throw new IllegalStateException("Key service did not return expected fields");
                }
                String chunkId = chunkIdObj.toString();
                String encryptedKey = encryptedKeyObj.toString();
                log.fine(String.format("[KEY-RESP] chunkNumber=%d chunkId=%s encryptedKeyLen=%d", chunkNumber, chunkId, encryptedKey.length()));

                // Decrypt AES key using session private key
                String base64AesKey = keyClient.decryptWithPrivateKey(kp.getPrivate(), encryptedKey);
                log.fine(String.format("[KEY-DECRYPTED] chunkId=%s aesKeyLen=%d", chunkId, base64AesKey.length()));

                // 5) Dispatch async encryption + storage + registration
                CompletableFuture<Snowflake> fut = asyncUploadService.processChunk(chunkId, fileId, originalFilename, chunkNumber, totalChunks, chunkBytes, base64AesKey);
                futures.add(fut);
                log.info(String.format("[CHUNK-QUEUED] fileId=%s chunkNumber=%d chunkId=%s queuedTasks=%d", fileId, chunkNumber, chunkId, futures.size()));

                // Increment for next chunk (0-indexed)
                chunkNumber++;
            }

            // 6) Wait for all chunks to finish
            log.info(String.format("[AWAIT] waiting for %d chunk tasks to complete for fileId=%s", futures.size(), fileId));
            Instant waitStart = Instant.now();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            Duration waitDur = Duration.between(waitStart, Instant.now());
            log.info(String.format("[AWAIT-DONE] completed %d chunk tasks in %d ms for fileId=%s", futures.size(), waitDur.toMillis(), fileId));

            // 7) Complete session on DatabaseNode
            log.info(String.format("[SESSION-COMPLETE] completing sessionId=%s on databaseNode", sessionId));
            Map<String, Object> completeResp = keyClient.completeSession(sessionId);
            log.info(String.format("[SESSION-COMPLETE-RESP] sessionId=%s response=%s", sessionId, (completeResp != null ? completeResp.toString() : "null")));

            Duration totalDur = Duration.between(start, Instant.now());
            Map<String, Object> resp = new HashMap<>();
            resp.put("sessionId", sessionId);
            resp.put("fileId", fileId);
            resp.put("chunks", chunkNumber);
            resp.put("completeResponse", completeResp);
            resp.put("status", "uploaded");
            resp.put("durationMs", totalDur.toMillis());

            log.info(String.format("[UPLOAD-FINISH] fileId=%s chunks=%d totalMs=%d", fileId, chunkNumber, totalDur.toMillis()));
            return ResponseEntity.status(HttpStatus.OK).body(resp);

        } catch (Exception e) {
            log.log(Level.SEVERE, "[UPLOAD-FAILED] upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}