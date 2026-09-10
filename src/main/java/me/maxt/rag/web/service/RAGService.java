package me.maxt.rag.web.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import shared.Assistant;

import java.util.List;

/**
 * RAG 对话门面：检索委托 {@link RetrievalPipeline}（唯一事实源），
 * 将检索结果组装为"参考资料 + 问题"的提示词交给 LLM，并附带多轮对话记忆。
 *
 * <p>LLM 看到的上下文与返回给调用方的 sources 恒等。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class RAGService {

    private static final String PROMPT_TEMPLATE = """
            请根据以下参考资料回答问题。若参考资料不足以回答，请如实说明。

            参考资料：
            %s

            问题：%s""";

    private final RetrievalPipeline pipeline;
    private final Assistant assistant;

    /**
     * @param pipeline  检索管线（唯一事实源）
     * @param chatModel 聊天模型
     * @param config    检索配置（取记忆窗口大小）
     */
    public RAGService(RetrievalPipeline pipeline, ChatModel chatModel, RetrievalConfig config) {
        this.pipeline = pipeline;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(config.getMemorySize()))
                .build();
    }

    /** 根据用户问题生成回答，并附带检索到的文档来源（全部覆盖项回退配置默认值）。 */
    public AnswerWithSources answerWithSources(String query) {
        return answerWithSources(query, null);
    }

    /** 根据用户问题生成回答，并附带检索到的文档来源（覆盖查询增强模式）。 */
    public AnswerWithSources answerWithSources(String query, String enhancementMode) {
        return answerWithSources(query, enhancementMode, null);
    }

    /**
     * 根据用户问题生成回答，并附带检索到的文档来源。
     *
     * @param query           用户问题
     * @param enhancementMode 查询增强模式（可选，null 时使用配置默认值）
     * @param recallModes     多路召回模式列表（可选，null 时使用配置默认模式）
     * @return 包含回答文本和来源列表的结果对象
     */
    public AnswerWithSources answerWithSources(String query, String enhancementMode, List<String> recallModes) {
        List<RetrievalPipeline.Source> sources =
                pipeline.retrieve(query, new RetrievalPipeline.RetrievalOverrides(enhancementMode, recallModes));
        String answer = assistant.answer(composePrompt(query, sources));
        return new AnswerWithSources(answer, sources);
    }

    private String composePrompt(String query, List<RetrievalPipeline.Source> sources) {
        StringBuilder materials = new StringBuilder();
        if (sources.isEmpty()) {
            materials.append("（无参考资料）");
        } else {
            for (int i = 0; i < sources.size(); i++) {
                materials.append("[").append(i + 1).append("] ").append(sources.get(i).text()).append("\n");
            }
        }
        return PROMPT_TEMPLATE.formatted(materials, query);
    }

    /** 带来源引用的回答结果 DTO。 */
    public static class AnswerWithSources {
        /** AI 生成的回答文本 */
        public String answer;
        /** 检索到的参考来源列表 */
        public List<RetrievalPipeline.Source> sources;

        /**
         * 构造带来源引用的回答结果。
         *
         * @param answer AI 生成的回答文本
         * @param sources 检索到的参考来源列表
         */
        public AnswerWithSources(String answer, List<RetrievalPipeline.Source> sources) {
            this.answer = answer;
            this.sources = sources;
        }
    }
}
