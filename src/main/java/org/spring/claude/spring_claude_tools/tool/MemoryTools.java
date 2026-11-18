package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨会话长期记忆工具。
 *
 * <p>与对话上下文记忆（chat-memory / session）不同：那些只按会话 ID 隔离，
 * 换一个会话 ID 就看不到。这个工具提供主动式长期记忆——AI 把重要事实
 * 主动写入 Redis（key 前缀 {@code agent:memory:}），跨会话、跨重启持久存在。
 */
@Component
public class MemoryTools implements AgentTool {

    private static final String KEY_PREFIX = "agent:memory:";

    private static final int MAX_NOTE_CHARS = 2000;

    private static final int MAX_NOTES = 1000;

    private final JedisPooled jedis;

    private final boolean enabled;

    private final boolean redisAvailable;

    private final String unavailableReason;

    public MemoryTools(
            @Value("${spring.ai.chat.memory.repository.redis.host:localhost}") String host,
            @Value("${spring.ai.chat.memory.repository.redis.port:6379}") int port,
            @Value("${agent.tools.memory.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        JedisPooled client = null;
        boolean ok = false;
        String reason = "";
        try {
            client = new JedisPooled(host, port);
            String pong = client.ping();
            ok = "PONG".equalsIgnoreCase(pong);
            if (!ok) {
                reason = "Redis PING 返回异常：" + pong;
            }
        }
        catch (Exception e) {
            reason = "Redis 不可用（" + host + ":" + port + "）：" + e.getMessage();
        }
        this.jedis = client;
        this.redisAvailable = ok;
        this.unavailableReason = !enabled ? "配置项 agent.tools.memory.enabled=false" : reason;
    }

    @Override
    public boolean isAvailable() {
        return enabled && redisAvailable;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Tool(description = "把一条重要事实或用户偏好保存到长期记忆（Redis 持久化，跨会话可召回）。当用户说'记住……'或对话中出现值得长期保留的关键信息时调用")
    public String remember(
            @ToolParam(description = "记忆的标题或主题，例如：用户编程偏好") String title,
            @ToolParam(description = "要记住的具体内容") String content) {

        if (!isAvailable()) {
            return "长期记忆不可用：" + unavailableReason;
        }
        if (content.length() > MAX_NOTE_CHARS) {
            return "保存失败：内容超过 " + MAX_NOTE_CHARS + " 字符上限";
        }
        Set<String> existing = jedis.smembers(KEY_PREFIX + "index");
        if (existing.size() >= MAX_NOTES) {
            return "保存失败：记忆数量已达上限 " + MAX_NOTES + " 条，请先用 recall 查看并让用户清理";
        }
        String id = String.valueOf(Instant.now().toEpochMilli());
        jedis.hset(KEY_PREFIX + id,
                Map.of("title", title, "content", content, "createdAt", Instant.now().toString()));
        jedis.sadd(KEY_PREFIX + "index", id);
        return "已保存到长期记忆 [" + title + "]（记忆 id：" + id + "）";
    }

    @Tool(description = "从长期记忆中检索相关内容。参数 keyword 支持在标题和内容里做包含匹配，传空字符串则返回最近保存的记忆列表")
    public String recall(
            @ToolParam(description = "检索关键词，传空字符串表示列出全部记忆") String keyword) {

        if (!isAvailable()) {
            return "长期记忆不可用：" + unavailableReason;
        }
        Set<String> ids = jedis.smembers(KEY_PREFIX + "index");
        if (ids.isEmpty()) {
            return "长期记忆为空，还没有保存过任何记忆";
        }
        List<String> notes = new ArrayList<>();
        for (String id : ids) {
            Map<String, String> note = jedis.hgetAll(KEY_PREFIX + id);
            if (note.isEmpty()) {
                continue;
            }
            String title = note.getOrDefault("title", "");
            String content = note.getOrDefault("content", "");
            if (keyword == null || keyword.isBlank()
                    || title.toLowerCase().contains(keyword.toLowerCase())
                    || content.toLowerCase().contains(keyword.toLowerCase())) {
                notes.add("- [" + title + "] " + content + "（保存于 " + note.get("createdAt") + "）");
            }
        }
        if (notes.isEmpty()) {
            return "长期记忆中没有与 [" + keyword + "] 相关的内容（共 " + ids.size() + " 条记忆）";
        }
        return "找到 " + notes.size() + " 条记忆：\n" + String.join("\n", notes);
    }

    @Tool(description = "从长期记忆中删除一条记忆。先调用 recall 查看记忆列表，再按标题匹配删除")
    public String forget(
            @ToolParam(description = "要删除的记忆标题（精确匹配）") String title) {

        if (!isAvailable()) {
            return "长期记忆不可用：" + unavailableReason;
        }
        Set<String> ids = jedis.smembers(KEY_PREFIX + "index");
        int deleted = 0;
        for (String id : ids) {
            Map<String, String> note = jedis.hgetAll(KEY_PREFIX + id);
            if (title.equals(note.get("title"))) {
                jedis.del(KEY_PREFIX + id);
                jedis.srem(KEY_PREFIX + "index", id);
                deleted++;
            }
        }
        return deleted > 0 ? "已删除 " + deleted + " 条记忆 [" + title + "]"
                : "未找到标题为 [" + title + "] 的记忆";
    }
}
