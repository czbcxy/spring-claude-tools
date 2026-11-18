# Spring Claude Tools 项目 —— 会话记忆（交接文档）

> 用途：供其他模型/开发者读取，接续本项目开发。
> 生成时间：2026-11-18
> 对应代码状态：本地 `main` = `088d389`（RAG 已提交），其上另有 P0 幻觉抑制 + 前端知识库开关的提交。

---

## 一、项目定位

**项目名**：spring_claude_tools（Spring AI 智能体工具集）
**路径**：`/Users/chengzhengbo/Documents/idea/archive/spring_claude_tools`
**远程仓库**：https://github.com/czbcxy/spring-claude-tools （公开）
**技术栈**：Spring Boot 4.1.0 + Spring AI 2.0 + DeepSeek 模型 + MySQL + Redis + PGVector

这是一个「自带工具能力的 AI 智能体」，支持命令行 REPL 和 Web 双入口。

---

## 二、已完成的功能模块

### 1. 工具链（13 个工具，通过 `register()` 注册）

| 工具类 | 能力 |
|---|---|
| WeatherTools | 实时天气、多日预报 |
| LocalFileTools | 读文件、目录遍历、全文搜索 |
| WriteFileTools | 写文件、追加、版本恢复 |
| MemoryTools | 长期记忆存取（Redis） |
| WebSearchTools | 联网搜索、新闻检索 |
| DatabaseTools | 表结构探查、SQL 查询（默认只读） |
| EnvironmentTools | 时间、系统信息、网络信息、倒计时 |
| HttpTools | 外部 HTTP 接口调用、网页抓取 |
| ScheduleTools | 定时提醒、任务列表、取消任务 |
| DocumentTools | pdf/docx/xlsx/纯文本解析 |
| GithubTools | 仓库信息、搜索、读文件、提交、issue/PR |
| ShellTools | **执行 shell 命令**（`agent.shell.enabled=true` 才启用） |
| UnknownToolFallbackConfiguration | 未知工具兜底 |

### 2. Web 对话界面（提交 `bb31f38`）

- `ChatController`：`/api/chat/stream`（SSE 流式）、`/api/chat/info`（能力清单）
- `index.html`：暗色玻璃拟态聊天页，730 行 / 26KB
- `AgentChatClientConfiguration`：把 ChatClient + 工具链抽成共享 Bean，REPL 与 Web 复用
- 端口 **8800**
- 终端 REPL 默认关闭，需 `--agent.cli.enabled=true` 开启

### 3. 安全改造（提交 `5f9eab2` 时完成）

- **git 历史已彻底重建**，原 5 个含明文密钥的提交不存在于任何地方
- `application-dev.properties` 存放全部明文（API Key、DB 密码、Token），已 gitignore
- `application.properties` 用占位符：`${DEEPSEEK_API_KEY:}` / `${DB_URL:...}` / `${GITHUB_TOKEN:}` 等
- `.gitignore` 已忽略：`application-dev.properties`、`application-local.properties`、`.workbuddy/`、`.devin/`

---

## 三、RAG 改造（**已完成并验证通过，但尚未提交**）

### 3.1 当前未提交的文件

```
 M pom.xml
 M src/main/resources/application.properties
?? src/main/java/.../rag/RagConfiguration.java
?? src/main/java/.../rag/DocumentIngestService.java
?? src/main/java/.../rag/RagController.java
?? src/main/java/.../utils/DataSourceConfiguration.java
```

### 3.2 技术选型与理由

| 环节 | 选型 | 理由 |
|---|---|---|
| Embedding | **本地 Transformers (ONNX)** `all-MiniLM-L6-v2` | **DeepSeek 不提供 embedding 接口**，本地模型零成本、数据不出机器 |
| 向量库 | **PGVector**（HNSW 索引，384 维，COSINE_DISTANCE） | 支持持久化 + HNSW，比 SimpleVectorStore 适合规模增长 |
| 检索注入 | `QuestionAnswerAdvisor` | 与 ChatMemory 同属 Advisor 机制，可叠加 |
| 切块 | `TokenTextSplitter`，chunkSize=800，minChunkSizeChars=350 | 800 token 容纳完整段落 |

