package com.dvarahq.agentworks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class DvaraAgentWorksApplication {

    public static void main(String[] args) {
        SpringApplication.run(DvaraAgentWorksApplication.class, args);
    }

}
