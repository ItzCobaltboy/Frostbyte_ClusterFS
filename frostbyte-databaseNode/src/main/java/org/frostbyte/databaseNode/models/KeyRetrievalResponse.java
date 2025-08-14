package org.frostbyte.databaseNode.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyRetrievalResponse {
    private UUID chunkId;
    private String encryptedKey; // RSA encrypted AES key
}
