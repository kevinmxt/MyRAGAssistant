package me.maxt.rag.web.service.vector;

import java.util.List;

/** 查询增强器接口，返回增强后的查询变体列表 */
public interface QueryEnhancer {
    List<String> enhance(String query);
}
