package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.config.RerankConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.vector.recall.MultiRecallRouter;
import me.maxt.rag.web.service.vector.rerank.Reranker;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索管线：拥有"从 query 到带来源候选列表"的检索编排。
 *
 * <p>唯一入口 {@link #retrieve(String, RetrievalOverrides)}。内部按配置开关选择召回通道
 * （多路召回 → 查询增强 → 朴素稠密检索），统一执行"召回（候选池仅在精排可用时按扩展倍数放大）
 * → 融合 → 精排/截断 → 映射 Source"的后处理。</p>
 *
 * <p>通道自降级契约：通道内部失败不上抛（多路召回 per-strategy 容错；查询增强失败回落原查询）。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class RetrievalPipeline {

    private final Deps deps;

    /**
     * 创建检索管线。
     *
     * @param deps 完整依赖集合，全部字段必非 null
     */
    public RetrievalPipeline(Deps deps) {
        this.deps = deps;
    }

    /**
     * 执行检索编排，返回带来源信息的候选列表。
     *
     * @param query     用户原始问题
     * @param overrides 每次调用的覆盖项，null 字段回退配置默认值；整个参数可为 null
     * @return 映射后的来源列表，无命中返回空列表
     */
    public List<Source> retrieve(String query, RetrievalOverrides overrides) {
        RetrievalOverrides o = overrides != null ? overrides : new RetrievalOverrides(null, null);
        boolean rerankOn = deps.reranker().isAvailable();
        List<EmbeddingMatch<TextSegment>> pool = recallPool(query, o, rerankOn);
        List<EmbeddingMatch<TextSegment>> ranked = postProcess(query, pool, rerankOn);
        return ranked.stream().map(RetrievalPipeline::toSource).toList();
    }

    private List<EmbeddingMatch<TextSegment>> recallPool(String query, RetrievalOverrides o, boolean rerankOn) {
        if (deps.recallConfig().isMultiRecallEnabled()) {
            return recallViaMultiRecall(query, o, rerankOn);
        }
        if (deps.enhancementConfig().isQueryEnhancementEnabled()) {
            return recallViaEnhancement(query, o, rerankOn);
        }
        return denseSearch(query, baseDepth(rerankOn));
    }

    /** M 通道：多路召回。深度 = recallTopK ×（精排可用 ? 扩展倍数 : 1），由管线算好传给 router。 */
    private List<EmbeddingMatch<TextSegment>> recallViaMultiRecall(String query,
            RetrievalOverrides o, boolean rerankOn) {
        int depth = deps.recallConfig().getRecallTopK()
                * (rerankOn ? deps.rerankConfig().getRerankExpansionFactor() : 1);
        List<String> modes = o.recallModes() != null ? o.recallModes()
                : deps.recallConfig().getRecallModes();
        return deps.multiRecallRouter().recall(query, modes, depth);
    }

    /** E 通道：查询增强。单变体直接稠密检索；多变体逐变体检索后 fuseN 一次性融合。 */
    private List<EmbeddingMatch<TextSegment>> recallViaEnhancement(String query,
            RetrievalOverrides o, boolean rerankOn) {
        String mode = o.enhancementMode() != null ? o.enhancementMode()
                : deps.enhancementConfig().getDefaultEnhancementMode();
        if (mode == null) {
            mode = "none";
        }
        List<String> variants = deps.enhancementRouter().route(query, mode);
        int depth = baseDepth(rerankOn);
        if (variants.size() <= 1) {
            return denseSearch(variants.isEmpty() ? query : variants.get(0), depth);
        }
        List<List<EmbeddingMatch<TextSegment>>> groups = new ArrayList<>();
        for (String variant : variants) {
            groups.add(denseSearch(variant, depth));
        }
        return RrfFusion.fuseN(groups, depth, deps.enhancementConfig().getRrfK());
    }

    /** 朴素/增强通道的候选池深度：精排可用时按扩展倍数放大。 */
    private int baseDepth(boolean rerankOn) {
        int base = deps.retrievalConfig().getMaxResults();
        return rerankOn ? base * deps.rerankConfig().getRerankExpansionFactor() : base;
    }

    private List<EmbeddingMatch<TextSegment>> postProcess(String query,
            List<EmbeddingMatch<TextSegment>> pool, boolean rerankOn) {
        if (rerankOn) {
            return deps.reranker().rerank(query, pool, deps.rerankConfig().getRerankTopK());
        }
        return pool.stream().limit(finalTopK()).toList();
    }

    /** 精排不可用时的终裁条数：多路召回用 recallTopK，其余用 maxResults。 */
    private int finalTopK() {
        return deps.recallConfig().isMultiRecallEnabled()
                ? deps.recallConfig().getRecallTopK()
                : deps.retrievalConfig().getMaxResults();
    }

    private List<EmbeddingMatch<TextSegment>> denseSearch(String text, int maxResults) {
        Embedding qe = deps.embeddingModel().embed(text).content();
        EmbeddingSearchResult<TextSegment> result = deps.storeManager().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(qe)
                        .maxResults(maxResults)
                        .minScore(deps.retrievalConfig().getMinScore())
                        .build());
        return result.matches();
    }

    private static Source toSource(EmbeddingMatch<TextSegment> match) {
        String dir = match.embedded().metadata().getString("absolute_directory_path");
        String name = match.embedded().metadata().getString("file_name");
        String fileName = dir != null && name != null ? dir + "/" + name : (name != null ? name : "unknown");
        return new Source(fileName, match.embedded().text(), match.score());
    }

    /** 每次调用的覆盖项，null 字段回退配置默认值。 */
    public record RetrievalOverrides(String enhancementMode, List<String> recallModes) {
    }

    /** 检索来源 DTO：文件名（含路径）、片段文本、相似度分数。 */
    public record Source(String fileName, String text, double score) {
    }

    /** 完整依赖集合，全部必非 null；"关闭/不可用"由协作者自身表达。 */
    public record Deps(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                       RetrievalConfig retrievalConfig,
                       QueryEnhancementRouter enhancementRouter, QueryEnhancementConfig enhancementConfig,
                       MultiRecallRouter multiRecallRouter, RecallConfig recallConfig,
                       Reranker reranker, RerankConfig rerankConfig) {
    }
}
