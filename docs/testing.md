# 测试约定

- JUnit 5 + Mockito + AssertJ，100 个单元测试（21 个测试类）+ 2 个集成测试（`*IT` 命名约定），Service 层覆盖率 >79%
- **只 mock 系统边界**（EmbeddingModel、ChatModel），不 mock 自己的模块
- 使用真实 `EmbeddingStoreManager` 实例（注入 `InMemoryEmbeddingStore`）
- `EmbeddingStoreManagerMilvusIT`（1 个测试）和 `MultiRecallRouterIT`（2 个测试）用 Testcontainers 启动真实 Milvus，surefire 默认排除
- Controllers 不单独测试（薄胶水层）
- 测试文件在 `src/test/java/me/maxt/rag/web/` 下，与源码结构一一对应（23 个 .java 文件）

## 运行

| 命令 | 用途 |
|------|------|
| `mvn test` | 运行所有单元测试 |
| `mvn test jacoco:report` | 覆盖率报告 → `target/site/jacoco/index.html` |
