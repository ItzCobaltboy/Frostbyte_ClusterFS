package org.frostbyte.balancer.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.balancer.models.DataNodeInfo;
import org.frostbyte.balancer.models.configModel;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Service for handling chunk downloads with replica selection and failover
 */
@Service
public class DownloadService {

    private static final Logger log = Logger.getLogger(DownloadService.class.getName());
    private final configModel config;
    private final DatabaseNodeService databaseNodeService;
    private final DataNodeService dataNodeService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Random random;

    public DownloadService(configModel config,
                           DatabaseNodeService databaseNodeService,
                           DataNodeService dataNodeService) {
        this.config = config;
        this.databaseNodeService = databaseNodeService;
        this.dataNodeService = dataNodeService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.random = new Random();
    }

    /**
     * Download a chunk with automatic replica selection and failover
     *
     * @param fileId UUID of the file
     * @param chunkId UUID of the chunk
     * @param chunkNumber Sequence number of the chunk
     * @return Encrypted snowflake bytes (validated)
     * @throws ChunkDownloadException if download fails
     */
    public byte[] downloadChunk(String fileId, String chunkId, int chunkNumber) throws ChunkDownloadException {
        log.info(String.format("[CHUNK-DOWNLOAD-REQ] fileId=%s chunkId=%s chunkNumber=%d",
                fileId, chunkId, chunkNumber));

        // Step 1: Query DatabaseNode for chunk replica locations
        List<ReplicaInfo> replicas = getChunkReplicas(chunkId);

        if (replicas.isEmpty()) {
            throw new ChunkDownloadException("No replicas found for chunk: " + chunkId);
        }

        log.info(String.format("[REPLICA-QUERY] chunkId=%s totalReplicas=%d", chunkId, replicas.size()));

        // Step 2: Filter available replicas (status = AVAILABLE, DataNode alive)
        List<ReplicaInfo> availableReplicas = filterAvailableReplicas(replicas);

        if (availableReplicas.isEmpty()) {
            throw new ChunkDownloadException("No available replicas for chunk: " + chunkId +
                    " (all replicas are dead or failed)");
        }

        log.info(String.format("[REPLICA-FILTER] chunkId=%s availableReplicas=%d", chunkId, availableReplicas.size()));

        // Step 3: Try downloading from replicas with failover
        String snowflakeName = fileId + "_" + chunkNumber + ".snowflake";
        List<String> failedNodes = new ArrayList<>();

        log.info(String.format("[SNOWFLAKE-NAME-CONSTRUCTED] fileId=%s chunkNumber=%d snowflakeName=%s",
                fileId, chunkNumber, snowflakeName));

        // Shuffle replicas for random selection
        Collections.shuffle(availableReplicas, random);

        for (ReplicaInfo replica : availableReplicas) {
            try {
                log.info(String.format("[REPLICA-SELECTED] chunkId=%s datanodeId=%s snowflakeName=%s attempt=%d/%d",
                        chunkId, replica.getDatanodeId(), snowflakeName, failedNodes.size() + 1, availableReplicas.size()));

                // Step 4: Download snowflake from DataNode
                byte[] snowflakeBytes = downloadSnowflakeFromDataNode(replica.getDatanodeId(), snowflakeName);

                // Step 5: Parse snowflake and validate CRC32
                SnowflakeData snowflakeData = parseSnowflake(snowflakeBytes);

                // Step 6: Validate CRC32 checksum
                if (validateCRC32(snowflakeData)) {
                    log.info(String.format("[CHUNK-DOWNLOAD-SUCCESS] chunkId=%s datanodeId=%s snowflakeSize=%d crcValid=true",
                            chunkId, replica.getDatanodeId(), snowflakeBytes.length));
                    return snowflakeBytes;
                } else {
                    // CRC mismatch - try next replica
                    log.warning(String.format("[CRC-MISMATCH] chunkId=%s datanodeId=%s expectedCrc=%s",
                            chunkId, replica.getDatanodeId(), snowflakeData.getCrcChecksum()));
                    failedNodes.add(replica.getDatanodeId() + " (CRC mismatch)");
                }

            } catch (Exception e) {
                log.warning(String.format("[REPLICA-DOWNLOAD-FAILED] chunkId=%s datanodeId=%s error=%s",
                        chunkId, replica.getDatanodeId(), e.getMessage()));
                failedNodes.add(replica.getDatanodeId() + " (" + e.getMessage() + ")");
            }
        }

        // All replicas failed
        throw new ChunkDownloadException(
                String.format("Failed to download chunk %s from all replicas. Failed nodes: %s",
                        chunkId, String.join(", ", failedNodes)));
    }

    /**
     * Query DatabaseNode for chunk replica locations
     */
    private List<ReplicaInfo> getChunkReplicas(String chunkId) throws ChunkDownloadException {
        String dbNodeUrl = databaseNodeService.fetchDatabaseNodeUrl();
        if (dbNodeUrl == null) {
            throw new ChunkDownloadException("No DatabaseNode available");
        }

        String endpoint = dbNodeUrl + "/replicas/chunk/" + chunkId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", config.getMasterAPIKey());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object replicasObj = response.getBody().get("replicas");
                if (replicasObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> replicasList = (List<Map<String, Object>>) replicasObj;

                    return replicasList.stream()
                            .map(map -> {
                                ReplicaInfo info = new ReplicaInfo();
                                info.setDatanodeId(map.get("datanodeId").toString());
                                info.setStatus(map.get("status").toString());
                                return info;
                            })
                            .collect(Collectors.toList());
                }
            }

