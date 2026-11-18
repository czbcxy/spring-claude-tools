package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件写入/修改工具，带版本缓存。
 *
 * <p>安全限制与 {@link LocalFileTools} 一致：只允许操作当前工作目录内的文件。
 * 每次写入前会把文件的旧内容缓存到内存（每个路径最多保留 5 个历史版本），
 * AI 写错时可以通过 restoreFile 恢复到上一个版本。
 */
@Component
public class WriteFileTools {

    /** 单次写入的最大字符数 */
    private static final int MAX_WRITE_CHARS = 200_000;

    /** 每个路径保留的历史版本数 */
    private static final int MAX_HISTORY = 5;

    /** 路径 -> 历史版本队列（队首最旧，队尾最新） */
    private final Map<String, Deque<String>> versionCache = new ConcurrentHashMap<>();

    @Tool(description = "创建或覆盖写入一个本地文件。如果文件已存在，旧内容会自动缓存，可以用 restoreFile 工具恢复。适合创建新文件或整体重写文件内容")
    public String writeFile(
            @ToolParam(description = "目标文件路径（相对路径或绝对路径），例如：notes/todo.md") String path,
            @ToolParam(description = "要写入的完整文件内容") String content) {

        if (content.length() > MAX_WRITE_CHARS) {
            return "写入失败：内容超过 " + MAX_WRITE_CHARS + " 字符上限";
        }
        Path resolved = resolvePath(path);
        if (resolved == null) {
            return "写入失败：路径 " + path + " 不在允许访问的范围内（仅允许当前工作目录及其子目录）";
        }
        try {
            cachePreviousVersion(resolved);
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            long size = Files.size(resolved);
            return "写入成功：" + resolved + "（" + size + " 字节，" + content.length() + " 字符）";
        } catch (IOException e) {
            return "写入失败：" + e.getMessage();
        }
    }

    @Tool(description = "向本地文件追加内容（不覆盖原有内容）。如果文件不存在则创建。旧内容同样会缓存，可以用 restoreFile 恢复")
    public String appendToFile(
            @ToolParam(description = "目标文件路径（相对路径或绝对路径）") String path,
            @ToolParam(description = "要追加的内容") String content) {

        Path resolved = resolvePath(path);
        if (resolved == null) {
            return "追加失败：路径 " + path + " 不在允许访问的范围内";
        }
        try {
            cachePreviousVersion(resolved);
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return "追加成功：" + resolved + "（追加了 " + content.length() + " 字符）";
        } catch (IOException e) {
            return "追加失败：" + e.getMessage();
        }
    }

    @Tool(description = "把文件恢复到最近一次写入/追加之前的内容（从版本缓存中还原）。当写入出错需要撤销时使用")
    public String restoreFile(
            @ToolParam(description = "要恢复的文件路径（相对路径或绝对路径）") String path) {

        Path resolved = resolvePath(path);
        if (resolved == null) {
            return "恢复失败：路径 " + path + " 不在允许访问的范围内";
        }
        Deque<String> history = versionCache.get(resolved.toString());
        if (history == null || history.isEmpty()) {
            return "恢复失败：没有 " + resolved + " 的历史版本缓存（只有通过 writeFile/appendToFile 写过的文件才有缓存）";
        }
        try {
            String previous = history.removeLast();
            Files.writeString(resolved, previous, StandardCharsets.UTF_8);
            return "恢复成功：" + resolved + "（恢复到上一版本，" + previous.length() + " 字符）";
        } catch (IOException e) {
            return "恢复失败：" + e.getMessage();
        }
    }

    /** 写入前缓存旧内容，超量时淘汰最旧版本 */
    private void cachePreviousVersion(Path resolved) throws IOException {
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return;
        }
        Deque<String> history = versionCache.computeIfAbsent(resolved.toString(), k -> new ArrayDeque<>());
        while (history.size() >= MAX_HISTORY) {
            history.removeFirst();
        }
        history.addLast(Files.readString(resolved, StandardCharsets.UTF_8));
    }

    /** 与 LocalFileTools 相同的路径校验：必须在当前工作目录内 */
    private Path resolvePath(String path) {
        try {
            Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path target = base.resolve(path).normalize();
            return target.startsWith(base) ? target : null;
        } catch (Exception e) {
            return null;
        }
    }
}
