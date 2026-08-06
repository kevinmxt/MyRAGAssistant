package me.maxt.rag.web.service.evaluation;

/**
 * LLM 答案质量评分结果。
 */
public record QualityScore(int score, String reason) {}
