# 测试约定

- JUnit 5 + Mockito + AssertJ，78 个单元测试，Service 层覆盖率 >79%
- **只 mock 系统边界**（EmbeddingModel、ChatModel），不 mock 自己的模块
- 使用真实 `EmbeddingStoreManager` 实例（注入 `InMemoryEmbeddingStore`）
- `EmbeddingStoreManagerMilvusIT` 集成测试用 Testcontainers 启动真实 Milvus，surefire 默认排除（*IT 命名约定）
- Controllers 不单独测试（薄胶水层）
- 测试文件在 `src/test/java/me/maxt/rag/web/` 下，与源码结构一一对应

## 运行

| 命令 | 用途 |
|------|------|
| `mvn test` | 运行所有单元测试 |
| `mvn test jacoco:report` | 覆盖率报告 → `target/site/jacoco/index.html` |