### 3.3 关键设计决策

**RAG 不挂在全局 ChatClient 的 defaultAdvisors 上**，而是由 `/api/rag/stream` 按需挂载。
原因：查天气、查数据库这类不需要知识库的请求若也走检索，会引入无关片段干扰回答。

**双数据源隔离**（`DataSourceConfiguration`）：
- 业务库 MySQL 标 `@Primary`，`DatabaseTools` 和自动装配的 `JdbcTemplate` 默认拿 MySQL
- `pgVectorDataSource` 指向 PostgreSQL 的 `ragdb`，由 `RagConfiguration` 按 Bean 名注入
- **注意**：Spring AI 2.x 的 `PgVectorStoreProperties` 已移除 url/username/password 字段，向量库连接必须由应用自己提供 DataSource

### 3.4 接口清单

| 接口 | 说明 |
|---|---|
| `POST /api/rag/ingest?path=` | 入库单文件（md/txt/pdf） |
| `POST /api/rag/ingest-dir?path=` | 批量入库目录（不递归） |
| `GET /api/rag/status` | 知识库规模概览 |
| `GET /api/rag/stream?message=&chatId=` | RAG 流式问答（检索+生成） |
| `GET /api/rag/search?query=&topK=` | **只检索不调模型**，用于调试"为什么答不出来" |

### 3.5 配置项（application.properties 第 64-92 行）

```properties
spring.ai.vectorstore.pgvector.url=jdbc:postgresql://localhost:5432/ragdb
spring.ai.vectorstore.pgvector.username=${PG_USERNAME:chengzhengbo}
spring.ai.vectorstore.pgvector.password=${PG_PASSWORD:}
spring.ai.vectorstore.pgvector.table-name=rag_documents
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=384
spring.ai.vectorstore.pgvector.initialize-schema=true

spring.ai.embedding.transformer.onnx.model-uri=https://media.githubusercontent.com/media/spring-projects/spring-ai/refs/heads/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx
spring.ai.embedding.transformer.tokenizer.uri=https://cdn.jsdelivr.net/gh/spring-projects/spring-ai@main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/tokenizer.json
spring.ai.embedding.transformer.cache.enabled=true
```

**模型下载 URL 是踩坑后的结果**：本机 `github.com` / `raw.githubusercontent.com` 不可达，只有 `media.githubusercontent.com` 可通；tokenizer 走 jsdelivr 镜像。下载一次后由 `ResourceCacheService` 本地缓存。

### 3.6 实测验证结果（已全部跑通）

| 环节 | 结果 |
|---|---|
| 编译 | ✅ `mvn -o compile` EXIT=0 |
| Embedding 模型加载 | ✅ ONNX + tokenizer 下载成功 |
| PGVector 建表 | ✅ `rag_documents` 表初始化，HNSW 索引 |
| 双数据源隔离 | ✅ MySQL 主库 + PGVector 各自连接正常 |
| 文档入库 | ✅ 1 文档 → 4 chunks |
| 语义检索 | ✅ 正确片段可召回（首个查询 rank 1，score 0.456） |
| RAG 问答（库内问题） | ✅ 答出文档独有信息「青鸾(QingLuan)」 |
| RAG 问答（库外问题） | ⚠️ **半对**：前半段正确拒绝作答，**后半段自行用先验知识补充了答案** |
| Netty 启动 | ✅ 端口 8811（测试用，正式端口看配置） |

---

## 四、待办事项（按优先级）

### P0：修幻觉抑制（✅ 已完成）

**问题**：检索为空时，模型会先说"上下文里没有"，然后**自行用训练知识作答**。企业知识库场景下这会让用户误以为答案来自文档，是危险的。

**原因**：`QuestionAnswerAdvisor` 默认 prompt 模板没有强制约束「只能用上下文作答」。

**已完成**：`questionAnswerAdvisor()` 已挂强约束中文 promptTemplate（见下），
实测库外问题（"Java 21 新特性"）只回"知识库中未找到相关信息"，不再补答。

**修复位置**：`RagConfiguration.java` 的 `questionAnswerAdvisor()` 方法，加 `.promptTemplate(...)`：

