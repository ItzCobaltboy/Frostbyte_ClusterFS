# BALANCERNODE CONTEXT.md

## Overview

The **BalancerNode** is the core orchestration component of Frostbyte ClusterFS. It sits between the ClientNode (user-facing API gateway) and the DataNodes (distributed storage layer), coordinating chunk distribution, replica management, and retrieval operations.

**Key Responsibility**: Ensure encrypted chunks are distributed across multiple DataNodes with redundancy, using the Latin rectangle allocation pattern to guarantee no two replicas of the same chunk reside on the same DataNode.

---

## Architecture Position

```
User
  ↓
ClientNode (public API, encryption)
  ↓
BalancerNode (chunk distribution, replica coordination) ← YOU ARE HERE
  ↓
DataNodes (storage layer)
  ↑
MasterNode (node registry, heartbeat tracking)
  ↑
DatabaseNode (metadata storage, key management)
```

---

## Core Responsibilities

### 1. **Upload Coordination**
- Accept encrypted chunks from ClientNode (already encrypted with AES-256-GCM)
- Query MasterNode for list of alive DataNodes
- Select N DataNodes for replica placement using Latin rectangle pattern
- Upload chunk to each selected DataNode in parallel
- Register all replica locations with DatabaseNode
- Return success/failure status to ClientNode

### 2. **Download Coordination**
- Accept download request from ClientNode (fileId or chunk-by-chunk)
- Query DatabaseNode for chunk replica locations
- Select optimal DataNode replica (random selection, prefer closest/least loaded)
- Retrieve encrypted chunk from DataNode
- Stream encrypted chunk back to ClientNode

### 3. **Replica Management**
- Implement Latin rectangle allocation algorithm
- Track allocation state across chunks and DataNodes
- Handle DataNode failures during upload (retry with fallback nodes)
- Cleanup orphaned replicas on upload failure

### 4. **Session Management**
- Track upload sessions assigned to this BalancerNode
- Update session status in DatabaseNode
- Handle session timeouts and cleanup

---

## Data Flow

### Upload Flow (Detailed)

```
1. ClientNode → BalancerNode
   POST /balancer/upload/chunk
   Body: {
     sessionId: UUID,
     fileId: UUID,
     chunkId: UUID,
     chunkNumber: int,
     totalChunks: int,
     encryptedChunk: MultipartFile (already encrypted by ClientNode)
   }

2. BalancerNode → MasterNode
   GET /datanode/getAlive
   Headers: X-API-Key
   Response: {
     aliveNodes: [{ host, nodeName }, ...]
   }

3. BalancerNode Internal
   - Run Latin rectangle allocation algorithm
   - Select N DataNodes (default: 3 replicas)
   - Create upload tasks for each DataNode

4. BalancerNode → DataNode (for each replica)
   POST /datanode/upload
   Headers: X-API-Key
   Params: snowflake=<encrypted_chunk_file>
   Response: {
     status: "success",
     snowflakeName: "fileId_chunkNumber.snowflake"
   }

5. BalancerNode → DatabaseNode
   POST /replicas/register/batch
   Headers: X-API-Key
   Body: {
     chunkId: UUID,
     datanodeIds: ["Datanode_1", "Datanode_2", "Datanode_3"]
   }

6. BalancerNode → ClientNode
   Response: {
     status: "success",
     chunkId: UUID,
     replicasCreated: 3,
     datanodes: ["Datanode_1", "Datanode_2", "Datanode_3"]
   }
```

### Download Flow (Detailed)

```
1. ClientNode → BalancerNode
   POST /balancer/download/chunk
   Body: {
     chunkId: UUID
   }

2. BalancerNode → DatabaseNode
   GET /replicas/chunk/{chunkId}
   Headers: X-API-Key
   Response: {
     replicas: [
       { datanodeId: "Datanode_1", status: "AVAILABLE" },
       { datanodeId: "Datanode_2", status: "AVAILABLE" }
     ]
   }

3. BalancerNode Internal
   - Filter replicas by status == AVAILABLE
   - Select random replica (or use load balancing logic)

4. BalancerNode → DataNode
   POST /datanode/download
   Headers: X-API-Key
   Params: snowflake_name=<filename>
   Response: <binary snowflake file stream>

5. BalancerNode → ClientNode
   Response: <binary snowflake stream>
```

