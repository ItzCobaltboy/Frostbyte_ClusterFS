# Frostbyte Cluster File Service

Frostbyte Cluster File Service (Frostbyte for short) is a **fully encrypted** distributed file storage solution designed to provide high availability, scalability, and performance for mid to large-scale application. 

Inspired from AWS S3, Frostbyte offers a simple and intuitive API for storing and retrieving files across a cluster of servers.

> Yes, Frostbyte can safely operate on **non-TLS / unsecured networks** without risking data exposure. [See below](https://github.com/ItzCobaltboy/Frostbyte_ClusterFS/tree/main?tab=readme-ov-file#security-model)

## Features
- **Distributed Storage**: Files are distributed across multiple Datanodes in the cluster to ensure redundancy and high availability.

- **Per Chunk Encryption**: Each file chunk is encrypted individually using AES-256 encryption before being stored, ensuring data security with RSA based key exchange.

- **Security First Design**: Each file is encrypted as soon as it is received at client, minimizing the risk of data exposure or leak.

- **Scalability**: Easily add or remove Datanodes to scale storage capacity and performance as needed.

- **Fault Tolerance**: Automatic replication and recovery mechanisms to handle Datanode failures without data loss.

- **Simple API**: RESTful API for easy integration with applications and services

---

## Getting Started
### Distribution Setup (Recommended)
1. Download the release package from the [GitHub Releases](PLACEHOLDER) along with Java Runtime.
2. Zip file contains 5 folders with 5 jars, configure applications.properties file in each folder as per your cluster setup.
3. Start the Masternode first, followed by DatabaseNode, Datanodes, BalancerNodes, and finally ClientNodes.
    - The Order of starting nodes is important to ensure proper registration and communication.
    - If using multiple servers/Docker, make sure to initialize port forwarding correctly
4. Launch the jar files using the command:
```terminal
java -jar Frostbyte-<NodeType>.jar
```


### Installation from Source

This project uses Maven for dependency management and build automation. Ensure you have Java and Maven installed on your system.

1. Clone the repository:
```terminal
git clone https://github.com/ItzCobaltboy/Frostbyte_ClusterFS
```
2. Navigate to the project directory and build the project using Maven:
```terminal
mvn clean install
```
3. Configure the `application.properties` file in each module as per your cluster setup.
4. CD into each module's target directory and launch the jar files using the command:
```terminal
java -jar Frostbyte-<NodeType>.jar
```
5. Start the nodes in the following order: Masternode, DatabaseNode, Datanodes, BalancerNodes, and ClientNodes.

---

## Architecture
Frostbyte has a microservices-based architecture consisting of the following key components:
- **ClientNode** : The public facing component that handles client requests for file uploads and downloads. It managed file chunking and encryption.
- **BalancerNode** : Distributes incoming file chunks from ClientNodes to available Datanodes based on load and availability. Using Latin Rectangle based allocation algorithm for optimal distribution and high failure tolerance.
- **Datanode** : Responsible for storing encrypted file chunks and handling retrieval requests. Each Datanode maintains its own storage.
- **Masternode** : Centralized management component that maintains Node statuses. Designed to support real-time addition and removal of Nodes.
- **DatabaseNode** : PostgreSQL based database wrapper for securely storing metadata about files, chunks, encryption keys, and their locations across the cluster.

---

## API Doc (End User)
### Upload File
Upload a file to the Frostbyte cluster.  
The file is streamed, encrypted per chunk, and distributed automatically.

#### Endpoint: 
- POST `/public/upload`
#### Request
- **Content-Type:** `multipart/form-data`
#### Form Parameters

| Name        | Type   | Required | Description |
|------------|--------|----------|-------------|
| file       | File   | Yes      | File to upload |
| totalChunks| Number | No       | Optional hint for total chunks (auto-calculated if omitted) |
#### Response
**HTTP 200 OK**
```json
{
  "status": "success",
  "fileId": "uuid",
  "sessionId": "uuid",
  "filename": "example.pdf",
  "totalChunks": 12,
  "durationMs": 4821
}
```
### Download File

Download a file from Frostbyte cluster using its `fileId`

#### Endpoint:
- GET `/public/download/{fileId}`
#### Response
- Content-type: `application/octet_stream`

> For Developers, full API doc is [here](PLACEHOLDER).

---

## Security Model

Frostbyte is designed to safely operate on non-TLS and otherwise unsecured networks due to its encryption-first architecture.

### Core Principles

- All files are streamed, chunked, and encrypted at the **ClientNode** before being transmitted to any other component.
- Each file chunk is encrypted using a **unique AES-256 key**, generated per chunk by the DatabaseNode.
- AES keys are exchanged using **RSA-based key exchange** over HTTP.
- A **new ephemeral RSA key pair** is generated for every upload and download session.
- No node in the cluster other than the ClientNode ever has access to plaintext data.

As a result, the Frostbyte cluster remains cryptographically blind to file contents at all times.

### Threat Model & Mitigations

#### 1. Server Compromise

If one or more DataNodes are compromised:

- All stored chunks are encrypted using unique per-chunk keys.
- By default, no single DataNode stores all chunks of a file.
- Compromising a DataNode does not provide sufficient information to reconstruct file contents.

This significantly raises the cost of data exfiltration, even under partial cluster compromise.

#### 2. Man-in-the-Middle (MITM) Attacks

If the network itself is compromised (e.g. non-TLS, hostile internal network):

- All file data is already encrypted before leaving the ClientNode.
- Intercepted traffic does not reveal plaintext file contents.
- Encryption keys are exchanged using session-scoped RSA key pairs.
- RSA keys are never reused across sessions.

This prevents both passive eavesdropping and key disclosure over the network.


### Trust Boundary Summary

| Component      | Can See Plaintext |
|----------------|------------------|
| ClientNode     | Yes |
| BalancerNode   | No |
| DataNode       | No |
| DatabaseNode   | No |
| Network        | No |

### Out of Scope

Frostbyte does not attempt to actively defend against:
- Malicious or compromised ClientNodes
- Compromised JVM runtimes or host operating systems
- Side-channel or hardware-level attacks

--- 

## Known Issues/Scope for improvement

1) DatabaseNode: Single Point of Failure
   - Currently by design, there exists only one DatabaseNode in the cluster. This leaves DatabaseNode as a single point of failure for complete system.
   - **Suggested Fix**: Implement DatabaseNode replication so all DatabaseNodes maintain identical DB copies constantly, and update the system to handle fallback on multiple data nodes
2) No tolerance for failure mid upload/download
   - Theres no proper error handling or retry logic incase of node failures using active uploads/downloads, currently the request simply returns `HTTP 500: Internal_Server_Error`
