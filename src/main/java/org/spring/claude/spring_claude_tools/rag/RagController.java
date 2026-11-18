package org.spring.claude.spring_claude_tools.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索增强对话接口。
 *
 * <p>与 {@code /api/chat/stream} 的区别：这里的对话会自动先去知识库检索相关片段，
 * 再基于检索结果作答；没有命中知识库时会明确说明，而不是自由发挥。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ChatClient chatClient;
    private final DocumentIngestService ingestService;
    private final VectorStore vectorStore;
    private final QuestionAnswerAdvisor ragAdvisor;

    public RagController(ChatClient chatClient,
                         DocumentIngestService ingestService,
                         VectorStore vectorStore,
                         QuestionAnswerAdvisor retrievalAugmentationAdvisor) {
        this.chatClient = chatClient;
        this.ingestService = ingestService;
        this.vectorStore = vectorStore;
        this.ragAdvisor = retrievalAugmentationAdvisor;
    }

    /** 入库单个文件。 */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam String path) {
        DocumentIngestService.IngestResult r = ingestService.ingestFile(path);
        return Map.of("file", r.file(), "chunks", r.chunks(), "status", r.status());
    }

    /** 批量入库一个目录。 */
    @PostMapping("/ingest-dir")
    public List<DocumentIngestService.IngestResult> ingestDir(@RequestParam String path) {
        return ingestService.ingestDirectory(path);
    }

    /** 知识库规模概览。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("documents", ingestService.count());
    }

    /**
     * RAG 流式问答。检索 + 生成一步到位，SSE 逐 token 推送。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message,
                               @RequestParam(required = false) String chatId) {
        String convId = (chatId == null || chatId.isBlank()) ? "rag-default" : chatId;

        return chatClient.prompt()
                .user(message)
                .advisors(ragAdvisor)
                .advisors(a -> a.param("chat_memory_conversation_id", convId))
                .stream()
                .content();
    }

    /**
     * 只做检索、不调模型：用于调试“为什么答不出来”。
     * 返回命中的片段和相似度分数，方便判断是切块问题还是阈值问题。
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                            @RequestParam(defaultValue = "5") int topK) {
        return vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(query)
                                .topK(topK)
                                .build())
                .stream()
                .map(d -> Map.<String, Object>of(
                        "source", d.getMetadata().getOrDefault("source", "unknown"),
                        "chunk_index", d.getMetadata().getOrDefault("chunk_index", -1),
                        "score", d.getMetadata().getOrDefault("distance", 0),
                        "text", d.getText().length() > 300
                                ? d.getText().substring(0, 300) + "..."
                                : d.getText()))
                .toList();
    }
}
