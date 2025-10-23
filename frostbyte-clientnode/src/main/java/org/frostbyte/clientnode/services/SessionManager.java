package org.frostbyte.clientnode.services;

import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class SessionManager {

    private static final Logger log = Logger.getLogger(SessionManager.class.getName());

    public static class Session {
        private final String fileUuid;
        private final String originalFileName;
        private final int totalChunks;
        private final int chunkSize;
        private final KeyPair keyPair; // ephemeral RSA keypair for this session
        private volatile String sessionAesKey; // base64 AES key assigned to this session (may be null until requested)
        private final long createdAt;

        public Session(String fileUuid, String originalFileName, int totalChunks, int chunkSize, KeyPair keyPair) {
            this.fileUuid = fileUuid;
            this.originalFileName = originalFileName;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.keyPair = keyPair;
            this.createdAt = Instant.now().toEpochMilli();
        }

        public String getFileUuid() { return fileUuid; }
        public String getOriginalFileName() { return originalFileName; }
        public int getTotalChunks() { return totalChunks; }
        public int getChunkSize() { return chunkSize; }
        public KeyPair getKeyPair() { return keyPair; }
        public PublicKey getPublicKey() { return keyPair.getPublic(); }
        public PrivateKey getPrivateKey() { return keyPair.getPrivate(); }
        public String getSessionAesKey() { return sessionAesKey; }
        public void setSessionAesKey(String key) { this.sessionAesKey = key; }
        public long getCreatedAt() { return createdAt; }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void createSession(String fileUuid, String originalFileName, int totalChunks, int chunkSize, KeyPair kp) {
        Session s = new Session(fileUuid, originalFileName, totalChunks, chunkSize, kp);
        sessions.put(fileUuid, s);
        log.info(String.format("[SESSION-CREATE] fileUuid=%s fileName=%s totalChunks=%d chunkSize=%d createdAt=%d",
                fileUuid, originalFileName, totalChunks, chunkSize, s.getCreatedAt()));
    }

    public Session getSession(String fileUuid) {
        Session s = sessions.get(fileUuid);
        if (s != null) {
            log.fine(String.format("[SESSION-GET] fileUuid=%s fileName=%s ageMs=%d",
                    fileUuid, s.getOriginalFileName(), (Instant.now().toEpochMilli() - s.getCreatedAt())));
        } else {
            log.fine(String.format("[SESSION-MISS] fileUuid=%s not found", fileUuid));
        }
        return s;
    }

    public void removeSession(String fileUuid) {
        Session removed = sessions.remove(fileUuid);
        if (removed != null) {
            log.info(String.format("[SESSION-REMOVE] fileUuid=%s fileName=%s createdAt=%d",
                    fileUuid, removed.getOriginalFileName(), removed.getCreatedAt()));
        } else {
            log.warning(String.format("[SESSION-REMOVE-MISS] fileUuid=%s not found", fileUuid));
        }
    }
}
