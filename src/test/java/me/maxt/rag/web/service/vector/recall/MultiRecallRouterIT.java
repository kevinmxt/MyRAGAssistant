package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.config.RecallConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多路召回编排集成测试：使用真实 {@link AppConfig} 配置（无 mock），
 * 验证 {@link MultiRecallRouter} 的路由编排与降级行为。
 *
 * <p>不需要真实 Milvus/LightRAG，策略以匿名类替身注入，只测编排逻辑。</p>
 *
 * <p>仅通过 {@code mvn test -Dtest=MultiRecallRouterIT} 显式运行，
 * 默认 surefire 模式不会执行 *IT 类（与 EmbeddingStoreManagerMilvusIT 约定一致）。</p>
 */
class MultiRecallRouterIT {

    /** 返回固定空结果的 dense 替身 */
    private RecallStrategy denseMock() {
        return new RecallStrategy() {
            @Override
            public String name() {
                return "dense";
            }

            @Override
            public List<EmbeddingMatch<TextSegment>> recall(String q, int topK) {
                return List.of();
            }
        };
    }

    /** 返回一条真实结果的 dense 替身 */
    private RecallStrategy denseWithResult() {
        return new RecallStrategy() {
            @Override
            public String name() {
                return "dense";
            }

            @Override
            public List<EmbeddingMatch<TextSegment>> recall(String q, int topK) {
                return List.of(new EmbeddingMatch<>(0.9, "dense-1", null, TextSegment.from("dense result")));
            }
        };
    }

    /** 直接抛异常的 sparse 替身，模拟不可用时的降级场景 */
    private RecallStrategy brokenSparse() {
        return new RecallStrategy() {
            @Override
            public String name() {
                return "sparse";
            }

            @Override
            public List<EmbeddingMatch<TextSegment>> recall(String q, int topK) {
                throw new RuntimeException("sparse unavailable");
            }
        };
    }

    @Test
    void shouldFallbackWhenOnlyDenseAvailable() {
        RecallConfig config = AppConfig.load();

        // 只注册 dense，验证路由编排逻辑
        MultiRecallRouter router = new MultiRecallRouter(config,
                Map.of("dense", denseMock()));
        List<EmbeddingMatch<TextSegment>> result = router.recall("test", List.of("dense"));

        assertThat(result).isEmpty(); // mock 返回空
    }

    @Test
    void shouldSkipNonExistentStrategies() {
        RecallConfig config = AppConfig.load();
        MultiRecallRouter router = new MultiRecallRouter(config, Map.of());

        // 请求不存在的策略，应该安全返回空
        List<EmbeddingMatch<TextSegment>> result = router.recall("test", List.of("nonexistent"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldDegradeGracefullyWhenStrategyFails() {
        RecallConfig config = AppConfig.load();
        MultiRecallRouter router = new MultiRecallRouter(config,
                Map.of("dense", denseWithResult(), "sparse", brokenSparse()));

        // sparse 异常不影响 dense，降级后仍返回 dense 结果
        List<EmbeddingMatch<TextSegment>> result = router.recall("test", List.of("dense", "sparse"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).embedded().text()).isEqualTo("dense result");
    }
}
