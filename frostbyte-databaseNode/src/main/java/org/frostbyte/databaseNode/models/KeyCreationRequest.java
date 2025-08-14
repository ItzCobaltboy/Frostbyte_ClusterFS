package org.frostbyte.databaseNode.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.UUID;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyCreationRequest {
    private String publicKey; // Base64 encoded RSA public key from client
}


