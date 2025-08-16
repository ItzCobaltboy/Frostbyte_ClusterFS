package org.frostbyte.clientnode.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

@Data
@NoArgsConstructor
public class Snowflake {

    private String snowflakeUuid;
    private String fileUuid;
    private String originalFileName;
    private int chunkNumber;
    private int totalChunks;
    private long createdOn;
    private long crcChecksum; // CRC of encryptedData
    private byte[] encryptedData;

    private static final ObjectMapper mapper = new ObjectMapper();

    // -------------------- Constructor --------------------
    public Snowflake(String snowflakeUuid, String fileUuid, String originalFileName,
                     int chunkNumber, int totalChunks, long createdOn, byte[] encryptedData) {
        this.snowflakeUuid = snowflakeUuid;
        this.fileUuid = fileUuid;
        this.originalFileName = originalFileName;
        this.chunkNumber = chunkNumber;
        this.totalChunks = totalChunks;
        this.createdOn = createdOn;
        this.encryptedData = encryptedData;
        this.crcChecksum = calculateCRC(encryptedData);
    }

    // -------------------- CRC --------------------
    private long calculateCRC(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // -------------------- Serialize to .snowflake File --------------------
    public File toSnowflakeFile() throws IOException {
        String metaJson = mapper.writeValueAsString(toMetaMap());
        byte[] metaBytes = metaJson.getBytes("UTF-8");

        ByteBuffer header = ByteBuffer.allocate(8);
        header.putLong(metaBytes.length); // first 8 bytes = metadata length

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(header.array());
        baos.write(metaBytes);
        baos.write(encryptedData);

        File tempFile = File.createTempFile(snowflakeUuid, ".snowflake");
        Files.write(tempFile.toPath(), baos.toByteArray());
        return tempFile;
    }

    // -------------------- Deserialize from .snowflake File --------------------
    public static Snowflake fromSnowflakeFile(File file) throws IOException {
        byte[] allBytes = Files.readAllBytes(file.toPath());
        ByteBuffer buffer = ByteBuffer.wrap(allBytes);

        long metaLength = buffer.getLong();

        byte[] metaBytes = new byte[(int) metaLength];
        buffer.get(metaBytes);

        byte[] dataBytes = new byte[buffer.remaining()];
        buffer.get(dataBytes);

        @SuppressWarnings("unchecked")
        Map<String, Object> metaMap = mapper.readValue(metaBytes, Map.class);

        Snowflake s = new Snowflake(
                (String) metaMap.get("snowflakeUuid"),
                (String) metaMap.get("fileUuid"),
                (String) metaMap.get("originalFileName"),
                (Integer) metaMap.get("chunkNumber"),
                (Integer) metaMap.get("totalChunks"),
                ((Number) metaMap.get("createdOn")).longValue(),
                dataBytes
        );

        long storedCRC = ((Number) metaMap.get("crcChecksum")).longValue();
        if (s.calculateCRC(dataBytes) != storedCRC)
            throw new IOException("CRC mismatch! File may be corrupted.");

        return s;
    }

    private Map<String, Object> toMetaMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("snowflakeUuid", snowflakeUuid);
        map.put("fileUuid", fileUuid);
        map.put("originalFileName", originalFileName);
        map.put("chunkNumber", chunkNumber);
        map.put("totalChunks", totalChunks);
        map.put("createdOn", createdOn);
        map.put("crcChecksum", crcChecksum);
        return map;
    }
}
