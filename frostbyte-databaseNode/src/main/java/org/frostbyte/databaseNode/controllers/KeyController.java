package org.frostbyte.databaseNode.controllers;

import org.frostbyte.databaseNode.models.BatchKeyRetrievalRequest;
import org.frostbyte.databaseNode.models.KeyCreationRequest;
import org.frostbyte.databaseNode.models.KeyCreationResponse;
import org.frostbyte.databaseNode.models.KeyRetrievalRequest;
import org.frostbyte.databaseNode.models.configModel;
import org.frostbyte.databaseNode.services.KeyService;
import org.frostbyte.databaseNode.utils.RSAEncryptionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.logging.Logger;

/*
    * KeyController
    * Dedicated API for handling key generations and retrievals from database
    * Critical endpoint, keep it secure
 */

@RestController
@RequestMapping("/keys")
public class KeyController {

    private final KeyService keyService;
    private final configModel config;
    private final RSAEncryptionUtil rsaUtil;
    private static final Logger log = Logger.getLogger(KeyController.class.getName());
    private static final String API_HEADER = "X-API-Key";

    @Autowired
    public KeyController(KeyService keyService, configModel config, RSAEncryptionUtil rsaUtil) {
        this.keyService = keyService;
        this.config = config;
        this.rsaUtil = rsaUtil;
    }

    /**
     * Generate a new AES key for a chunk
     * Request body should contain the client's public RSA key
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateKey(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody KeyCreationRequest request) {

        // Validate API key
        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized key generation attempt with API key: " + apiKey);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        // Validate request
        if (request.getPublicKey() == null || request.getPublicKey().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Public key is required"));
        }

        try {
            // Generate new key pair (chunk ID + AES key)
            KeyCreationResponse keyPair = keyService.generateAndStoreKey();

            // Encrypt the AES key with client's public RSA key
            String encryptedKey = rsaUtil.encryptWithPublicKey(
                    keyPair.getKey(),
                    request.getPublicKey()
            );

            log.info("Generated and encrypted key for chunk: " + keyPair.getChunkId());

            // Return chunk ID and encrypted key
            return ResponseEntity.ok(Map.of(
                    "chunkId", keyPair.getChunkId(),
                    "encryptedKey", encryptedKey,
                    "status", "success"
            ));

        } catch (Exception e) {
            log.severe("Failed to generate key: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate key: " + e.getMessage()));
        }
    }

    /**
     * Retrieve an existing AES key for a chunk
     * Request body should contain chunk ID and client's public RSA key
     */
    @PostMapping("/retrieve")
    public ResponseEntity<?> retrieveKey(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody KeyRetrievalRequest request) {

        // Validate API key
        if (!isAuthorized(apiKey)) {
            log.warning("Unauthorized key retrieval attempt with API key: " + apiKey);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        // Validate request
        if (request.getChunkId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk ID is required"));
        }

        if (request.getPublicKey() == null || request.getPublicKey().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Public key is required"));
        }

        try {
            // Retrieve the plain text key from database
            String plainTextKey = keyService.findKey(request.getChunkId());

            // Encrypt with client's public RSA key
            String encryptedKey = rsaUtil.encryptWithPublicKey(
                    plainTextKey,
                    request.getPublicKey()
            );

            log.info("Retrieved and encrypted key for chunk: " + request.getChunkId());

            return ResponseEntity.ok(Map.of(
                    "chunkId", request.getChunkId(),
                    "encryptedKey", encryptedKey,
                    "status", "success"
            ));

        } catch (Exception e) {
            log.warning("Failed to retrieve key for chunk " + request.getChunkId() + ": " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Key not found for chunk: " + request.getChunkId()));
        }
    }

    /**
     * Batch retrieve multiple keys
     * Useful for downloading files with multiple chunks
     */
    @PostMapping("/retrieve/batch")
    public ResponseEntity<?> retrieveMultipleKeys(
            @RequestHeader(value = API_HEADER) String apiKey,
            @RequestBody BatchKeyRetrievalRequest request) {

        if (!isAuthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: Invalid API key"));
        }

        if (request.getChunkIds() == null || request.getChunkIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Chunk IDs list is required"));
        }

        if (request.getPublicKey() == null || request.getPublicKey().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Public key is required"));
        }

        Map<String, String> encryptedKeys = new HashMap<>();
        List<UUID> notFound = new ArrayList<>();

        for (UUID chunkId : request.getChunkIds()) {
            try {
                String plainTextKey = keyService.findKey(chunkId);
                String encryptedKey = rsaUtil.encryptWithPublicKey(plainTextKey, request.getPublicKey());
                encryptedKeys.put(chunkId.toString(), encryptedKey);
            } catch (Exception e) {
                notFound.add(chunkId);
                log.warning("Key not found for chunk: " + chunkId);
            }
        }

        log.info("Batch retrieved " + encryptedKeys.size() + " keys, " + notFound.size() + " not found");

        return ResponseEntity.ok(Map.of(
                "encryptedKeys", encryptedKeys,
                "notFound", notFound,
                "retrieved", encryptedKeys.size(),
                "failed", notFound.size()
        ));
    }

    private boolean isAuthorized(String apiKey) {
        return config.getMasterAPIKey().equals(apiKey);
    }
}