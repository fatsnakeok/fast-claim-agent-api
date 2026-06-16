package com.fastclaim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FastClaimAgentApiApplication {
    public static void main(String[] args) {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
        SpringApplication.run(FastClaimAgentApiApplication.class, args);
    }
}
