package org.frostbyte.databaseNode.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchKeyRetrievalRequest {
    private List<UUID> chunkIds;  // List of chunk IDs to retrieve keys for
    private String publicKey;      // Base64 encoded RSA public key from client
}