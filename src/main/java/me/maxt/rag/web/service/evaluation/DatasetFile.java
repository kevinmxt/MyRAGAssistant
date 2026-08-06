package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * testcases.json 文件的完整内容映射。
 */
public record DatasetFile(
    String format,
    String version,
    String created,
    List<TestCase> testCases
) {}
