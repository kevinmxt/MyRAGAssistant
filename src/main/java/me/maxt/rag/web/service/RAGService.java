package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.QueryEnhancementRouter;
import shared.Assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 核心服务，负责检索增强生成（RAG）流程的编排。
 *
 * <p>主要功能：</p>
 * <ol>
 *   <li>使用本地 ONNX 嵌入模型（BgeSmallZhV15）将查询向量化</li>
 *   <li>通过内容检索器从向量库中检索最相关的文档片段</li>
 *   <li>将检索到的上下文片段注入 LLM 对话，生成增强后的答案</li>
 * </ol>
 *
 * <p>对话记忆基于 {@link MessageWindowChatMemory}，窗口大小由 {@link RetrievalConfig#getMemorySize()} 配置。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class RAGService {

    private final RetrievalConfig config;
    private final EmbeddingStoreManager storeManager;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final ContentRetriever contentRetriever;
    private final Assistant assistant;
    private final QueryEnhancementRouter enhancementRouter;
    private final QueryEnhancementConfig enhancementConfig;

    /**
     * 创建 RAG 服务实例。
     *
     * @param config       检索配置
     * @param storeManager 嵌入存储管理器
     * @param embeddingModel 嵌入模型（共享实例）
     * @param chatModel    聊天模型（共享实例）
     */
    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel) {
        this(config, storeManager, embeddingModel, chatModel, null, null);
    }

    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel,
                      QueryEnhancementRouter enhancementRouter,
                      QueryEnhancementConfig enhancementConfig) {
        this.config = config;
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.enhancementRouter = enhancementRouter;
        this.enhancementConfig = enhancementConfig;

        this.contentRetriever = storeManager.createContentRetriever(
                embeddingModel, config.getMaxResults(), config.getMinScore());

        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .contentRetriever(contentRetriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(config.getMemorySize()))
                .build();
    }

    /**
     * 根据用户问题生成回答（不含来源引用）。
     *
     * @param query 用户问题
     * @return AI 生成的回答文本
     */
    public String answer(String query) {
        return assistant.answer(query);
    }

    /**
     * 根据用户问题生成回答，并附带检索到的文档来源。
     *
     * <p>该方法会先手动执行向量检索以获取来源信息，再通过 AI 助手生成回答。</p>
     *
     * @param query 用户问题
     * @return 包含回答文本和来源列表的结果对象
     */
    public AnswerWithSources answerWithSources(String query) {
        return answerWithSources(query, null);
    }

    public AnswerWithSources answerWithSources(String query, String enhancementMode) {
        // 解析 enhancement mode
        String mode = enhancementMode;
        if (mode == null && enhancementConfig != null) {
            mode = enhancementConfig.getDefaultEnhancementMode();
        }
        if (mode == null) mode = "none";

        List<Source> sources = new ArrayList<>();

        if (enhancementRouter != null && enhancementConfig != null && enhancementConfig.isQueryEnhancementEnabled()) {
            List<String> queryVariants = enhancementRouter.route(query, mode);

            if (queryVariants.size() == 1) {
                // 单查询变体：直接检索
                sources = searchAndCollect(queryVariants.get(0));
            } else {
                // 多查询变体：分别检索 + RRF 融合
                List<List<EmbeddingMatch<TextSegment>>> matchGroups = new ArrayList<>();
                for (String variant : queryVariants) {
                    Embedding qEmbedding = embeddingModel.embed(variant).content();
                    EmbeddingSearchResult<TextSegment> result = storeManager.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(qEmbedding)
                                    .maxResults(config.getMaxResults() * 2)
                                    .minScore(config.getMinScore())
                                    .build());
                    matchGroups.add(result.matches());
                }
                // RRF 融合：先融合前两组，再与后续组合并
                List<EmbeddingMatch<TextSegment>> fused = enhancementRouter.fuse(
                        matchGroups.get(0), matchGroups.get(1),
                        config.getMaxResults(), enhancementConfig.getRrfK());
                for (int i = 2; i < matchGroups.size(); i++) {
                    fused = enhancementRouter.fuse(
                            fused, matchGroups.get(i),
                            config.getMaxResults(), enhancementConfig.getRrfK());
                }
                sources = fused.stream()
                        .map(this::toSource)
                        .toList();
            }
        } else {
            // 无增强：保持原有逻辑
            sources = searchAndCollect(query);
        }

        String answer = assistant.answer(query);
        return new AnswerWithSources(answer, sources);
    }

    private List<Source> searchAndCollect(String queryText) {
        List<Source> sources = new ArrayList<>();
        Embedding queryEmbedding = embeddingModel.embed(queryText).content();
        EmbeddingSearchResult<TextSegment> result = storeManager.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(config.getMaxResults())
                        .minScore(config.getMinScore())
                        .build());
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            sources.add(toSource(match));
        }
        return sources;
    }

    private Source toSource(EmbeddingMatch<TextSegment> match) {
        String fileName = match.embedded().metadata().getString("absolute_directory_path");
        if (fileName == null) {
            fileName = match.embedded().metadata().getString("file_name");
        } else {
            fileName = fileName + "/" + match.embedded().metadata().getString("file_name");
        }
        if (fileName == null) fileName = "unknown";
        return new Source(fileName, match.embedded().text(), match.score());
    }

    /**
     * 带来源引用的回答结果 DTO。
     */
    public static class AnswerWithSources {
        /** AI 生成的回答文本 */
        public String answer;
        /** 检索到的参考来源列表 */
        public List<Source> sources;

        /**
         * 构造带来源引用的回答结果。
         *
         * @param answer AI 生成的回答文本
         * @param sources 检索到的参考来源列表
         */
        public AnswerWithSources(String answer, List<Source> sources) {
            this.answer = answer;
            this.sources = sources;
        }
    }

    /**
     * 检索来源 DTO，表示一个与查询相关的文档片段。
     */
    public static class Source {
        /** 来源文件名（含路径） */
        public String fileName;
        /** 来源文本内容 */
        public String text;
        /** 相似度分数（0~1） */
        public double score;

        /**
         * 构造检索来源。
         *
         * @param fileName 来源文件名（含路径）
         * @param text 来源文本内容
         * @param score 相似度分数（0~1）
         */
        public Source(String fileName, String text, double score) {
            this.fileName = fileName;
            this.text = text;
            this.score = score;
        }
    }
}
