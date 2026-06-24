package com.fastclaim.config;

import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.fastclaim.service.InsuranceKnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    @Value("${insurance.rag.lucene.name:insurance-lucene}")
    private String indexName;

    @Value("${insurance.rag.lucene.chunk-size:1000}")
    private int chunkSize;

    @Value("${insurance.rag.lucene.chunk-overlap:200}")
    private int chunkOverlap;

    /**
     * Lucene BM25 纯文本检索引擎 — 无向量嵌入，100% BM25 权重。
     */
    @Bean
    public LuceneSearchOperations luceneSearchOperations() {
        log.info("初始化 Lucene BM25 检索引擎 — 索引: {}, chunk: {}/{}, 纯文本模式",
                indexName, chunkSize, chunkOverlap);
        return LuceneSearchOperations.builder()
                .withName(indexName)
                .withChunkerConfig(new ContentChunker.Config(chunkSize, chunkOverlap, chunkSize))
                .withChunkTransformer(ChunkTransformer.NO_OP)
                .buildAndLoadChunks();
    }

    /**
     * RAG LlmReference — 将 InsuranceKnowledgeBase 的 @LlmTool 方法暴露给 LLM。
     * LLM 通过 search 工具自主检索保险知识库。
     */
    @Bean
    public LlmReference insuranceRag(InsuranceKnowledgeBase knowledgeBase) {
        log.info("注册 RAG 工具: insurance_docs — LLM 可调用的保险知识库检索");
        return LlmReference.of(
                "insurance_docs",
                "Insurance knowledge base — includes vehicle insurance terms, claims procedures, "
                        + "and FAQ documents. Available as a searchable reference.",
                knowledgeBase
        );
    }
}
