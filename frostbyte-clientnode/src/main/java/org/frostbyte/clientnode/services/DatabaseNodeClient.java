package org.frostbyte.clientnode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.clientnode.models.configModel;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Client for DatabaseNode endpoints related to file metadata and downloads
 */
@Service
public class DatabaseNodeClient {
    private static final Logger log = Logger.getLogger(DatabaseNodeClient.class.getName());
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final configModel config;
    private final MasterNodeDiscoveryService discoveryService;

    public DatabaseNodeClient(configModel config, MasterNodeDiscoveryService discoveryService) {
        this.config = config;
        this.discoveryService = discoveryService;
    }

    /**
     * Get file metadata from DatabaseNode (/upload/file/{fileId})
     * Used to check if file exists and get basic file information
     *
     * @param fileId The UUID of the file to query
     * @return Map containing file metadata (fileId, fileName, fileSize, totalChunks, uploadStatus)
     * @throws FileNotFoundException if file doesn't exist (404)
     * @throws RuntimeException for other errors
     */
    public Map<String, Object> getFileMetadata(String fileId) throws Exception {
        String host = discoveryService.discoverDatabaseNode();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "upload/file/" + fileId;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getMasterAPIKey() != null) {
            headers.set("X-API-Key", config.getMasterAPIKey());
        }

        Instant start = Instant.now();
        log.info(String.format("[FILE-METADATA-REQ] GET %s fileId=%s", endpoint, fileId));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = rest.exchange(endpoint, HttpMethod.GET, entity, String.class);
            int status = resp.getStatusCode().value();
            log.info(String.format("[FILE-METADATA-RESP] status=%d timeMs=%d bodyLen=%d",
                    status, Duration.between(start, Instant.now()).toMillis(),
                    resp.getBody() != null ? resp.getBody().length() : 0));

            if (resp.getStatusCode() != HttpStatus.OK) {
                String msg = "File metadata query returned status: " + status;
                log.severe("[FILE-METADATA-ERR] " + msg + " body=" + resp.getBody());
                throw new RuntimeException(msg);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
            log.fine("[FILE-METADATA-BODY] " + (body != null ? body.toString() : "null"));
            return body;

        } catch (HttpClientErrorException.NotFound e) {
            log.warning(String.format("[FILE-NOT-FOUND] fileId=%s", fileId));
            throw new FileNotFoundException("File not found: " + fileId);
        } catch (HttpClientErrorException e) {
            log.severe(String.format("[FILE-METADATA-HTTP-ERR] status=%d body=%s",
                    e.getStatusCode().value(), e.getResponseBodyAsString()));
            throw new RuntimeException("Failed to query file metadata: " + e.getMessage());
        }
    }

    /**
     * Get file chunk map with replica locations (/replicas/file/{fileId}/map)
     * Used for downloading - provides chunk order and DataNode locations
     *
     * @param fileId The UUID of the file to query
     * @return Map containing fileMap with chunks and replica locations
     * @throws FileNotFoundException if file doesn't exist (404)
     * @throws RuntimeException for other errors
     */
    public Map<String, Object> getFileChunkMap(String fileId) throws Exception {
        String host = discoveryService.discoverDatabaseNode();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        String endpoint = host + (host.endsWith("/") ? "" : "/") + "replicas/file/" + fileId + "/map";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getMasterAPIKey() != null) {
            headers.set("X-API-Key", config.getMasterAPIKey());
        }

        Instant start = Instant.now();
        log.info(String.format("[FILE-CHUNK-MAP-REQ] GET %s fileId=%s", endpoint, fileId));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = rest.exchange(endpoint, HttpMethod.GET, entity, String.class);
            int status = resp.getStatusCode().value();
            log.info(String.format("[FILE-CHUNK-MAP-RESP] status=%d timeMs=%d bodyLen=%d",
                    status, Duration.between(start, Instant.now()).toMillis(),
                    resp.getBody() != null ? resp.getBody().length() : 0));

            if (resp.getStatusCode() != HttpStatus.OK) {
                String msg = "File chunk map query returned status: " + status;
                log.severe("[FILE-CHUNK-MAP-ERR] " + msg + " body=" + resp.getBody());
                throw new RuntimeException(msg);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(resp.getBody(), Map.class);
            log.fine("[FILE-CHUNK-MAP-BODY] " + (body != null ? body.toString() : "null"));
            return body;

        } catch (HttpClientErrorException.NotFound e) {
            log.warning(String.format("[FILE-CHUNK-MAP-NOT-FOUND] fileId=%s", fileId));
            throw new FileNotFoundException("File chunk map not found: " + fileId);
        } catch (HttpClientErrorException e) {
            log.severe(String.format("[FILE-CHUNK-MAP-HTTP-ERR] status=%d body=%s",
                    e.getStatusCode().value(), e.getResponseBodyAsString()));
            throw new RuntimeException("Failed to query file chunk map: " + e.getMessage());
        }
    }

    /**
     * Custom exception for file not found errors
     */
    public static class FileNotFoundException extends Exception {
        public FileNotFoundException(String message) {
            super(message);
        }
    }
}
