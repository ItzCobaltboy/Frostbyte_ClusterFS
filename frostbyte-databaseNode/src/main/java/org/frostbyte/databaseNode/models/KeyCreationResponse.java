package org.frostbyte.databaseNode.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyCreationResponse {
    private UUID chunkId;
    private String plainTextKey; // The unencrypted key for the client to use
}