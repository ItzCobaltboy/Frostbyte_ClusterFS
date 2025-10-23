package org.frostbyte.clientnode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.Duration;
import java.time.Instant;

@Service
public class  KeyClient {
    private static final Logger log = Logger.getLogger(KeyClient.class.getName());
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final configModel config;
    private final MasterNodeDiscoveryService discoveryService;

    public KeyClient(configModel config, MasterNodeDiscoveryService discoveryService) {
        this.config = config;
        this.discoveryService = discoveryService;
    }

    // Generate an ephemeral RSA keypair for client sessions
    public KeyPair generateClientKeyPair() throws NoSuchAlgorithmException {
        Instant start = Instant.now();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        log.fine(String.format("[KEYGEN] generated RSA keypair (public=%d bytes) in %d ms",
                kp.getPublic().getEncoded().length, Duration.between(start, Instant.now()).toMillis()));
        return kp;
    }

    public String publicKeyToBase64(PublicKey pk) {
        String b64 = Base64.getEncoder().encodeToString(pk.getEncoded());
        log.fine(String.format("[KEY-EXPORT] publicKey length=%d base64Len=%d", pk.getEncoded().length, b64.length()));
        return b64;
    }

    // Decrypt a base64-encoded RSA-encrypted string using a private key
    public String decryptWithPrivateKey(PrivateKey privateKey, String encryptedBase64) throws Exception {
        Instant start = Instant.now();
        byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] plain = cipher.doFinal(encrypted);
        String result = new String(plain, StandardCharsets.UTF_8);
        log.fine(String.format("[KEY-DECRYPT] decrypted %d bytes -> %d chars in %d ms",
                encrypted.length, result.length(), Duration.between(start, Instant.now()).toMillis()));
        return result;
    }

    // Request a single encrypted AES key from DatabaseNode's KeyService endpoint
    public Map<String, Object> requestKeyFromKeyService(String clientPublicKey) throws Exception {
        String host = discoveryService.discoverDatabaseNode();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "keys/generate";
        String apiKey = config.getMasterAPIKey();
        return requestKeyFromKeyService(endpoint, apiKey, clientPublicKey);
    }

    // Overload that takes endpoint and apiKey explicitly
    public Map<String, Object> requestKeyFromKeyService(String endpointUrl, String apiKey, String clientPublicKey) throws Exception {
        Instant start = Instant.now();
        log.fine(String.format("[HTTP-KEY-REQ] POST %s (apiKey=%s)", endpointUrl, apiKey != null ? "***" : "(none)"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) headers.set("X-API-Key", apiKey);

        String json = mapper.writeValueAsString(Map.of("publicKey", clientPublicKey));
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        ResponseEntity<String> resp = rest.postForEntity(endpointUrl, entity, String.class);
        int status = resp.getStatusCode().value();
        log.fine(String.format("[HTTP-KEY-RESP] status=%d timeMs=%d bodyLen=%d",
                status, Duration.between(start, Instant.now()).toMillis(), resp.getBody() != null ? resp.getBody().length() : 0));
        if (resp.getStatusCode() != HttpStatus.OK) {
            String msg = "Key service returned status: " + status;
            log.severe("[HTTP-KEY-ERR] " + msg + " body=" + resp.getBody());
            throw new RuntimeException(msg);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
        log.fine("[HTTP-KEY-BODY] " + (body != null ? body.toString() : "null"));
        return body;
    }

    // Initialize upload session on DatabaseNode (/upload/initialize)
    public Map<String, Object> initializeUploadSession(String fileName, long fileSize, int totalChunks) throws Exception {
        String host = discoveryService.discoverDatabaseNode();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "upload/initialize";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getMasterAPIKey() != null) headers.set("X-API-Key", config.getMasterAPIKey());

        String clientNodeId = config.getNodeName() != null ? config.getNodeName() : "clientNode";
        String json = mapper.writeValueAsString(Map.of(
                "fileName", fileName,
                "fileSize", fileSize,
                "totalChunks", totalChunks,
                "clientNodeId", clientNodeId
        ));

        Instant start = Instant.now();
        log.info(String.format("[UPLOAD-INIT] POST %s payloadSize=%d", endpoint, json.length()));
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        ResponseEntity<String> resp = rest.postForEntity(endpoint, entity, String.class);
        int status = resp.getStatusCode().value();
        log.info(String.format("[UPLOAD-INIT-RESP] status=%d timeMs=%d bodyLen=%d", status, Duration.between(start, Instant.now()).toMillis(), resp.getBody() != null ? resp.getBody().length() : 0));
        if (resp.getStatusCode() != HttpStatus.OK) {
            String msg = "Upload initialize returned status: " + status;
            log.severe("[UPLOAD-INIT-ERR] body=" + resp.getBody());
            throw new RuntimeException(msg);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
        log.fine("[UPLOAD-INIT-BODY] " + (body != null ? body.toString() : "null"));
        return body;
    }

    // Register chunk metadata with DatabaseNode (/upload/chunk/register)
    public Map<String, Object> registerChunk(String chunkId, String fileId, int chunkNumber, int chunkSize, String crc32) throws Exception {
        String host = discoveryService.discoverDatabaseNode();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "upload/chunk/register";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getMasterAPIKey() != null) headers.set("X-API-Key", config.getMasterAPIKey());

        // Build DTO similar to ChunkMetadataDTO
        Map<String, Object> dto = Map.of(
                "chunkId", java.util.UUID.fromString(chunkId),
                "fileId", java.util.UUID.fromString(fileId),
                "chunkNumber", chunkNumber,
                "chunkSize", chunkSize,
                "crc32", crc32
        );

        String json = mapper.writeValueAsString(dto);
        Instant start = Instant.now();
        log.fine(String.format("[CHUNK-REGISTER] POST %s payloadSize=%d", endpoint, json.length()));
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        ResponseEntity<String> resp = rest.postForEntity(endpoint, entity, String.class);
        int status = resp.getStatusCode().value();
        log.fine(String.format("[CHUNK-REGISTER-RESP] status=%d timeMs=%d bodyLen=%d", status, Duration.between(start, Instant.now()).toMillis(), resp.getBody() != null ? resp.getBody().length() : 0));
        if (resp.getStatusCode() != HttpStatus.OK) {
            String msg = "Chunk register returned status: " + status;
            log.severe("[CHUNK-REGISTER-ERR] body=" + resp.getBody());
            throw new RuntimeException(msg);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
        log.fine("[CHUNK-REGISTER-BODY] " + (body != null ? body.toString() : "null"));
        return body;
    }

    // Complete session (/upload/session/{sessionId}/complete)
    public Map<String, Object> completeSession(String sessionId) throws Exception {
        String dbNode = discoveryService.discoverDatabaseNode();
        String host = dbNode;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "upload/session/" + sessionId + "/complete";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getMasterAPIKey() != null) headers.set("X-API-Key", config.getMasterAPIKey());

        Instant start = Instant.now();
        log.info(String.format("[SESSION-COMPLETE] POST %s", endpoint));
        HttpEntity<String> entity = new HttpEntity<>("{}", headers);
        ResponseEntity<String> resp = rest.postForEntity(endpoint, entity, String.class);
        int status = resp.getStatusCode().value();
        log.info(String.format("[SESSION-COMPLETE-RESP] status=%d timeMs=%d bodyLen=%d", status, Duration.between(start, Instant.now()).toMillis(), resp.getBody() != null ? resp.getBody().length() : 0));
        if (resp.getStatusCode() != HttpStatus.OK) {
            String msg = "Complete session returned status: " + status;
            log.severe("[SESSION-COMPLETE-ERR] body=" + resp.getBody());
            throw new RuntimeException(msg);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
        log.fine("[SESSION-COMPLETE-BODY] " + (body != null ? body.toString() : "null"));
        return body;
    }

    // Utility to convert a base64-encoded RSA public key (X.509) to PublicKey instance
    public PublicKey base64ToPublicKey(String base64) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }
}
