# NOTE: This project is still under progress at time of latest commit, below is the System design
## Progress
- Two basic microservices aka DataNode and MasterNode have been updated and tested for registration and heartbeat system, Datanode can send and recieve chunks.
- Elaborate Unit tests for Masternode and datanode have been setup

# ☁️ Frostbyte ClusterFS Plan

## 🔥 Project Summary

A distributed, encrypted, chunked object storage system — S3-style — with fully modular microservices. Written in Java + Spring Boot, deployed via Docker. Every file is streamed, split into chunks, encrypted, and distributed across multiple DataNodes with redundancy and zero-trust pipeline. All files are encrypted as soon as they reach the system using dynamically generated keys, the keys themsselves are stored encrypted in the database, thus employing a zero trust infra architecture. Autoscale friendly, packages into `.jar` for easy deployment and launch, with support for external editable `config.json` and dockerfile for quick and easy deployment. 

---

## 🧱 Node Architecture

### 1. **ClientNode** (Public API Gateway)

* Receives file uploads/download requests
* Streams files to Balancer
* Interfaces with users, initiates sessions
* Pings MasterNode for Balancer IP
* Handles:

    * Upload session start
    * Stream-to-chunk pipeline
    * Download session reconstruction

### 2. **BalancerNode** (Core logic)

* Handles upload/download sessions
* Splits file into chunks
* Allocates chunks to DataNodes with redundancy
* Tracks chunk placement for rollback and downloads
* Coordinates chunk distribution, retry, rollback
* Manages replica logic using Latin rectangle pattern

### 3. **DataNode** (Storage unit)

* Stores chunk binary data
* Responds to chunk fetch requests
* Sends periodic heartbeats to MasterNode
* Rejects uploads if near full
* Can delete chunks if rollback triggered

### 4. **MasterNode** (Coordinator)

* Receives heartbeats from all nodes
* Maintains alive node registry
* Provides ClientNode and BalancerNode with list of alive nodes
* Detects failure via missed heartbeat

### 5. **DatabaseNode** (Metadata DB)

* RDBMS (Postgres or similar)
* Stores:

    * User data
    * File metadata
    * Chunk-to-datanode map
    * Upload/download sessions
    * Encrypted AES keys for chunks

---

## 📦 Key Components

### 📄 Chunk Object

```java
Chunk {
  UUID chunk_id;
  UUID file_id;
  int chunk_number;
  byte[] binaryData; // streamed, not stored in memory long
  String owner;
  boolean encrypted;
  EncryptedAESKey aes_key;
}
```

### 🔐 Encryption Logic

* Every chunk:

    * AES encrypted with a new key
    * AES key encrypted with master key
    * Encrypted AES key stored in DB
* All node-to-node transfers use encrypted streams

---

## 🔁 Upload Flow (Full Streaming)

1. ClientNode → `/upload/start` → get Balancer IP from Master
2. Stream file chunk-by-chunk to Balancer
3. Balancer splits, assigns UUIDs
4. Allocator assigns chunks to nodes using Latin rectangle logic
5. Chunks stored in DataNodes → ACK to Balancer
6. Balancer stores map in DB via DatabaseNode
7. If upload fails, Balancer rolls back stored chunks

## 🔁 Download Flow

1. ClientNode → `/download/start?file_id`
2. Balancer retrieves chunk map from DB
3. Streams chunks in order from DataNodes
4. Streams final reconstructed file back to user

---

## 🧠 Chunk Allocation Logic

* For file with M chunks, and N datanodes:
* Want R replicas, R <= N
* Create R random permutations of length M
* Ensure no two permutations have same element at same index
* Assign accordingly:

```json
"chunk_0": ["Node1", "Node2"],
"chunk_1": ["Node2", "Node3"]
```

---

## 🗄️ Database Schema (RDBMS)

### `users`

* user\_id (PK)
* name, email, password\_hash

### `files`

* file\_id (PK)
* user\_id (FK)
* filename
* created\_at
* total\_chunks

### `chunks`

* chunk\_id (PK)
* file\_id (FK)
* chunk\_number (ordering index)

### `replicas`

* chunk\_id (FK)
* datanode\_id

### `keys`

* chunk\_id (FK)
* encrypted\_aes\_key (Base64/Hex)

### `upload_sessions`

* session\_id (PK)
* file\_id (FK)
* start\_time
* status

---

## 💼 Node-Specific Algorithms

### 🔵 MasterNode

* `registerNode(node_id, type, ip, port)`
* `receiveHeartbeat(node_id, storage_pct, timestamp)`
* `getAliveNodes()` → filter by type

### 🟠 BalancerNode

* `startUploadSession(file_metadata)`
* `allocateChunks(List<chunk>, replicas)`
* `streamChunk(chunk, datanode)`
* `rollback(session_id)`

### 🟣 DataNode

* `onStart()` → register with MasterNode
* `storeChunk(chunk_id, data)`
* `getChunk(chunk_id)`
* `sendHeartbeat()`

### 🟢 ClientNode

* `startUploadSession()` → talks to Master & Balancer
* `streamFileToBalancer()`
* `downloadFileFromChunks()`

### 🟡 DatabaseNode

* `createFileRecord(user_id, filename)`
* `linkChunkToFile()`
* `getChunkMap(file_id)`
* `getChunkLocations(chunk_id)`

---

## 🔐 Security Model

* Chunk data AES-encrypted
* Keys encrypted with master key
* Master key never leaves secure node
* All network transfers encrypted
* Internal REST calls authenticated with internal API key from config

---

## 🐞 Error Handling / Edge Cases

* Upload fails mid-stream → Balancer rollbacks chunks
* DataNode full → returns 507 → Balancer re-assigns
* Node crash → Master marks as dead
* Orphaned chunk cleanup via sweeper job
* Session timeouts (UploadSession TTL)

---

## 🚀 DevOps / Packaging Plan

* Java + Spring Boot
* Maven for builds
* Dockerfile per node:

    * `node.jar`
    * `config.json`
* Releases published to GitHub

---

## 🧾 Next Steps

* [ ] Scaffold Spring Boot projects per node
* [ ] Write `Chunk` class + encrypt/decrypt utils
* [ ] Write allocator service in Balancer
* [ ] Build heartbeat system between nodes
* [ ] Implement chunk upload/download APIs
* [ ] Build session manager
* [ ] Write garbage collector for orphaned chunks
* [ ] Publish first working build
