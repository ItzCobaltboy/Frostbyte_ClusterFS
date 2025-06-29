package org.frostbyte.datanode.utils;

import org.frostbyte.datanode.models.configModel;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.logging.Logger;

@Component
public class MasterNodeCommunicator {

    private final configModel config;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Logger log = Logger.getLogger(getClass().getName());

    public MasterNodeCommunicator(configModel config) {
        this.config = config;
    }

    public void registerWithMasters() {
        for (String node : config.getMasterNodes()) {
            try {
                String url = "http://" + node + "/masternode/register";
                Map<String, String> body = Map.of(
                        "host", config.getHost(),
                        "port", String.valueOf(config.getPort()),
                        "nodeName", config.getNodeName()
                );

                restTemplate.postForEntity(url, body, String.class);
                log.info("Registered with " + node);

            } catch (Exception e) {
                log.warning("Failed to register with " + node + ": " + e.getMessage());
            }
        }
    }

    public void pingMasters() {
        for (String node : config.getMasterNodes()) {
            try {
                String url = "http://" + node + "/masternode/ping";
                Map<String, String> body = Map.of(
                        "host", config.getHost(),
                        "port", String.valueOf(config.getPort()),
                        "nodeName", config.getNodeName()
                );

                restTemplate.postForEntity(url, body, String.class);
                log.info("Pinged " + node);

            } catch (Exception e) {
                log.warning("Ping failed for " + node + ": " + e.getMessage());
            }
        }
    }
}
