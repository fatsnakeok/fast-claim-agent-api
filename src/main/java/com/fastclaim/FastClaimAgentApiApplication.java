package com.fastclaim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FastClaimAgentApiApplication {
    public static void main(String[] args) {
        // 关闭 Embabel 交互式 Shell，以 Servlet 模式运行 — 否则框架会尝试启动命令行 REPL 导致冲突
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
        SpringApplication.run(FastClaimAgentApiApplication.class, args);
    }
}