---

## API Contracts

### Endpoints to Implement

#### 1. **Upload Endpoint**
```
POST /balancer/upload/chunk
Headers: X-API-Key: <shared-key>
Body (multipart/form-data):
  - sessionId: UUID
  - fileId: UUID
  - chunkId: UUID
  - chunkNumber: int
  - totalChunks: int
  - snowflake: MultipartFile (encrypted chunk)

Response:
  200 OK:
    {
      "status": "success",
      "chunkId": "uuid",
      "replicasCreated": 3,
      "datanodes": ["Datanode_1", "Datanode_2", "Datanode_3"]
    }

  507 Insufficient Storage:
    {
      "error": "Not enough DataNodes available for replication"
    }

  500 Internal Server Error:
    {
      "error": "Failed to upload chunk: <details>"
    }
```

#### 2. **Download Chunk Endpoint**
```
POST /balancer/download/chunk
Headers: X-API-Key: <shared-key>
Body:
  {
    "chunkId": "uuid"
  }

Response:
  200 OK: <binary snowflake file stream>

  404 Not Found:
    {
      "error": "Chunk not found or no available replicas"
    }

  500 Internal Server Error:
    {
      "error": "Failed to retrieve chunk: <details>"
    }
```

#### 3. **Download File Endpoint** (streams all chunks)
```
POST /balancer/download/file
Headers: X-API-Key: <shared-key>
Body:
  {
    "fileId": "uuid"
  }

Response:
  200 OK:
    {
      "fileId": "uuid",
      "fileName": "example.bin",
      "totalChunks": 10,
      "chunks": [
        {
          "chunkId": "uuid",
          "chunkNumber": 0,
          "datanodeId": "Datanode_1"
        },
        ...
      ]
    }
```

#### 4. **Health Check**
```
GET /balancer/health
Response:
  {
    "status": "healthy",
    "service": "BalancerNode",
    "nodeName": "BalancerNode-01",
    "timestamp": 1234567890
  }
```

---

## Latin Rectangle Allocation Pattern

### Algorithm Overview

The Latin rectangle ensures **no two replicas of the same chunk are placed on the same DataNode**, while also balancing load across available nodes.

### Data Structure

```java
// Allocation matrix: chunks × datanodes
// Each cell = number of replicas of that chunk on that datanode
Map<UUID, Map<String, Integer>> allocationMatrix;

// Example:
// ChunkID → { "Datanode_1": 1, "Datanode_2": 0, "Datanode_3": 1 }
```

### Algorithm Steps

```java
public List<String> selectDataNodes(UUID chunkId, List<String> availableDataNodes, int replicaCount) {
    List<String> selected = new ArrayList<>();

    // Get or initialize allocation map for this chunk
    Map<String, Integer> chunkAllocations = allocationMatrix.getOrDefault(chunkId, new HashMap<>());

    // Sort datanodes by:
    // 1. Current replicas of this chunk (ascending, 0 first)
    // 2. Total chunk count across all files (ascending, prefer less loaded)
    List<String> candidates = availableDataNodes.stream()
        .filter(dn -> chunkAllocations.getOrDefault(dn, 0) == 0) // No replica of this chunk yet
        .sorted(Comparator.comparingInt(this::getTotalChunkCount))
        .collect(Collectors.toList());

    // Select first N candidates
    for (int i = 0; i < Math.min(replicaCount, candidates.size()); i++) {
        String datanode = candidates.get(i);
        selected.add(datanode);

        // Update allocation matrix
        chunkAllocations.put(datanode, chunkAllocations.getOrDefault(datanode, 0) + 1);
        incrementTotalChunkCount(datanode);
    }

    allocationMatrix.put(chunkId, chunkAllocations);

    return selected;
}
```

### Edge Cases

1. **Not Enough DataNodes**: If `availableDataNodes.size() < replicaCount`, return all available nodes and log a warning
2. **All Nodes Have Replica**: Should never happen if algorithm is correct, but fallback to random selection with warning
3. **DataNode Failure During Upload**: Remove from allocation matrix and select replacement node

