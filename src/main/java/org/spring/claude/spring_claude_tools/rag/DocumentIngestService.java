package org.spring.claude.spring_claude_tools.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 * 切块后的每个 Document 都带上 source（文件名）与 chunk_index 元数据，
 * 方便检索后回溯引用来源。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private static final List<String> SUPPORTED = List.of(".md", ".txt", ".pdf");

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public DocumentIngestService(VectorStore vectorStore, TokenTextSplitter ragTextSplitter) {
        this.vectorStore = vectorStore;
        this.splitter = ragTextSplitter;
    }

    /** 入库结果统计。 */
    public record IngestResult(String file, int chunks, String status) {}

    /**
     * 入库单个文件。
     *
     * @param filePath 文件绝对或相对路径
     */
    public IngestResult ingestFile(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return new IngestResult(filePath, 0, "文件不存在");
        }
        String name = path.getFileName().toString().toLowerCase();
        if (SUPPORTED.stream().noneMatch(name::endsWith)) {
            return new IngestResult(filePath, 0, "不支持的格式，仅支持 md/txt/pdf");
        }

        try {
            List<Document> raw = readDocuments(path);
            if (raw.isEmpty()) {
                return new IngestResult(filePath, 0, "解析结果为空");
            }

            // 切块：大文档必须切，否则超出 embedding 模型上下文长度
            List<Document> chunks = splitter.apply(raw);

            // 补充元数据，便于检索后溯源
            List<Document> enriched = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Document d = chunks.get(i);
                Map<String, Object> meta = new HashMap<>(d.getMetadata());
                meta.put("source", path.getFileName().toString());
                meta.put("chunk_index", i);
                enriched.add(new Document(d.getText(), meta));
            }

            vectorStore.add(enriched);
            log.info("入库成功：{}，{} 块", path.getFileName(), enriched.size());
            return new IngestResult(path.getFileName().toString(), enriched.size(), "成功");

        } catch (Exception e) {
            log.error("入库失败：{}", filePath, e);
            return new IngestResult(filePath, 0, "失败：" + e.getMessage());
        }
    }

    /**
     * 入库整个目录（不递归）。
     *
     * @param dirPath 目录路径
     */
    public List<IngestResult> ingestDirectory(String dirPath) {
        Path dir = Path.of(dirPath);
        if (!Files.isDirectory(dir)) {
            return List.of(new IngestResult(dirPath, 0, "不是有效目录"));
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> ingestFile(p.toString()))
                    .toList();
        } catch (IOException e) {
            log.error("遍历目录失败：{}", dirPath, e);
            return List.of(new IngestResult(dirPath, 0, "目录读取失败：" + e.getMessage()));
        }
    }

    /** 按扩展名选择对应的 DocumentReader。 */
    private List<Document> readDocuments(Path path) {
        Resource resource = new FileSystemResource(path);
        String name = path.getFileName().toString().toLowerCase();

        if (name.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource).get();
        }

        if (name.endsWith(".md")) {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(true)
                    .withIncludeBlockquote(false)
                    .build();
            return new MarkdownDocumentReader(resource, config).get();
        }

        // .txt：直接整篇读入，后续由 splitter 切块
        try {
            return List.of(new Document(Files.readString(path)));
        } catch (IOException e) {
            throw new IllegalStateException("读取文本文件失败：" + path, e);
        }
    }

    /** 当前库中已入库的文档块总数（用于前端展示）。 */
    public long count() {
        return vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query("")
                                .topK(1)
                                .build())
                .size();
    }
}
