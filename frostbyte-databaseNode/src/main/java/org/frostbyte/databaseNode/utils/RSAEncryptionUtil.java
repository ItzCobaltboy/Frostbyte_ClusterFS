package org.frostbyte.databaseNode.utils;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

@Component
public class RSAEncryptionUtil {

    private static final Logger log = Logger.getLogger(RSAEncryptionUtil.class.getName());
    private static final String ALGORITHM = "RSA";
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    /**
     * Encrypts a plain text string using an RSA public key
     *
     * @param plainText The text to encrypt (in this case, the AES key)
     * @param publicKeyString Base64 encoded RSA public key
     * @return Base64 encoded encrypted text
     */
    public String encryptWithPublicKey(String plainText, String publicKeyString) throws Exception {
        try {
            // Decode the base64 public key
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);

            // Generate public key from bytes
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            // Initialize cipher with public key
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);

            // Encrypt the plain text
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes());

            // Return as base64 encoded string
            String encrypted = Base64.getEncoder().encodeToString(encryptedBytes);
            log.fine("Successfully encrypted data with RSA public key");

            return encrypted;

        } catch (Exception e) {
            log.severe("Failed to encrypt with RSA public key: " + e.getMessage());
            throw new Exception("RSA encryption failed", e);
        }
    }

    /**
     * Validates if a string is a valid base64 encoded RSA public key
     *
     * @param publicKeyString The public key string to validate
     * @return true if valid, false otherwise
     */
    public boolean isValidPublicKey(String publicKeyString) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            keyFactory.generatePublic(keySpec);
            return true;
        } catch (Exception e) {
            log.warning("Invalid public key format: " + e.getMessage());
            return false;
        }
    }
}