---

## Services Architecture

### 1. **BalancerController**
- REST endpoints for upload/download
- Input validation
- Delegates to services

### 2. **ChunkAllocationService**
- Implements Latin rectangle algorithm
- Maintains allocation matrix (in-memory, could be persisted later)
- Provides `selectDataNodes(chunkId, availableNodes, replicaCount)` method

### 3. **DataNodeClientService**
- HTTP client for DataNode communication
- Methods:
  - `uploadChunk(datanodeHost, snowflakeFile) → boolean`
  - `downloadChunk(datanodeHost, snowflakeName) → byte[]`
  - `checkStorage(datanodeHost) → StorageInfo`

### 4. **DatabaseClientService**
- HTTP client for DatabaseNode communication
- Methods:
  - `registerReplicas(chunkId, List<datanodeIds>) → boolean`
  - `getChunkReplicas(chunkId) → List<ReplicaInfo>`
  - `getFileChunkMap(fileId) → FileMapDTO`

### 5. **MasterNodeClientService**
- HTTP client for MasterNode communication
- Methods:
  - `registerSelf(host, port, nodeName) → boolean`
  - `sendHeartbeat(nodeName) → boolean`
  - `getAliveDataNodes() → List<DataNode>`

### 6. **ReplicaManagementService**
- Orchestrates upload process
- Handles retries and fallback
- Cleanup on failure
- Flow:
  ```java
  public UploadResult uploadChunkWithReplicas(ChunkUploadRequest request) {
      // 1. Get alive DataNodes from MasterNode
      List<String> aliveNodes = masterNodeClient.getAliveDataNodes();

      // 2. Select DataNodes using Latin rectangle
      List<String> selectedNodes = allocationService.selectDataNodes(
          request.getChunkId(), aliveNodes, REPLICA_COUNT);

      // 3. Upload to each DataNode in parallel
      List<CompletableFuture<Boolean>> uploadTasks = selectedNodes.stream()
          .map(node -> CompletableFuture.supplyAsync(() ->
              dataNodeClient.uploadChunk(node, request.getSnowflakeFile())))
          .collect(Collectors.toList());

      // 4. Wait for all uploads
      CompletableFuture.allOf(uploadTasks.toArray(new CompletableFuture[0])).join();

      // 5. Collect successful uploads
      List<String> successfulNodes = IntStream.range(0, uploadTasks.size())
          .filter(i -> uploadTasks.get(i).join())
          .mapToObj(selectedNodes::get)
          .collect(Collectors.toList());

      // 6. Register replicas with DatabaseNode
      if (!successfulNodes.isEmpty()) {
          databaseClient.registerReplicas(request.getChunkId(), successfulNodes);
      }

      // 7. Return result
      return new UploadResult(successfulNodes.size(), successfulNodes);
  }
  ```

### 7. **SessionTrackingService**
- Track sessions assigned to this BalancerNode
- Update session status in DatabaseNode
- Handle timeouts

---

## Configuration Model

```json
{
  "host": "127.0.0.1",
  "port": 7500,
  "nodeName": "BalancerNode-01",
  "masterNodes": ["127.0.0.1:7001"],
  "databaseNodes": ["127.0.0.1:8082"],
  "masterAPIKey": "ABCDEFEG",
  "replicaCount": 3,
  "uploadThreadPoolSize": 10,
  "heartbeatIntervalSeconds": 30,
  "uploadRetryAttempts": 2,
  "uploadTimeoutSeconds": 60
}
```

---

## Integration Points

### With ClientNode

**ClientNode Changes Required:**
Currently, ClientNode stores snowflakes locally in `AsyncUploadService.processChunk()`. This needs to be modified to send to BalancerNode instead.

**Before (current):**
```java
// AsyncUploadService.java:118
Path dest = storageDir.resolve(fileId + "_" + chunkNumber + ".snowflake");
Files.move(tmp.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
```

