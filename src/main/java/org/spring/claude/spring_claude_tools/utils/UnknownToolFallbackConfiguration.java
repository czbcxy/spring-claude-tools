package org.spring.claude.spring_claude_tools.utils;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型调用了未注册的工具时（例如会话历史里残留的 Bash 调用），返回提示而不是抛
 * {@code IllegalStateException: No ToolCallback found for tool name}，让对话继续。
 * 需配合 {@code spring.ai.tools.resolution.fallback.enabled=true}。
 */
@Configuration
public class UnknownToolFallbackConfiguration {

    @Bean
    ToolCallbackResolver toolCallbackResolver() {
        return toolName -> new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(toolName)
                        .description("未注册的工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                System.err.println("[tool] 模型调用了未注册的工具：" + toolName);
                return "工具 " + toolName + " 当前不可用（未注册）。请只使用本次请求中提供的工具，或直接回答用户。";
            }
        };
    }
}
