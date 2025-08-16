package org.frostbyte.databaseNode.services;

import org.frostbyte.databaseNode.models.KeyCreationResponse;
import org.frostbyte.databaseNode.entities.KeyPair;
import org.frostbyte.databaseNode.repositories.ChunkKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;

@Service
public class KeyService {

    private final ChunkKeyRepository chunkKeyRepository;
    private final Logger log = Logger.getLogger(KeyService.class.getName());

    // Spring will automatically provide the repository bean we created earlier
    @Autowired
    public KeyService(ChunkKeyRepository chunkKeyRepository) {
        this.chunkKeyRepository = chunkKeyRepository;
    }

    public KeyCreationResponse generateAndStoreKey() {
        // 1. Generate a new, unique UUID for the chunk.
        UUID chunkId = UUID.randomUUID();

        // 2. Generate a new, cryptographically secure AES-256 key.
        String plainTextKey = generateAesKey();

        log.info("Generated Key for UUID " + chunkId);
        // 4. Create a new ChunkKey entity object to save to the database.
        KeyPair newChunkKey = new KeyPair();
        newChunkKey.setChunkId(chunkId);
        newChunkKey.setKey(plainTextKey);

        // 5. Save the entity to the 'chunk_keys' table using our repository.
        chunkKeyRepository.save(newChunkKey);

        // 6. Return the chunkId and the UNENCRYPTED key for the client to use immediately.
        return new KeyCreationResponse(chunkId, plainTextKey);
    }

    private String generateAesKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256); // for AES-256
            SecretKey secretKey = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            // This should never happen with "AES"
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    public String findKey(UUID chunkId) {
        Optional<KeyPair> pair = chunkKeyRepository.findById(chunkId);

        if (pair.isPresent()) {
            log.info("Re    trieved key for Chunk ID: " + chunkId);
            return pair.get().getKey();
        } else {
            log.info("Key not found for Chunk ID: " + chunkId);
            throw new NoSuchElementException("Key not found for Chunk ID: " + chunkId);
        }
    }

}