**After (proposed):**
```java
// AsyncUploadService.java (modified)
// Send to BalancerNode instead of storing locally
String balancerHost = getBalancerNodeFromMaster(); // Query MasterNode
boolean uploadSuccess = balancerClient.uploadChunk(
    balancerHost, sessionId, fileId, chunkId, chunkNumber, totalChunks, snowflakeFile);

if (!uploadSuccess) {
    throw new RuntimeException("Failed to upload chunk to BalancerNode");
}
```

**New Service in ClientNode:**
```java
@Service
public class BalancerClientService {
    public boolean uploadChunk(String balancerHost, UUID sessionId, String fileId,
                              String chunkId, int chunkNumber, int totalChunks,
                              File snowflakeFile) {
        // POST to http://{balancerHost}/balancer/upload/chunk
        // Multipart form data with all metadata + snowflake file
    }
}
```

### With DatabaseNode

**Required APIs (already implemented):**
- `POST /replicas/register/batch` - ✅ Implemented (ReplicaController.java:100)
- `GET /replicas/chunk/{chunkId}` - ✅ Implemented (ReplicaController.java:250)
- `GET /replicas/file/{fileId}/map` - ✅ Implemented (ReplicaController.java:214)

**Session Tracking:**
- `PUT /upload/session/{sessionId}/balancer` - ✅ Implemented (UploadController.java:244)

### With DataNode

**Required APIs (already implemented):**
- `POST /datanode/upload` - ✅ Implemented (Datanode_controller.java:34)
- `POST /datanode/download` - ✅ Implemented (Datanode_controller.java:80)
- `GET /datanode/storage` - ✅ Implemented (Datanode_controller.java:112)

### With MasterNode

**Required APIs (already implemented):**
- `POST /balancer/register` - ✅ Implemented (Masternode_controller.java:60)
- `POST /balancer/heartbeat` - ✅ Implemented (Masternode_controller.java:122)
- `GET /datanode/getAlive` - ✅ Implemented (Masternode_controller.java:145)

---

## Error Handling & Retry Logic

### Upload Errors

1. **DataNode Returns 507 Insufficient Storage**
   - Remove node from available list
   - Select replacement node using Latin rectangle
   - Retry upload

2. **DataNode Upload Fails (network/timeout)**
   - Retry up to `uploadRetryAttempts` times
   - If still fails, select replacement node
   - If no replacement available, log error and continue with partial replicas

3. **All DataNodes Fail**
   - Return 500 error to ClientNode
   - Do NOT register any replicas
   - ClientNode should handle session rollback

4. **DatabaseNode Replica Registration Fails**
   - Attempt cleanup: DELETE from DataNodes
   - Return error to ClientNode

### Download Errors

1. **No Available Replicas**
   - Return 404 to ClientNode

2. **Selected DataNode Fails**
   - Try next available replica (up to 3 attempts)
   - If all fail, return 500 error

3. **DatabaseNode Query Fails**
   - Return 500 error to ClientNode

---

## Testing Strategy

### Unit Tests

1. **ChunkAllocationService**
   - Test Latin rectangle with 3 chunks, 5 DataNodes, 3 replicas
   - Verify no duplicate allocations per chunk
   - Test edge case: 2 DataNodes, 3 replicas needed (should return 2)

2. **DataNodeClientService**
   - Mock HTTP client
   - Test successful upload
   - Test 507 error handling
   - Test timeout handling

3. **ReplicaManagementService**
   - Test full upload flow with mocked dependencies
   - Test partial failure (1 of 3 DataNodes fails)
   - Test complete failure (all DataNodes fail)

### Integration Tests

1. **End-to-End Upload**
   - Start MasterNode, DatabaseNode, 3 DataNodes, BalancerNode
   - Upload 10MB file from ClientNode
   - Verify 3 replicas created per chunk
   - Verify replicas registered in DatabaseNode

2. **End-to-End Download**
   - Upload file
   - Download file
   - Verify byte-for-byte match

3. **Failure Recovery**
   - Start upload
   - Kill 1 DataNode mid-upload
   - Verify BalancerNode selects replacement
   - Verify upload completes successfully

---

## Deployment Checklist

