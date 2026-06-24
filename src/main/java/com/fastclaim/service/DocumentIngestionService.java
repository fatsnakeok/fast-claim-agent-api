package com.fastclaim.service;

import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.MaterializedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final LuceneSearchOperations luceneSearchOperations;
    private final TikaHierarchicalContentReader contentReader;

    @Value("${insurance.rag.documents-path:classpath:documents/}")
    private String documentsPath;

    public DocumentIngestionService(LuceneSearchOperations luceneSearchOperations) {
        this.luceneSearchOperations = luceneSearchOperations;
        this.contentReader = new TikaHierarchicalContentReader();
    }

    /**
     * 全量摄入 — 清空索引后重新加载所有 Markdown 文档。
     * @return 摄入文档数
     */
    public int ingestAll() {
        log.info("开始全量文档摄入 — 路径: {}", documentsPath);
        long start = System.currentTimeMillis();
        luceneSearchOperations.clear();

        int count = 0;
        try {
            Resource[] resources = loadDocuments();
            for (Resource resource : resources) {
                ingestResource(resource);
                count++;
            }
        } catch (IOException e) {
            log.error("文档摄入失败: {}", e.getMessage(), e);
            throw new RuntimeException("文档摄入失败", e);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("全量文档摄入完成 — 共摄入 {} 篇文档，耗时 {}ms", count, elapsed);
        return count;
    }

    /**
     * 增量摄入 — 仅摄入尚未索引的文档。
     * @return 新摄入文档数
     */
    public int ingestDelta() {
        log.info("开始增量文档摄入 — 路径: {}", documentsPath);

        Set<String> indexedDocs = new HashSet<>();
        // 通过 ContentRoot 查询已索引的文档，对比文件名判断是否需要增量摄入
        int count = 0;

        try {
            Resource[] resources = loadDocuments();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && !indexedDocs.contains(filename)) {
                    ingestResource(resource);
                    count++;
                }
            }
        } catch (IOException e) {
            log.error("增量文档摄入失败: {}", e.getMessage(), e);
            throw new RuntimeException("增量文档摄入失败", e);
        }

        log.info("增量文档摄入完成 — 新摄入 {} 篇文档", count);
        return count;
    }

    /**
     * 单文档摄入。
     * @param fileName 文件名（如 "claims_guide.md"）
     * @return 摄入文档数（0 或 1）
     */
    public int ingestDocument(String fileName) {
        String location = documentsPath.replace("classpath:", "") + fileName;
        try {
            Resource resource = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:" + location);
            if (!resource.exists()) {
                log.warn("文档不存在: {}", location);
                return 0;
            }
            ingestResource(resource);
            log.info("单文档摄入完成: {}", fileName);
            return 1;
        } catch (IOException e) {
            log.error("单文档摄入失败 — {}: {}", fileName, e.getMessage(), e);
            return 0;
        }
    }

    private void ingestResource(Resource resource) throws IOException {
        String filename = resource.getFilename();
        log.debug("摄入文档: {}", filename);
        MaterializedDocument doc = contentReader.parseContent(resource.getInputStream(), filename);
        luceneSearchOperations.writeAndChunkDocument(doc);
    }

    private Resource[] loadDocuments() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return resolver.getResources(documentsPath + "*.md");
    }
}