            throw new ChunkDownloadException("Invalid response from DatabaseNode");

        } catch (HttpClientErrorException.NotFound e) {
            throw new ChunkDownloadException("Chunk not found in database: " + chunkId);
        } catch (Exception e) {
            throw new ChunkDownloadException("Failed to query chunk replicas: " + e.getMessage());
        }
    }

    /**
     * Filter replicas to only include AVAILABLE status and alive DataNodes
     */
    private List<ReplicaInfo> filterAvailableReplicas(List<ReplicaInfo> replicas) {
        // Get list of alive DataNodes from MasterNode
        List<DataNodeInfo> aliveNodes = dataNodeService.fetchAvailableDataNodes();
        Set<String> aliveNodeHosts = aliveNodes.stream()
                .map(DataNodeInfo::getHost)
                .collect(Collectors.toSet());

        return replicas.stream()
                .filter(replica -> "AVAILABLE".equalsIgnoreCase(replica.getStatus()))
                .filter(replica -> aliveNodeHosts.contains(replica.getDatanodeId()))
                .collect(Collectors.toList());
    }

    /**
     * Download snowflake binary from DataNode
     */
    private byte[] downloadSnowflakeFromDataNode(String datanodeId, String snowflakeName) throws Exception {
        String url = "http://" + datanodeId + "/datanode/download?snowflake_name=" + snowflakeName;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", config.getMasterAPIKey());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.fine(String.format("[DATANODE-DOWNLOAD-REQ] url=%s snowflakeName=%s", url, snowflakeName));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                byte[].class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new Exception("DataNode returned status: " + response.getStatusCode());
        }

        log.fine(String.format("[DATANODE-DOWNLOAD-RESP] datanodeId=%s snowflakeSize=%d",
                datanodeId, response.getBody().length));

        return response.getBody();
    }

    /**
     * Parse snowflake binary format
     * Format: [8-byte metadata length][JSON metadata][encrypted data]
     */
    private SnowflakeData parseSnowflake(byte[] snowflakeBytes) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(snowflakeBytes);
             DataInputStream dis = new DataInputStream(bais)) {

            // Read 8-byte metadata length
            long metadataLength = dis.readLong();

            if (metadataLength <= 0 || metadataLength > snowflakeBytes.length - 8) {
                throw new Exception("Invalid snowflake format: metadataLength=" + metadataLength);
            }

            // Read JSON metadata
            byte[] metadataBytes = new byte[(int) metadataLength];
            dis.readFully(metadataBytes);
            String metadataJson = new String(metadataBytes, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, Map.class);

            // Read encrypted data (remaining bytes)
            byte[] encryptedData = new byte[snowflakeBytes.length - 8 - (int) metadataLength];
            dis.readFully(encryptedData);

            SnowflakeData snowflakeData = new SnowflakeData();
            snowflakeData.setMetadata(metadata);
            snowflakeData.setEncryptedData(encryptedData);
            snowflakeData.setCrcChecksum(metadata.get("crcChecksum").toString());

            log.fine(String.format("[SNOWFLAKE-PARSED] metadataLen=%d encryptedDataLen=%d crc=%s",
                    metadataLength, encryptedData.length, snowflakeData.getCrcChecksum()));

            return snowflakeData;

        } catch (Exception e) {
            throw new Exception("Failed to parse snowflake: " + e.getMessage());
        }
    }

    /**
     * Validate CRC32 checksum of encrypted data
     */
    private boolean validateCRC32(SnowflakeData snowflakeData) {
        try {
            CRC32 crc32 = new CRC32();
            crc32.update(snowflakeData.getEncryptedData());
            long computedCrc = crc32.getValue();

            // Parse expected CRC as long (metadata stores it as a number)
            long expectedCrc = Long.parseLong(snowflakeData.getCrcChecksum());

            boolean valid = (computedCrc == expectedCrc);

            log.fine(String.format("[CRC-VALIDATION] expectedCrc=%d computedCrc=%d valid=%s",
                    expectedCrc, computedCrc, valid));

            return valid;

        } catch (Exception e) {
            log.warning("[CRC-VALIDATION-ERROR] " + e.getMessage());
            return false;
        }
    }

    // Inner classes for data structures

    public static class ReplicaInfo {
        private String datanodeId;
        private String status;

        public String getDatanodeId() {
            return datanodeId;
        }

        public void setDatanodeId(String datanodeId) {
            this.datanodeId = datanodeId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class SnowflakeData {
        private Map<String, Object> metadata;
        private byte[] encryptedData;
        private String crcChecksum;

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public byte[] getEncryptedData() {
            return encryptedData;
        }

        public void setEncryptedData(byte[] encryptedData) {
            this.encryptedData = encryptedData;
        }

        public String getCrcChecksum() {
            return crcChecksum;
        }

        public void setCrcChecksum(String crcChecksum) {
            this.crcChecksum = crcChecksum;
        }
    }

    public static class ChunkDownloadException extends Exception {
        public ChunkDownloadException(String message) {
            super(message);
        }
    }
}