```java
@Bean
public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
    String template = """
        请仅根据下面提供的上下文回答问题。
        如果上下文中不包含答案，请直接回答"知识库中未找到相关信息"，不要使用你自己的知识补充。
        不要编造任何不在上下文中的内容。

        上下文：
        {question_answer_context}

        问题：{query}
        """;
    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(SearchRequest.builder()
                    .topK(DEFAULT_TOP_K)
                    .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                    .build())
            .promptTemplate(new PromptTemplate(template))
            .build();
}
```

### P1：检索质量

当前 4 个 chunk 的小样本还测不出问题。喂到 50+ 文档后会出现「正确片段排名靠后」：

- **混合检索**：向量 + 关键词 BM25。错误码 `SQLException: 1062` 这类精确查询向量检索不靠谱
- **Rerank 重排序**：召回 20 条 → rerank 模型精排保留 3 条
- **调参**：`DEFAULT_TOP_K=4`、`DEFAULT_SIMILARITY_THRESHOLD=0.45` 目前是经验起点，需实测调整

### P2：评估集

准备 20~30 个「问题 + 标准答案」对，每次调参后跑一遍，看：
- **召回率**：正确片段有没有被找到
- **准确率**：生成的答案对不对

不做评估就是凭感觉调参。

### P3：前端接入（✅ 已完成）

`index.html` 底部栏已加「知识库模式」开关：
- 勾选后请求走 `POST /api/rag/stream`（与 `/api/chat/stream` 同构：JSON body + SSE 事件流）
- 启动时拉 `/api/rag/status` 显示文档条数，接口不可用时开关自动禁用
- RAG 会话记忆以 `rag-` 前缀隔离，不污染普通对话历史

### P4：提交 RAG 代码

```bash
cd /Users/chengzhengbo/Documents/idea/archive/spring_claude_tools
git add -A
git status --short   # 确认 application-dev.properties 不在列表里
git commit -m "feat: 接入 RAG 检索增强（本地 Embedding + PGVector + QuestionAnswerAdvisor）"
export https_proxy=http://127.0.0.1:7890
git push
```

---

## 五、环境与踩坑记录

### 5.1 本机环境

```
OS      : macOS
用户    : chengzhengbo
项目    : /Users/chengzhengbo/Documents/idea/archive/spring_claude_tools
JDK     : (见 systemInfo)
Maven   : 私服 packages.glodon.com，镜像 alimaven
MySQL   : 业务库（localhost）
PostgreSQL : ragdb（localhost:5432，pgvector 扩展已装）
Redis   : ChatMemory 持久化
代理    : http://127.0.0.1:7890
```

### 5.2 坑一：github.com DNS 污染

```
github.com → 57.155.223.240   ← 假 IP，连接超时
api.github.com → 正常
真实 IP（140.82.112.x）直连可通
```

**症状**：`git push` 报 `SSL_ERROR_SYSCALL`

**解决**：
```bash
export https_proxy=http://127.0.0.1:7890
git push
```

**注意**：`git config http.https://github.com.proxy` 这种写法**不生效**，必须用环境变量。

### 5.3 坑二：Maven 私服缺包导致编译失败

**症状**：`spring-boot-starter-webflux` 拉不到，但 jar 实际已在本地 `~/.m2`

**原因**：本地仓库的 `_remote.repositories` 标记来源为 `nexus-aliyun`，但当前 `settings.xml` 里只有 `alimaven` 指向 `packages.glodon.com/artifactory/maven-proxy-aliyun/`。**仓库 id 对不上，Maven 判定「没从当前配置的仓库下载过」**

**解决**：
```bash
# 把 ~/.m2/repository 下相关包的 _remote.repositories 里的 nexus-aliyun 改成 alimaven
```

### 5.4 坑三：REPL 阻塞 Web 启动

**症状**：`CommandLineRunner` 自动启动终端 REPL，Web 模式下日志里不停出现 `> 本尊:` 提示

**解决**：加 `agent.cli.enabled` 开关，默认关闭。想用终端对话加 `--agent.cli.enabled=true`

