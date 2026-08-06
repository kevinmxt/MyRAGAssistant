package me.maxt.rag.web.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 src/test/resources/evaluation/<format>/testcases.json 加载测试用例。
 */
public class DatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);

    private static final String RESOURCE_ROOT = "evaluation";

    /**
     * 加载指定格式的测试用例。
     *
     * @param format 格式名（如 "markdown"）
     * @return 解析后的 DatasetFile，或 null（文件不存在或解析失败时）
     */
    public DatasetFile load(String format) {
        String resourcePath = RESOURCE_ROOT + "/" + format + "/testcases.json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("测试用例文件不存在: {}", resourcePath);
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = MAPPER.readValue(in, Map.class);
            return parseDataset(format, root);
        } catch (Exception e) {
            log.error("加载测试用例失败: {} — {}", resourcePath, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private DatasetFile parseDataset(String format, Map<String, Object> root) {
        String version = (String) root.getOrDefault("version", "1.0");
        String created = (String) root.getOrDefault("created", "");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("testCases");
        List<TestCase> testCases = new ArrayList<>();
        if (cases != null) {
            for (Map<String, Object> c : cases) {
                testCases.add(new TestCase(
                    (String) c.get("id"),
                    (String) c.get("query"),
                    (List<String>) c.get("relevantDocs"),
                    (List<String>) c.get("relevantContent"),
                    (String) c.get("expectedAnswer")
                ));
            }
        }
        return new DatasetFile(format, version, created, testCases);
    }

    /**
     * 校验测试用例字段完整性。
     */
    public List<String> validate(DatasetFile dataset) {
        List<String> errors = new ArrayList<>();
        for (TestCase tc : dataset.testCases()) {
            if (tc.id() == null || tc.id().isBlank()) errors.add("缺少 id");
            if (tc.query() == null || tc.query().isBlank()) errors.add(tc.id() + ": 缺少 query");
            if (tc.relevantDocs() == null || tc.relevantDocs().isEmpty())
                errors.add(tc.id() + ": 缺少 relevantDocs");
        }
        return errors;
    }
}
