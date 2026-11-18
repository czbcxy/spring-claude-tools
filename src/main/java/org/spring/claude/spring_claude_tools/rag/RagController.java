package org.spring.claude.spring_claude_tools.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索增强对话与知识库管理接口。
 *
 * <p>多知识库共用一张向量表，以 metadata.kb 区分；问答时按请求里的
 * {@code kb} 字段下发过滤表达式，只检索该知识库的内容。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ChatClient chatClient;
    private final DocumentIngestService ingestService;
    private final VectorStore vectorStore;
    private final QuestionAnswerAdvisor ragAdvisor;
    private final SessionMemoryAdvisor sessionMemoryAdvisor;

    public RagController(ChatClient chatClient,
                         DocumentIngestService ingestService,
                         VectorStore vectorStore,
                         QuestionAnswerAdvisor retrievalAugmentationAdvisor,
                         SessionMemoryAdvisor sessionMemoryAdvisor) {
        this.chatClient = chatClient;
        this.ingestService = ingestService;
        this.vectorStore = vectorStore;
        this.ragAdvisor = retrievalAugmentationAdvisor;
        this.sessionMemoryAdvisor = sessionMemoryAdvisor;
    }

    /** 入库单个文件。kb 缺省归入 default 知识库。 */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam String path,
                                      @RequestParam(required = false) String kb) {
        DocumentIngestService.IngestResult r = ingestService.ingestFile(path, kb);
        return Map.of("file", r.file(), "chunks", r.chunks(), "status", r.status());
    }

    /** 批量入库一个目录。 */
    @PostMapping("/ingest-dir")
    public List<DocumentIngestService.IngestResult> ingestDir(@RequestParam String path,
                                                            @RequestParam(required = false) String kb) {
        return ingestService.ingestDirectory(path, kb);
    }

    /** 浏览器上传文件入库（multipart）。 */
    @PostMapping("/upload")
    public Mono<Map<String, Object>> upload(@RequestPart("file") FilePart file,
                                            @RequestParam(required = false) String kb) {
        String fileName = file.filename();
        if (fileName == null || fileName.isBlank()) {
            return Mono.just(Map.of("file", "unknown", "chunks", 0, "status", "文件名为空"));
        }
        String name = fileName;
        return DataBufferUtils.join(file.content())
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    DocumentIngestService.IngestResult r = ingestService.ingestResource(
                            new ByteArrayResource(bytes), name, kb);
                    return Map.<String, Object>of("file", r.file(), "chunks", r.chunks(),
                            "status", r.status());
                });
    }

    /** 知识库列表：名称 + 块数。 */
    @GetMapping("/kbs")
    public List<Map<String, Object>> knowledgeBases() {
        return ingestService.listKnowledgeBases();
    }

    /** 删除某个文件的全部文档块（source 为入库时的文件名）。 */
    @DeleteMapping("/doc")
    public Map<String, Object> deleteDocument(@RequestParam String source,
                                              @RequestParam(required = false) String kb) {
        return Map.of("source", source, "deleted", ingestService.deleteDocument(source, kb));
    }

    /** 删除指定知识库的全部文档块。 */
    @DeleteMapping("/kb")
    public Map<String, Object> deleteKnowledgeBase(@RequestParam String name) {
        int deleted = ingestService.deleteKnowledgeBase(name);
        return Map.of("name", name, "deleted", deleted);
    }

    /** 知识库规模概览。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("documents", ingestService.count(),
                "kbs", ingestService.listKnowledgeBases());
    }

    /**
     * RAG 流式问答。检索 + 生成一步到位，SSE 逐 token 推送。
     * 与 /api/chat/stream 同构（POST + JSON + 事件流），前端切换 URL 即可。
     * 会话记忆以 "rag-" 前缀隔离，不污染普通对话历史。
     * body.kb 指定知识库时只检索该库的文档块。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody RagChatRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            return Flux.just(event("error", "消息不能为空"));
        }
        String sessionId = (body.sessionId() == null || body.sessionId().isBlank())
                ? "rag-default" : "rag-" + body.sessionId();

        var spec = chatClient.prompt()
                .user(body.message())
                .advisors(ragAdvisor, sessionMemoryAdvisor, SimpleLoggerAdvisor.builder().build())
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId));

        // 指定知识库 → 下发过滤表达式，只在该库内检索
        if (body.kb() != null && !body.kb().isBlank()) {
            spec.advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                    "kb == '" + body.kb().replace("'", "") + "'"));
        }

        Flux<ServerSentEvent<String>> content = spec.stream()
                .content()
                .map(chunk -> event("message", chunk))
                .onErrorResume(ex -> Flux.just(event("error", describe(ex))));

        return content.concatWith(Flux.just(event("done", "[DONE]")))
                .timeout(Duration.ofMinutes(10));
    }

    /**
     * 只做检索、不调模型：用于调试“为什么答不出来”。
     * 返回命中的片段和相似度分数，方便判断是切块问题还是阈值问题。
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                            @RequestParam(defaultValue = "5") int topK,
                                            @RequestParam(required = false) String kb) {
        var request = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK);
        if (kb != null && !kb.isBlank()) {
            request.filterExpression(
                    new org.springframework.ai.vectorstore.filter.FilterExpressionTextParser()
                            .parse("kb == '" + kb.replace("'", "") + "'"));
        }
        return vectorStore.similaritySearch(request.build())
                .stream()
                .map(d -> Map.<String, Object>of(
                        "kb", d.getMetadata().getOrDefault("kb", "default"),
                        "source", d.getMetadata().getOrDefault("source", "unknown"),
                        "chunk_index", d.getMetadata().getOrDefault("chunk_index", -1),
                        "score", d.getMetadata().getOrDefault("distance", 0),
                        "text", d.getText().length() > 300
                                ? d.getText().substring(0, 300) + "..."
                                : d.getText()))
                .toList();
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(data == null ? "" : data)
                .build();
    }

    private String describe(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                String responseBody = responseException.getResponseBodyAsString();
                return "模型调用失败（HTTP " + responseException.getStatusCode().value() + "）："
                        + (responseBody.isBlank() ? responseException.getStatusText() : responseBody);
            }
            current = current.getCause();
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** RAG 问答请求体：kb 为可选知识库名。 */
    public record RagChatRequest(String message, String sessionId, String kb) {
    }
}
