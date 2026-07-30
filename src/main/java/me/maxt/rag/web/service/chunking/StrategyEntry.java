package me.maxt.rag.web.service.chunking;

import java.util.Map;

public record StrategyEntry(String strategyName, Map<String, Object> params) {}
