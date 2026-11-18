package org.spring.claude.spring_claude_tools.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG（检索增强生成）核心配置。
 *
 * <p>整体链路：
 * <pre>
 *   文档 → DocumentReader 解析 → TokenTextSplitter 切块 → EmbeddingModel 向量化
 *        → VectorStore(PGVector) 持久化
 *
 *   用户提问 → EmbeddingModel 向量化问题 → VectorStore 相似度检索 TopK
 *           → 检索片段注入 Prompt → ChatModel 生成答案
 * </pre>
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>本地 Embedding</b>：DeepSeek 没有 embedding 接口，改用 Transformers(ONNX) 本地模型，
 *       零成本、数据不出机器。首次运行会下载模型文件（约 90MB），之后离线可用。</li>
 *   <li><b>PGVector</b>：相比内存版 SimpleVectorStore，支持持久化与 HNSW 索引，
 *       数据量大时检索性能不退化。</li>
 *   <li><b>QuestionAnswerAdvisor</b>：Spring AI 2.0 的检索增强 Advisor，
 *       与 ChatMemory 同属 Advisor 机制，可叠加在同一个 ChatClient 上。</li>
 *   <li><b>独立入口</b>：RAG 不挂在全局 ChatClient 的 defaultAdvisors 上，
 *       而是由 {@code /api/rag/stream} 按需挂载。原因是查天气、查数据库这类
 *       不需要知识库的场景若也走检索，反而会引入无关片段干扰回答。</li>
 * </ul>
 */
@Configuration
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    /** 检索返回的文档块数量：太小漏答案，太大引入噪声。4 是较常见的起点。 */
    public static final int DEFAULT_TOP_K = 6;

    /** 相似度阈值（0~1）：低于此分数的块被过滤，避免无关内容污染上下文。 */
    // MiniLM 对中文的相似度分数整体偏低（实测强相关片段约 0.44），
    // 阈值 0.45 会把正确答案过滤成"未找到"；中文场景 0.30 更实用。
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.30;

    /**
     * 文档切块器。
     *
     * <p>参数含义：
     * <ul>
     *   <li>chunkSize=800：每块约 800 token，能容纳一个完整段落或一小段代码</li>
     *   <li>minChunkSizeChars=350：过短的块会被合并，避免产生无意义的碎片</li>
     *   <li>minChunkLengthToEmbed=5：少于 5 字符的块直接丢弃（空行、标点）</li>
     *   <li>maxNumChunks=10000：单文档最大块数保护，防止超大 PDF 拖垮内存</li>
     *   <li>keepSeparator=true：保留分隔符，避免把代码块或列表切成语义断裂的片段</li>
     * </ul>
     */
    @Bean
    public TokenTextSplitter ragTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * 向量库 Bean：显式使用 PostgreSQL 数据源，覆盖 starter 的自动装配。
     *
     * <p>Spring AI 2.x 的 {@code PgVectorStoreAutoConfiguration} 直接注入应用唯一的
     * {@code JdbcTemplate}（走 @Primary 的 MySQL），其 Properties 也不再支持独立连接参数；
     * 这里手工构建，使用 {@code pgVectorDataSource} 指向 ragdb。自动装配的
     * {@code vectorStore} 带 {@code @ConditionalOnMissingBean}，本 Bean 优先生效。
     */
    @Bean
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource,
                                   EmbeddingModel embeddingModel,
                                   PgVectorStoreProperties properties) {
        return PgVectorStore.builder(new JdbcTemplate(pgVectorDataSource), embeddingModel)
                .schemaName(properties.getSchemaName())
                .idType(properties.getIdType())
                .vectorTableName(properties.getTableName())
                .vectorTableValidationsEnabled(properties.isSchemaValidation())
                .dimensions(properties.getDimensions())
                .distanceType(properties.getDistanceType())
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .indexType(properties.getIndexType())
                .initializeSchema(properties.isInitializeSchema())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
    }

    /**
     * 检索增强 Advisor。
     *
     * <p>工作方式：拦截请求 → 把用户问题向量化 → 去 PGVector 捞 TopK 相似片段
     * → 把片段拼进 Prompt 的上下文 → 交给模型生成。
     *
     * <p>检索为空时的行为：默认过滤器会让模型明确回答"提供的上下文里没有相关信息"，
     * 而不是暴露原始问题后自由发挥（那是幻觉的来源）。
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        log.info("初始化 RAG Advisor：topK={}, similarityThreshold={}",
                DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);

        // 强约束模板：检索为空或上下文不相关时只许说"未找到"，
        // 禁止用模型先验知识补答，否则用户会把幻觉误认为知识库内容。
        // 占位符 {query} / {question_answer_context} 由 advisor 渲染，不可改名。
        String template = """
                请仅根据下面提供的上下文回答问题。
                如果上下文中不包含答案，请直接回答"知识库中未找到相关信息"，
                绝对不要使用你自己的知识补充，也不要编造任何不在上下文中的内容。

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
}
