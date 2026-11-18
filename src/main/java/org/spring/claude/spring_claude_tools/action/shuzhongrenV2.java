package org.spring.claude.spring_claude_tools.action;

import org.spring.claude.spring_claude_tools.tool.*;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

@Configuration
public class shuzhongrenV2 {

    @Bean
    public CommandLineRunner commandLineRunner(DeepSeekChatModel deepSeekChatModel,
                                               SessionMemoryAdvisor sessionMemoryAdvisor,
                                               WeatherTools weatherTools, LocalFileTools localFileTools,
                                               WriteFileTools writeFileTools, MemoryTools memoryTools,
                                               WebSearchTools webSearchTools, DatabaseTools databaseTools,
                                               EnvironmentTools environmentTools, ShellTools shellTools,
                                               HttpTools httpTools, ScheduleTools scheduleTools,
                                               DocumentTools documentTools, GithubTools githubTools) {

        // 按 AgentTool 契约过滤：配置缺失/外部依赖不可用的工具不注册给模型
        List<Object> available = new ArrayList<>();
        register(available, weatherTools);
        register(available, localFileTools);
        register(available, writeFileTools);
        register(available, memoryTools);
        register(available, webSearchTools);
        register(available, databaseTools);
        register(available, environmentTools);
//        register(available, shellTools);
        register(available, httpTools);
        register(available, scheduleTools);
        register(available, documentTools);
        register(available, githubTools);
        //注册 apirngaiutils中的工具
        register(available, FileSystemTools.builder().build());
        register(available, org.springaicommunity.agent.tools.ShellTools.builder().build());
        

        ChatClient chatClient = ChatClient.builder(deepSeekChatModel)
                .defaultSystem("""
                        你是书中人2.0，拥有以下能力与使用规则：

                        【长期记忆】你通过 remember/recall/forget 工具管理跨会话的长期记忆：
                        - 当用户透露关于自己的重要信息（名字、身份、偏好、重要决定等）时，必须主动调用 remember 工具保存，并在保存后简短告知用户"已记住"。
                        - 当用户说"记住……"时，必须调用 remember 工具。
                        - 每次对话开始或不确定用户信息时，可调用 recall 工具（keyword 可传空）召回相关记忆，主动使用召回的信息个性化回应。
                        - 用户要求删除记忆时调用 forget 工具。
                        - 记忆为空是正常的初始状态，不代表功能故障；保存过之后召回就有了。

                        【动态感知】涉及"现在/今天/还有多久"的时间问题用 environmentTools.currentTime / timeUntil；
                        涉及本机环境、资源、网络状况用 environmentTools.systemInfo / networkInfo。不要凭猜。

                        【工具使用】
                        - weatherTools：当前天气、多日预报
                        - localFileTools：读文件、目录遍历、全文搜索
                        - writeFileTools：写文件、追加、版本恢复
                        - memoryTools：长期记忆存取
                        - webSearchTools：联网搜索、新闻检索
                        - databaseTools：表结构探查、SQL 查询（默认只读）
                        - environmentTools：时间、系统信息、网络信息、倒计时
                        - httpTools：调外部 HTTP 接口、抓取网页
                        - scheduleTools：定时提醒、任务列表、取消任务
                        - documentTools：解析 pdf/docx/xlsx/纯文本文档
                        - githubTools：GitHub 仓库信息、搜索仓库/代码、读仓库文件与目录、最近提交、issue/PR 列表
                          仓库参数统一用 owner/repo 格式（如 openai/openai-java）；需要 Token 的功能未配置时会返回提示
                        只能调用本次请求实际提供的工具；当前没有 shell 执行能力，不要调用 Bash 等历史对话中出现过但未提供的工具。
                        按需调用，不要虚构工具返回结果。
                        """)
                .build();

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
                        Flux<String> content = chatClient.prompt()
                                .messages(message)
                                .tools(available.toArray())
                                .advisors(sessionMemoryAdvisor, SimpleLoggerAdvisor.builder().build())
                                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "shuzhongren"))
                                .stream()
                                .content();
                        System.err.print("\n> 分身: ");
                        content.doOnNext(System.out::print).blockLast();
                    }
                    catch (RuntimeException ex) {
                        // 打印服务端原始错误体，否则 400 只剩一行状态码，无法定位具体参数
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

    private void register(List<Object> target, Object tool) {
        if (tool instanceof AgentTool agentTool) {
            if (agentTool.isAvailable()) {
                target.add(tool);
            } else {
                System.err.println("[tool] 跳过 " + tool.getClass().getSimpleName() + "：" + agentTool.unavailableReason());
            }
            return;
        }
        target.add(tool);
    }
}
