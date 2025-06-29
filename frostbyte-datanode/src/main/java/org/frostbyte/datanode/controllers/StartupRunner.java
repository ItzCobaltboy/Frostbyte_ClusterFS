package org.frostbyte.datanode.controllers;

import org.frostbyte.datanode.models.configModel;
import org.frostbyte.datanode.utils.MasterNodeCommunicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import org.frostbyte.datanode.utils.FolderSizeChecker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements CommandLineRunner {

    private final configModel config;
    private final MasterNodeCommunicator communicator;
    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    @Autowired
    public StartupRunner(configModel config, MasterNodeCommunicator communicator) {
        this.config = config;
        this.communicator = communicator;
    }

    @Override
    public void run(String... args) throws IOException{

        try {
            log.info("Starting Frostbyte Cluster Data Node Controller");

            log.info("Loaded configuration as follows: | Host: {} | Port: {} | Chunks Location: {} | Storage Size(GB): {}", config.getHost(), config.getPort(), config.getSnowflakeFolder(), config.getSize());

            Path chunkFolder = Paths.get(config.getSnowflakeFolder());
            Files.createDirectories(chunkFolder);
            log.info("Created chunk folder Successfully: {}", chunkFolder);

            float fillSpace = FolderSizeChecker.getFolderSize(chunkFolder);
            float fillPercentage = fillSpace / (float) config.getSize();

            log.info("Total Space occupied {} GB | %{}", fillSpace, fillPercentage * 100);

            communicator.registerWithMasters();
        } catch (IOException e) {
            throw new IOException(e);
        }

    }

    @Scheduled(fixedRate = 30000)
    public void ping(){
        communicator.pingMasters();
    }

}
