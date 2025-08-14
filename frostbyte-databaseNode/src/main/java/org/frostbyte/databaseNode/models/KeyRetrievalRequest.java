package org.frostbyte.databaseNode.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyRetrievalRequest {
    private UUID chunkId;
    private String publicKey; // Base64 encoded RSA public key from client
}