### 5.5 坑四：ShellTools 不生效

**原因**：`ShellTools` 构造函数的 `@Value("${agent.shell.enabled:false}")` 默认 false，`register()` 里 `isAvailable()` 检查不通过就跳过注册（只打一行日志）

**解决**：`application-dev.properties` 加 `agent.shell.enabled=true`，**重启应用**（工具清单不支持热加载）

### 5.6 坑五：Spring AI 2.x PgVectorStoreProperties 变更

旧版的 `spring.ai.vectorstore.pgvector.url/username/password` 独立建连属性已移除，`PgVectorStoreAutoConfiguration` 直接注入应用唯一的 `JdbcTemplate`（会拿到 `@Primary` 的 MySQL）。所以必须手工构建 `PgVectorStore` Bean 并指定 `pgVectorDataSource`。

### 5.7 坑六：embedding 模型下载 URL

- ~~`spring-ai-all-MiniLM-L6-v2.zip`~~ 已失效
- `github.com` / `raw.githubusercontent.com` 本机不可达
- ✅ 可用：`media.githubusercontent.com/media/...`（ONNX 模型）
- ✅ 可用：`cdn.jsdelivr.net/gh/...`（tokenizer）

---

## 六、给接续开发者的建议

1. **先做 P0 幻觉抑制**，这个改动小、收益直接，是当前 RAG 唯一明确缺陷
2. **别跳过 P2 评估集**，这是 RAG 从"能跑"到"好用"的分水岭
3. **提交前务必检查** `application-dev.properties` 不在 `git ls-files` 里
4. **启动命令**：`mvn spring-boot:run -Dspring-boot.run.profiles=dev`
5. **调试检索**用 `/api/rag/search`，它只检索不调模型，能直接看到 score 和命中文本，比猜"为什么答不出来"高效得多

---

## 七、提交历史

```
088d389  feat: 接入 RAG 检索增强，新增文档入库与语义检索能力
3b680a1  chore: 还原配置文件中文注释，日志级别调整为 info
bb31f38  feat: 新增 Web 对话界面与 SSE 流式接口
5f9eab2  feat: Spring AI 智能体工具集项目初始提交
```

本地 `main` 与 `origin/main` 在 `3b680a1` 时一致，RAG 改造在其之上未提交。

## 多知识库管理（本轮新增）

- 多个知识库共用 `rag_documents` 一张表，以 `metadata.kb` 区分，缺省归入 `default`；旧数据 kb 为空，列表里 coalesce 成 default 展示。
- `DocumentIngestService`：`ingestFile/ingestDirectory/ingestResource` 均接受 kb 参数；`listKnowledgeBases()`/`deleteKnowledgeBase()` 走 JdbcTemplate 直接查/删 metadata。
- `RagController` 新增：`GET /api/rag/kbs`、`DELETE /api/rag/kb?name=`、`POST /api/rag/upload?kb=`（multipart，WebFlux 用 `@RequestPart FilePart` + DataBufferUtils.join → ByteArrayResource；`@RequestParam MultipartFile` 在 WebFlux 下 400）；`/search` 和 `/stream` 支持 `kb` 参数。
- 检索隔离：stream 时把 `qa_filter_expression`（`kb == 'xxx'`）放进 advisor context，`QuestionAnswerAdvisor` 用 `FilterExpressionTextParser` 解析后下发到 SearchRequest。kb 参数拼表达式前会剔除单引号。
- 前端：`index.html` 输入区加"管理"按钮 → 知识库面板（radio 选用/删除/上传），选中库存 `localStorage("rag.kb")`，RAG 模式请求体带 `kb` 字段。
- `application.properties`：`spring.ai.embedding.transformer.cache.directory=${user.home}/.cache/spring-ai-model` 固定缓存目录，否则每次启动随机 temp 目录重复下载 90MB 模型。
- 已验证：上传（中文库名）、按库 search 隔离、kb=test 能答 青鸾 / kb=default 返回"知识库中未找到"、DELETE 清理。
- 注意：curl 测中文 query 必须 `--data-urlencode`，否则服务端拿到的 query 为空导致误判检索失败。
