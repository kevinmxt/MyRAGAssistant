package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 稀疏向量检索策略，使用 Milvus 原生 BM25 分词器。
 * 依赖 Milvus collection 中已有稀疏向量字段 "sparse_vector"，
 * 且已通过 BM25EmbeddingFunction 绑定到 "text" 字段。
 *
 * <p>构造函数启动时探测 collection schema：不存在 sparse_vector 字段
 * （或无法探测，如 Milvus 未运行）时自动降级，recall 恒返回空列表。</p>
 */
public class SparseRecallStrategy implements RecallStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparseRecallStrategy.class);

    private static final String SPARSE_FIELD = "sparse_vector";

    private final Supplier<MilvusClientV2> clientSupplier;
    private final String collectionName;
    private final boolean sparseAvailable;

    public SparseRecallStrategy(Supplier<MilvusClientV2> clientSupplier, String collectionName) {
        this.clientSupplier = clientSupplier;
        this.collectionName = collectionName;
        this.sparseAvailable = detectSparseField(clientSupplier.get(), collectionName);
    }

    /** 启动时探测 collection 是否含 sparse_vector 字段（client 为 null 视为不可用） */
    private static boolean detectSparseField(MilvusClientV2 client, String collectionName) {
        if (client == null) {
            return false;
        }
        try {
            DescribeCollectionResp desc = client.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
            boolean hasSparse = desc.getFieldNames() != null && desc.getFieldNames().contains(SPARSE_FIELD);
            if (!hasSparse) {
                log.warn("Collection {} has no {} field, sparse recall will return empty",
                        collectionName, SPARSE_FIELD);
            }
            return hasSparse;
        } catch (Exception e) {
            log.warn("Cannot check {} field of collection {}: {}",
                    SPARSE_FIELD, collectionName, e.getMessage());
            return false;
        }
    }

    @Override
    public String name() { return "sparse"; }

    @Override
    public List<EmbeddingMatch<TextSegment>> recall(String query, int topK) {
        MilvusClientV2 client = clientSupplier.get();
        if (!sparseAvailable || client == null) {
            return List.of();
        }
        try {
            SearchReq req = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new EmbeddedText(query)))
                    .annsField(SPARSE_FIELD)
                    .topK(topK)
                    .outputFields(List.of("text", "file_name", "absolute_directory_path"))
                    .build();

            SearchResp resp = client.search(req);

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