- [ ] Implement BalancerController with upload/download endpoints
- [ ] Implement ChunkAllocationService with Latin rectangle
- [ ] Implement DataNodeClientService for DataNode communication
- [ ] Implement DatabaseClientService for metadata operations
- [ ] Implement MasterNodeClientService for node registry
- [ ] Implement ReplicaManagementService for orchestration
- [ ] Add config.json support via ConfigLoader
- [ ] Add StartupRunner to register with MasterNode on boot
- [ ] Add scheduled heartbeat sender
- [ ] Write unit tests for allocation algorithm
- [ ] Write integration tests for upload/download
- [ ] Update ClientNode to send chunks to BalancerNode
- [ ] Test with 10GB file upload across 5 DataNodes

---

## Performance Considerations

### Parallelization

- **Upload to DataNodes**: Use `CompletableFuture` to upload to all replicas in parallel
- **Download from DataNode**: Single stream (no parallelization needed)

### Thread Pools

- Dedicated thread pool for DataNode uploads (size: `uploadThreadPoolSize`)
- Separate thread pool for heartbeat and health checks

### Caching

- Cache alive DataNode list (refresh every 30s or on failure)
- Cache chunk allocation matrix in-memory (persist to DatabaseNode on shutdown for recovery)

### Monitoring

- Log upload/download latencies
- Track replica distribution across DataNodes
- Alert on under-replicated chunks (< `replicaCount`)
- Track DataNode failures and fallback counts

---

## Future Enhancements

1. **Load Balancing**
   - Prefer DataNodes with lower storage usage
   - Prefer DataNodes with lower network latency

2. **Erasure Coding**
   - Instead of full replication, use Reed-Solomon coding (e.g., 6+3 scheme)
   - Reduce storage overhead from 3x to 1.5x

3. **Chunk Rebalancing**
   - Background job to rebalance chunks when new DataNodes join
   - Ensure even distribution across cluster

4. **Multi-BalancerNode Support**
   - Implement sticky session routing (sessionId → BalancerNode mapping)
   - Handle BalancerNode failure mid-upload

5. **Download Optimization**
   - Smart replica selection based on DataNode proximity/latency
   - Parallel chunk download for large files

6. **Replica Health Monitoring**
   - Periodic checksum verification
   - Auto-heal corrupted replicas

---

## Dependencies

### Maven Dependencies

```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- HTTP Client (for DataNode/DatabaseNode/MasterNode communication) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </dependency>

    <!-- Jackson for JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Quick Reference

### Key Files to Create

```
frostbyte-balancer/
├── src/main/java/org/frostbyte/balancer/
│   ├── controllers/
│   │   └── BalancerController.java
│   ├── services/
│   │   ├── ChunkAllocationService.java
│   │   ├── DataNodeClientService.java
│   │   ├── DatabaseClientService.java
│   │   ├── MasterNodeClientService.java
│   │   ├── ReplicaManagementService.java
│   │   └── SessionTrackingService.java
│   ├── models/
│   │   ├── ChunkUploadRequest.java
│   │   ├── UploadResult.java
│   │   ├── DataNodeInfo.java
│   │   ├── ReplicaInfo.java
│   │   └── configModel.java
│   ├── utils/
│   │   ├── ConfigLoader.java
│   │   └── StartupRunner.java
│   └── BalancerApplication.java
├── src/main/resources/
│   ├── application.properties
│   └── config.json
└── src/test/java/org/frostbyte/balancer/
    ├── ChunkAllocationServiceTest.java
    ├── ReplicaManagementServiceTest.java
    └── BalancerControllerTest.java
```

---

## Summary

The BalancerNode is the **orchestration brain** of Frostbyte ClusterFS. It takes encrypted chunks from ClientNode, distributes them across multiple DataNodes using a smart allocation pattern, and manages replica metadata in DatabaseNode. By implementing the Latin rectangle algorithm, it guarantees fault tolerance and even load distribution across the cluster.

**Next Steps:**
1. Implement `BalancerController` with upload/download endpoints
2. Implement `ChunkAllocationService` with Latin rectangle algorithm
3. Implement HTTP client services for node communication
4. Write comprehensive tests
5. Update ClientNode to integrate with BalancerNode
6. Deploy and test with multi-node cluster
