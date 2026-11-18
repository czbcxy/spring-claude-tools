package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务 / 提醒工具。
 *
 * <p>当前为进程内轻量实现：任务保存在内存，到点后写一条到内存队列，
 * 由 nextReminder / listScheduledTasks 查询读取。进程重启后任务丢失。
 * 需要跨重启请配合 WriteFileTools 把任务落盘。
 */
@Component
public class ScheduleTools {

    /** 触发后的提醒条数上限 */
    private static final int MAX_FIRED = 200;

    private static final Path STORE = Path.of(System.getProperty("user.dir"), ".agent", "schedule.log");

    private record Task(String id, String title, Instant fireAt, String note) {
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    private final List<String> fired = new ArrayList<>();

    @Tool(description = "创建一个延时提醒任务（毫秒级精度）。到点后会被记入待读取提醒队列，可通过 nextReminder 读取。适合'X 分钟后提醒我'类需求")
    public String scheduleReminder(
            @ToolParam(description = "提醒标题，例如：喝咖啡") String title,
            @ToolParam(description = "距今多少秒后触发，例如 600 表示 10 分钟后") long delaySeconds,
            @ToolParam(description = "备注内容，可为空") String note) {

        if (title == null || title.isBlank()) {
            return "创建失败：标题不能为空";
        }
        if (delaySeconds < 0) {
            return "创建失败：延时秒数不能为负";
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Instant fireAt = Instant.now().plusSeconds(delaySeconds);
        tasks.put(id, new Task(id, title, fireAt, note == null ? "" : note));

        long scheduled = 0;
        try {
            Path parent = STORE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(STORE,
                    Instant.now() + " | scheduled | " + id + " | " + title + " | fireAt=" + fireAt + " | " + note + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            scheduled = tasks.size();
        } catch (IOException ignored) {
            // 落盘失败不影响内存调度
        }
        return "已创建提醒 [" + title + "]（id=" + id + "），将在 " + delaySeconds + " 秒后触发于 " + fireAt
                + "。当前未完成任务数：" + scheduled;
    }

    @Tool(description = "读取已到期的提醒（并清出队列）。没有到期提醒时返回提示信息")
    public String nextReminder() {
        Instant now = Instant.now();
        List<Task> due = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (!t.fireAt().isAfter(now)) {
                due.add(t);
            }
        }
        if (due.isEmpty()) {
            long pending = tasks.values().stream().filter(t -> t.fireAt().isAfter(now)).count();
            return "暂无到期提醒。未完成任务数：" + pending;
        }
        StringBuilder sb = new StringBuilder("到期提醒 ").append(due.size()).append(" 条：\n");
        for (Task t : due) {
            tasks.remove(t.id());
            fired.add(t.title() + " | " + t.fireAt());
            sb.append("- [").append(t.title()).append("] 触发于 ").append(t.fireAt());
            if (!t.note().isBlank()) {
                sb.append("  备注：").append(t.note());
            }
            sb.append('\n');
            try {
                Path parent = STORE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(STORE,
                        Instant.now() + " | fired | " + t.id() + " | " + t.title() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
        while (fired.size() > MAX_FIRED) {
            fired.remove(0);
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "列出所有尚未到期的定时提醒任务及其剩余时间")
    public String listScheduledTasks() {
        Instant now = Instant.now();
        List<Task> pending = tasks.values().stream()
                .filter(t -> t.fireAt().isAfter(now))
                .sorted((a, b) -> a.fireAt().compareTo(b.fireAt()))
                .toList();
        if (pending.isEmpty()) {
            return "当前没有未到期的提醒任务";
        }
        StringBuilder sb = new StringBuilder("未到期提醒 ").append(pending.size()).append(" 条：\n");
        for (Task t : pending) {
            long remain = t.fireAt().getEpochSecond() - now.getEpochSecond();
            sb.append("- [").append(t.title()).append("]（id=").append(t.id())
                    .append("）剩余 ").append(remain).append(" 秒，触发于 ").append(t.fireAt()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "按任务 id 取消一个尚未触发的提醒")
    public String cancelTask(@ToolParam(description = "任务 id，可从 listScheduledTasks 获取") String taskId) {
        Task removed = (taskId == null) ? null : tasks.remove(taskId.trim());
        return removed == null ? "未找到任务 [" + taskId + "]"
                : "已取消提醒 [" + removed.title() + "]（id=" + removed.id() + "）";
    }
}
