package com.fastclaim.service;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 保险知识库 — 将 Lucene BM25 检索方法通过 @LlmTool 暴露给 LLM。
 * Embabel 框架调用 {@code Tool.fromInstance(this)} 自动提取注解方法生成 Tool。
 */
@Component
public class InsuranceKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(InsuranceKnowledgeBase.class);

    private final LuceneSearchOperations lucene;

    public InsuranceKnowledgeBase(LuceneSearchOperations lucene) {
        this.lucene = lucene;
    }

    /**
     * 全文检索保险知识库。
     *
     * @param query 自然语言搜索查询
     * @return 相关文档块内容，块之间以 "---" 分隔
     */
    @LlmTool(description = "搜索保险知识库文档，包括车险条款、理赔指南、常见问题。"
            + "输入自然语言查询，返回相关文档段落。")
    public String search(String query) {
        log.debug("RAG 搜索 — 查询: {}", query);
        var request = TextSimilaritySearchRequest.create(query, 0.0, 5);
        var results = lucene.textSearch(request, Chunk.class);
        if (results.isEmpty()) {
            return "未找到相关文档内容";
        }
        StringBuilder sb = new StringBuilder();
        for (var r : results) {
            sb.append(r.getMatch().getText()).append("\n---\n");
        }
        log.debug("RAG 搜索 — 返回 {} 条结果", results.size());
        return sb.toString();
    }
}
