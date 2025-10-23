package org.frostbyte.clientnode.services;

import org.frostbyte.clientnode.models.Snowflake;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

@Service
public class AsyncUploadService {
    private static final Logger log = Logger.getLogger(AsyncUploadService.class.getName());

    private final configModel config;
    private final KeyClient keyClient;
    private ExecutorService executor;
    private Path storageDir;

    public AsyncUploadService(configModel config, KeyClient keyClient) {
        this.config = config;
        this.keyClient = keyClient;
    }

    @PostConstruct
    public void init() throws Exception {
        int threads = 4;
        try {
            if (config != null && config.getMaxThreadPool() > 0) threads = config.getMaxThreadPool();
        } catch (Exception ignored) {}

        final AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "async-upload-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        };

        executor = Executors.newFixedThreadPool(threads, tf);

        // Use configured folder or default
        String storageFolder = "Test storage";
        if (config != null && config.getSnowflakeStorageFolder() != null && !config.getSnowflakeStorageFolder().isEmpty()) {
            storageFolder = config.getSnowflakeStorageFolder();
        }

        storageDir = Path.of(storageFolder);
        Files.createDirectories(storageDir);
        log.info(String.format("AsyncUploadService initialized. Storage dir=%s threads=%d chunkSize=%d",
                storageDir.toAbsolutePath(), threads, (config != null ? config.getChunkSizeMB() : -1)));
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    public CompletableFuture<Snowflake> processChunk(String chunkId, String fileId, String originalFileName,
                                                     int chunkNumber, int totalChunks,
                                                     byte[] chunkData, String base64AesKey) {
        return CompletableFuture.supplyAsync(() -> {
            if (chunkData == null) {
                log.severe(String.format("[ERROR] chunkData is null for chunkId=%s fileId=%s chunkNumber=%d", chunkId, fileId, chunkNumber));
                throw new IllegalArgumentException("chunkData cannot be null");
            }

            if (chunkId == null || chunkId.isEmpty()) {
                log.severe(String.format("[ERROR] chunkId is null/empty for fileId=%s chunkNumber=%d", fileId, chunkNumber));
                throw new IllegalArgumentException("chunkId cannot be null or empty");
            }

            String threadName = Thread.currentThread().getName();
            Instant overallStart = Instant.now();
            log.info(String.format("[START] chunkProcess thread=%s fileId=%s chunkId=%s chunkNumber=%d totalChunks=%d chunkBytes=%d",
                    threadName, fileId, chunkId, chunkNumber, totalChunks, chunkData.length));

            try {
                // Encrypt
                Instant encStart = Instant.now();
                byte[] encrypted = ChunkEncryptionService.encrypt(chunkData, base64AesKey);
                Duration encDuration = Duration.between(encStart, Instant.now());

                log.fine(String.format("[ENCRYPTED] chunkId=%s encryptedBytes=%d durationMs=%d thread=%s",
                        chunkId, encrypted.length, encDuration.toMillis(), threadName));

                // Create snowflake and write to temp file
                // NOTE: snowflakeUuid is the same as chunkId (from DatabaseNode's KeyController)
                Instant ioStart = Instant.now();
                Snowflake s = new Snowflake(chunkId, fileId, originalFileName, chunkNumber, totalChunks, Instant.now().toEpochMilli(), encrypted);

                File tmp = s.toSnowflakeFile();
                Path dest = storageDir.resolve(fileId + "_" + chunkNumber + ".snowflake");
                Files.move(tmp.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                Duration ioDuration = Duration.between(ioStart, Instant.now());
                log.info(String.format("[STORED] chunkId=%s path=%s size=%d ioMs=%d thread=%s",
                        chunkId, dest.toAbsolutePath(), encrypted.length, ioDuration.toMillis(), threadName));

                // Compute CRC32 of the encrypted data
                CRC32 crc = new CRC32();
                crc.update(encrypted);
                long crcValue = crc.getValue();
                log.fine(String.format("[CRC] chunkId=%s crc=%d thread=%s", chunkId, crcValue, threadName));

                // Register chunk with DatabaseNode via KeyClient
                try {
                    Instant regStart = Instant.now();
                    Map<String, Object> regResp = keyClient.registerChunk(chunkId, fileId, chunkNumber, chunkData.length, Long.toString(crcValue));
                    Duration regDuration = Duration.between(regStart, Instant.now());
                    log.info(String.format("[REGISTERED] chunkId=%s fileId=%s chunkNumber=%d regMs=%d response=%s thread=%s",
                            chunkId, fileId, chunkNumber, regDuration.toMillis(), (regResp != null ? regResp.toString() : "null"), threadName));
                } catch (Exception e) {
                    log.log(Level.SEVERE, String.format("[REGISTER-FAILED] chunkId=%s fileId=%s chunkNumber=%d thread=%s",
                            chunkId, fileId, chunkNumber, threadName), e);
                    throw e;
                }

                Duration overallDur = Duration.between(overallStart, Instant.now());
                log.info(String.format("[END] chunkProcess chunkId=%s fileId=%s chunkNumber=%d totalMs=%d thread=%s",
                        chunkId, fileId, chunkNumber, overallDur.toMillis(), threadName));

                return s;
            } catch (Exception e) {
                log.log(Level.SEVERE, String.format("[ERROR] chunkProcess chunkId=%s fileId=%s chunkNumber=%d thread=%s",
                        chunkId, fileId, chunkNumber, threadName), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }
}
