package org.spring.claude.spring_claude_tools.tool;

/**
 * 工具可用性契约。
 *
 * <p>实现了该接口的工具在注册给大模型前会先检查 {@link #isAvailable()}：
 * 依赖的配置参数缺失或外部服务不可用时，工具不会被加载进 prompt 的工具列表，
 * 避免模型调用到必然失败的工具而报错。
 *
 * <p>未实现该接口的工具视为始终可用（如纯本地文件工具）。
 */
public interface AgentTool {

    /** 当前配置下该工具是否可用 */
    boolean isAvailable();

    /** 不可用原因（用于启动日志提示） */
    default String unavailableReason() {
        return "缺少必要配置";
    }
}
