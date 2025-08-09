package org.frostbyte.datanode.services;

import org.frostbyte.datanode.models.configModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DatanodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private configModel config;

    @TempDir
    Path tempDir;

    private final String TEST_API_KEY = "TEST_KEY_123";

    @BeforeEach
    void setUp() {
        // Override the snowflake folder to use temp directory for each test
        config.setSnowflakeFolder(tempDir.toString());
    }

    @Test
    void testUploadSnowflake_Success() throws Exception {

        Path targetDir = Paths.get(System.getProperty("user.dir"), "target", "test-chunks");
        Files.createDirectories(targetDir);
        config.setSnowflakeFolder(targetDir.toString());


        MockMultipartFile file = new MockMultipartFile(
                "snowflake",
                "test-chunk.snow",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "Test snowflake content".getBytes()
        );

        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.snowflakeName").value("test-chunk.snow"));

        // Verify file was saved
        Path uploadedFile = targetDir.resolve("test-chunk.snow");
        assertTrue(Files.exists(uploadedFile));
    }

    @Test
    void testUploadSnowflake_InvalidApiKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "snowflake",
                "test.snow",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "Test content".getBytes()
        );

        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", "WRONG_KEY"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid API key."));
    }

    @Test
    void testUploadSnowflake_EmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "snowflake",
                "empty.snow",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("No file uploaded."));
    }

    @Test
    void testUploadSnowflake_DuplicateFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "snowflake",
                "duplicate.snow",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                new byte[1000]
        );

        Path targetDir = Paths.get(System.getProperty("user.dir"), "target", "test-chunks");
        Files.createDirectories(targetDir);
        config.setSnowflakeFolder(targetDir.toString());


        // Upload once
        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isOk());

        // Upload again - should fail
        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("snowflake with same name already exists"));
    }

    @Test
    void testUploadSnowflake_InsufficientStorage() throws Exception {
        // Set storage to 0 to simulate full disk
        config.setSize(0);

        MockMultipartFile file = new MockMultipartFile(
                "snowflake",
                "large.snow",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "Content".getBytes()
        );

        mockMvc.perform(multipart("/datanode/upload")
                        .file(file)
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Space insufficient on node"));

        // Reset size for other tests
        config.setSize(100);
    }

    @Test
    void testDownloadSnowflake_Success() throws Exception {
        // Create a test file
        Path testFile = tempDir.resolve("download-test.snow");
        Files.write(testFile, "Test content for download".getBytes());

        mockMvc.perform(post("/datanode/download")
                        .header("X-API-Key", TEST_API_KEY)
                        .param("snowflake_name", "download-test.snow"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("Test content for download".getBytes()))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"download-test.snow\""));
    }

    @Test
    void testDownloadSnowflake_NotFound() throws Exception {
        mockMvc.perform(post("/datanode/download")
                        .header("X-API-Key", TEST_API_KEY)
                        .param("snowflake_name", "nonexistent.snow"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("snowflake not found."));
    }

    @Test
    void testGetStorage() throws Exception {
        // Create a small test file
        Path testFile = tempDir.resolve("storage-test.snow");
        Files.write(testFile, new byte[1024]); // 1KB file

        mockMvc.perform(get("/datanode/storage")
                        .header("X-API-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUsedGB").exists())
                .andExpect(jsonPath("$.fillPercent").exists());
    }
}