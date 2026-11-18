package org.spring.claude.spring_claude_tools.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.spring.claude.spring_claude_tools.utils.AgentChatClientConfiguration.DEFAULT_USER_ID;

/**
 * 网页对话接口。
 *
 * <p>使用 SSE（text/event-stream）逐 token 推送模型输出，前端用 EventSource 或 fetch 流式读取。
 * 会话记忆复用 {@link SessionMemoryAdvisor}，因此网页与终端共享同一份对话历史。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient agentChatClient;

    private final SessionMemoryAdvisor sessionMemoryAdvisor;

    private final List<Object> agentTools;

    public ChatController(ChatClient agentChatClient,
                          SessionMemoryAdvisor sessionMemoryAdvisor,
                          List<Object> agentTools) {
        this.agentChatClient = agentChatClient;
        this.sessionMemoryAdvisor = sessionMemoryAdvisor;
        this.agentTools = agentTools;
    }

    /**
     * 流式对话。
     *
     * @param body 请求体，形如 {"message":"你好","sessionId":"可选"}
     * @return SSE 流，每个事件 data 字段是一段增量文本
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            return Flux.just(event("error", "消息不能为空"));
        }

        String sessionId = (body.sessionId() == null || body.sessionId().isBlank())
                ? DEFAULT_USER_ID
                : body.sessionId();

        Message message = UserMessage.builder().text(body.message()).build();

        Flux<ServerSentEvent<String>> content = agentChatClient.prompt()
                .messages(message)
                .tools(agentTools.toArray())
                .advisors(sessionMemoryAdvisor, SimpleLoggerAdvisor.builder().build())
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                .stream()
                .content()
                .map(chunk -> event("message", chunk))
                .onErrorResume(ex -> Flux.just(event("error", describe(ex))));

        // 末尾补一个 done 事件，便于前端判断结束并解锁输入
        return content.concatWith(Flux.just(event("done", "[DONE]")))
                .timeout(Duration.ofMinutes(10));
    }

    /**
     * 健康检查 / 前端启动时拉取工具清单，用于展示能力面板。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        List<String> toolNames = agentTools.stream()
                .map(t -> t.getClass().getSimpleName())
                .sorted()
                .toList();
        return Map.of(
                "userId", DEFAULT_USER_ID,
                "toolCount", toolNames.size(),
                "tools", toolNames
        );
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(data == null ? "" : data)
                .build();
    }

    /**
     * 把异常转成人类可读的错误信息，尽量带上 DeepSeek 返回的原始错误体。
     */
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

    /**
     * 对话请求体。
     *
     * @param message   用户输入，必填
     * @param sessionId 会话标识，留空则使用默认会话（与终端共享历史）
     */
    public record ChatRequest(String message, String sessionId) {
    }
}
