package org.frostbyte.masternode.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.frostbyte.masternode.models.nodeType;
import org.frostbyte.masternode.models.registerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test") // This activates TestConfig and deactivates ConfigLoader
public class MasternodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String TEST_API_KEY = "TEST_KEY_123";

    @Test
    void testRegisterDataNode_Success() throws Exception {
        registerRequest request = new registerRequest();
        request.setIp("192.168.1.100:8080");
        request.setNodeName("TestDataNode1");
        request.setNodeType(nodeType.DataNode);

        mockMvc.perform(post("/datanode/register")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("true"));
    }

    @Test
    void testRegisterDataNode_InvalidApiKey() throws Exception {
        registerRequest request = new registerRequest();
        request.setIp("192.168.1.100:8080");
        request.setNodeName("TestDataNode1");
        request.setNodeType(nodeType.DataNode);

        mockMvc.perform(post("/datanode/register")
                        .header("X-API-Key", "WRONG_KEY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}