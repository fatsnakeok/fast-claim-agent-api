package com.fastclaim.config;

import com.fastclaim.service.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DocumentIngestionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionRunner.class);

    private final DocumentIngestionService ingestionService;

    @Value("${insurance.rag.auto-ingest:true}")
    private boolean autoIngest;

    public DocumentIngestionRunner(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) {
        if (!autoIngest) {
            log.info("auto-ingest 已关闭，跳过启动时文档摄入");
            return;
        }
        log.info("开始启动时文档摄入...");
        int count = ingestionService.ingestAll();
        log.info("文档摄入完成 — 共摄入 {} 篇文档", count);
    }
}
