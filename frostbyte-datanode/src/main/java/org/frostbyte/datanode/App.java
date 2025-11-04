package org.frostbyte.datanode;

/*
 * Frostbyte - Encrypted Distributed Object Storage
 * Copyright (c) 2025 Affan Pathan
 *
 * Licensed for non-commercial use only.
 * Commercial usage requires a separate license.
 * Contact: your-email@example.com
 */


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling

public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}