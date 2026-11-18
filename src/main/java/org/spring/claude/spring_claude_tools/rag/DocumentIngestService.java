package org.spring.claude.spring_claude_tools.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文档入库服务：把本地文档解析、切块、向量化后写入 PGVector。
 *
 * <p>支持格式：.md / .txt / .pdf。
 * 切块后的每个 Document 都带上 kb（知识库名）、source（文件名）与
 * chunk_index 元数据；多个知识库共用一张向量表，按 metadata.kb 区分，
 * 检索时用 qa_filter_expression 过滤。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private static final List<String> SUPPORTED = List.of(".md", ".txt", ".pdf");

    /** 未指定知识库时的默认归属。 */
    public static final String DEFAULT_KB = "default";

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;
    private final JdbcTemplate pgJdbc;
    private final String tableName;

    public DocumentIngestService(VectorStore vectorStore, TokenTextSplitter ragTextSplitter,
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource,
            PgVectorStoreProperties properties) {
        this.vectorStore = vectorStore;
        this.splitter = ragTextSplitter;
        this.pgJdbc = new JdbcTemplate(pgVectorDataSource);
        this.tableName = properties.getTableName();
    }

    /** 入库结果统计。 */
    public record IngestResult(String file, int chunks, String status) {}

    /**
     * 入库单个文件到默认知识库。
     *
     * @param filePath 文件绝对或相对路径
     */
    public IngestResult ingestFile(String filePath) {
        return ingestFile(filePath, DEFAULT_KB);
    }

    /**
     * 入库单个文件到指定知识库。
     *
     * @param filePath 文件绝对或相对路径
     * @param kb       知识库名，写入每个块的 metadata.kb
     */
    public IngestResult ingestFile(String filePath, String kb) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return new IngestResult(filePath, 0, "文件不存在");
        }
        try {
            return ingestResource(new FileSystemResource(path), path.getFileName().toString(), kb);
        } catch (Exception e) {
            log.error("入库失败：{}", filePath, e);
            return new IngestResult(filePath, 0, "失败：" + e.getMessage());
        }
    }

    /**
     * 入库任意资源（本地上传走这里）。
     *
     * @param resource 文件内容
     * @param fileName 原始文件名（按后缀选择解析器）
     * @param kb       知识库名
     */
    public IngestResult ingestResource(Resource resource, String fileName, String kb) {
        String name = fileName.toLowerCase();
        if (SUPPORTED.stream().noneMatch(name::endsWith)) {
            return new IngestResult(fileName, 0, "不支持的格式，仅支持 md/txt/pdf");
        }
        String kbName = (kb == null || kb.isBlank()) ? DEFAULT_KB : kb.trim();
        try {
            List<Document> raw = readDocuments(resource, name);
            if (raw.isEmpty()) {
                return new IngestResult(fileName, 0, "解析结果为空");
            }

            // 切块：大文档必须切，否则超出 embedding 模型上下文长度
            List<Document> chunks = splitter.apply(raw);

            // 补充元数据，便于检索后溯源与按知识库过滤
            List<Document> enriched = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Document d = chunks.get(i);
                Map<String, Object> meta = new HashMap<>(d.getMetadata());
                meta.put("kb", kbName);
                meta.put("source", fileName);
                meta.put("chunk_index", i);
                enriched.add(new Document(d.getText(), meta));
            }

            vectorStore.add(enriched);
            log.info("入库成功：{} → kb={}，{} 块", fileName, kbName, enriched.size());
            return new IngestResult(fileName, enriched.size(), "成功");

        } catch (Exception e) {
            log.error("入库失败：{}", fileName, e);
            return new IngestResult(fileName, 0, "失败：" + e.getMessage());
        }
    }

    /**
     * 入库整个目录（不递归）。
     */
    public List<IngestResult> ingestDirectory(String dirPath, String kb) {
        Path dir = Path.of(dirPath);
        if (!Files.isDirectory(dir)) {
            return List.of(new IngestResult(dirPath, 0, "不是有效目录"));
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> ingestFile(p.toString(), kb))
                    .toList();
        } catch (IOException e) {
            log.error("遍历目录失败：{}", dirPath, e);
            return List.of(new IngestResult(dirPath, 0, "目录读取失败：" + e.getMessage()));
        }
    }

    /** 按扩展名选择对应的 DocumentReader。 */
    private List<Document> readDocuments(Resource resource, String fileName) {
        if (fileName.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource).get();
        }

        if (fileName.endsWith(".md")) {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(true)
                    .withIncludeBlockquote(false)
                    .build();
            return new MarkdownDocumentReader(resource, config).get();
        }

        // .txt：直接整篇读入，后续由 splitter 切块。
        // 下载的小说等文本常用 GBK 编码，先按 UTF-8 严格解码，失败则回退 GB18030，
        // 否则乱码文本入库后检索完全失效。
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            return List.of(new Document(decodeText(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文本文件失败：" + fileName, e);
        }
    }

    /** UTF-8 严格解码失败时回退 GB18030（GBK 超集）。 */
    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("GB18030"));
        }
    }

    /** 知识库列表：名称 + 块数（按 metadata.kb 分组统计）。 */
    public List<Map<String, Object>> listKnowledgeBases() {
        return pgJdbc.queryForList(
                "select coalesce(metadata->>'kb', '" + DEFAULT_KB + "') as name,"
                        + " count(*) as chunks from " + tableName + " group by 1 order by 2 desc");
    }

    /** 删除某个文件的全部文档块（可按 kb 限定），返回删除行数。 */
    public int deleteDocument(String source, String kb) {
        if (kb == null || kb.isBlank()) {
            return pgJdbc.update("delete from " + tableName + " where metadata->>'source' = ?",
                    source);
        }
        return pgJdbc.update("delete from " + tableName
                        + " where metadata->>'source' = ? and metadata->>'kb' = ?", source, kb);
    }

    /** 删除指定知识库的全部文档块，返回删除行数。 */
    public int deleteKnowledgeBase(String kb) {
        return pgJdbc.update("delete from " + tableName + " where metadata->>'kb' = ?", kb);
    }

    /** 当前库中已入库的文档块总数（用于前端展示）。 */
    public long count() {
        Long total = pgJdbc.queryForObject("select count(*) from " + tableName, Long.class);
        return total == null ? 0 : total;
    }
}
