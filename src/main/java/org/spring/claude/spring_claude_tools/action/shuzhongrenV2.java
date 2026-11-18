package org.spring.claude.spring_claude_tools.action;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

import static org.spring.claude.spring_claude_tools.utils.AgentChatClientConfiguration.DEFAULT_USER_ID;

/**
 * 终端 REPL 入口。
 *
 * <p>ChatClient 与工具集由 {@link org.spring.claude.spring_claude_tools.utils.AgentChatClientConfiguration}
 * 统一提供，这里只负责命令行交互循环。
 *
 * <p>默认关闭：网页版已经覆盖了对话场景，且 CommandLineRunner 会阻塞启动线程并占用 stdin，
 * 与 Web 服务并行运行时体验不好。需要终端模式时加 {@code --agent.cli.enabled=true}。
 */
@Configuration
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true")
public class shuzhongrenV2 {

    @Bean
    public CommandLineRunner commandLineRunner(ChatClient agentChatClient,
                                               SessionMemoryAdvisor sessionMemoryAdvisor,
                                               List<Object> agentTools) {
        return args -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.err.print("\n> 本尊: ");
                    final String val;
                    try {
                        val = scanner.nextLine();
                    }
                    catch (NoSuchElementException eof) {
                        return; // stdin 关闭时正常退出，不再抛栈
                    }
                    if (val.isBlank()) {
                        continue;
                    }
                    Message message = UserMessage.builder().text(val).build();
                    try {
                        Flux<String> content = agentChatClient.prompt()
                                .messages(message)
                                .tools(agentTools.toArray())
                                .advisors(sessionMemoryAdvisor, SimpleLoggerAdvisor.builder().build())
                                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, DEFAULT_USER_ID))
                                .stream()
                                .content();
                        System.err.print("\n> 分身: ");
                        content.doOnNext(System.out::print).blockLast();
                    }
                    catch (RuntimeException ex) {
                        printHttpError(ex);
                    }
                }
            }
        };
    }

    /**
     * 从异常链里找出 WebClient 的 HTTP 错误，输出 DeepSeek 返回的 JSON 错误体。
     * 同时让单次失败不终止整个交互式循环。
     */
    private void printHttpError(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                System.err.println("\n[DeepSeek HTTP " + responseException.getStatusCode() + "] "
                        + (body.isBlank() ? responseException.getStatusText() : body));
                return;
            }
            current = current.getCause();
        }
        System.err.println("\n[调用失败] " + ex);
    }
}
