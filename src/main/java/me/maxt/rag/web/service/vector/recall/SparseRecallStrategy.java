package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 稀疏向量检索策略，使用 Milvus 原生 BM25 分词器。
 * 依赖 Milvus collection 中已有稀疏向量字段 "sparse_vector"，
 * 且已通过 BM25EmbeddingFunction 绑定到 "text" 字段。
 */
public class SparseRecallStrategy implements RecallStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparseRecallStrategy.class);

    private final MilvusClientV2 milvusClient;
    private final String collectionName;

    public SparseRecallStrategy(MilvusClientV2 milvusClient, String collectionName) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
    }

    @Override
    public String name() { return "sparse"; }

    @Override
    public List<EmbeddingMatch<TextSegment>> recall(String query, int topK) {
        try {
            SearchReq req = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new EmbeddedText(query)))
                    .annsField("sparse_vector")
                    .topK(topK)
                    .outputFields(List.of("text", "file_name", "absolute_directory_path"))
                    .build();

            SearchResp resp = milvusClient.search(req);

            List<EmbeddingMatch<TextSegment>> results = new ArrayList<>();
            for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
                for (SearchResp.SearchResult sr : group) {
                    Object textObj = sr.getEntity().get("text");
                    if (textObj == null) continue;
                    String text = textObj.toString();
                    TextSegment seg = TextSegment.from(text);
                    // BM25 稀疏检索无 embedding 向量，EmbeddingMatch.embedding 传 null
                    results.add(new EmbeddingMatch<>((double) sr.getScore(), sr.getId().toString(), null, seg));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Sparse recall failed: {}", e.getMessage());
            return List.of();
        }
    }
}
