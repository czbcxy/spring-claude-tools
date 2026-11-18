package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 本地文件读取工具。
 * 出于安全考虑，只允许读取工作目录下的文件，
 * 拒绝访问工作目录之外的路径，且对大文件做截断保护。
 * 支持三种能力：读取文件内容、目录遍历（递归列出）、全文搜索。
 */
@Component
public class LocalFileTools {

    /** 单次最多返回的字符数，避免超大文件撑爆上下文 */
    private static final int MAX_CHARS = 50_000;

    /** 全文搜索最多扫描的文件数 */
    private static final int MAX_SCAN_FILES = 5000;

    /** 全文搜索最多返回的匹配行数 */
    private static final int MAX_MATCHES = 50;

    /** 目录遍历最多返回的条目数 */
    private static final int MAX_ENTRIES = 500;

    /** 超过此大小的文件跳过搜索（视为大文件/二进制），单位：字节 */
    private static final long MAX_SEARCH_FILE_SIZE = 2 * 1024 * 1024;

    /** 遍历和搜索时跳过的目录名 */
    private static final Set<String> SKIP_DIRS = Set.of(".git", ".idea", "node_modules", "target", "build", ".codebuddy");

    @Tool(description = "读取本地文件的内容并返回文本。参数 path 可以是相对路径（相对于项目工作目录）或绝对路径。适合让 AI 查看代码、配置文件、文本文档等。二进制文件不支持读取")
    public String readLocalFile(
            @ToolParam(description = "要读取的文件路径，例如：src/main/resources/application.properties 或 /Users/me/notes/a.txt") String path) {

        Path resolved = resolvePath(path);
        if (resolved == null) {
            return "读取失败：路径 " + path + " 不在允许访问的范围内（仅允许当前工作目录及其子目录）";
        }
        if (!Files.exists(resolved)) {
            return "读取失败：文件不存在 " + resolved;
        }
        if (!Files.isRegularFile(resolved)) {
            return "读取失败：" + resolved + " 不是一个文件（可能是目录）";
        }
        if (!Files.isReadable(resolved)) {
            return "读取失败：文件没有读取权限 " + resolved;
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            if (content.length() > MAX_CHARS) {
                return content.substring(0, MAX_CHARS)
                        + "\n\n[文件过大，已截断。原始大小：" + content.length() + " 字符，仅展示前 " + MAX_CHARS + " 字符]";
            }
            return content;
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        }
    }

    @Tool(description = "递归遍历目录，列出其下所有文件和子目录的相对路径。适合先摸清项目结构再决定读取哪个文件。会自动跳过 .git、node_modules、target 等无关目录")
    public String listDirectory(
            @ToolParam(description = "要遍历的目录路径，可以是相对路径或绝对路径，例如：src 或 .") String path) {

        Path resolved = resolvePath(path);
        if (resolved == null) {
            return "遍历失败：路径 " + path + " 不在允许访问的范围内（仅允许当前工作目录及其子目录）";
        }
        if (!Files.isDirectory(resolved)) {
            return "遍历失败：" + resolved + " 不是一个目录";
        }
        List<String> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resolved, 10)) {
            walk.filter(p -> !isSkippedDir(p))
                    .filter(p -> !p.equals(resolved))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_ENTRIES + 1)
                    .forEach(p -> {
                        if (entries.size() >= MAX_ENTRIES) {
                            entries.add("... [结果过多，已截断，只展示前 " + MAX_ENTRIES + " 条。请缩小遍历范围]");
                            return;
                        }
                        String relative = resolved.relativize(p).toString();
                        try {
                            if (Files.isDirectory(p)) {
                                entries.add("[DIR]  " + relative + "/");
                            }
                            else {
                                entries.add("[FILE] " + relative + " (" + Files.size(p) + " B)");
                            }
                        } catch (IOException e) {
                            entries.add("[ERROR] " + relative);
                        }
                    });
        } catch (IOException e) {
            return "遍历失败：" + e.getMessage();
        }
        if (entries.isEmpty()) {
            return "目录为空：" + resolved;
        }
        return "共 " + entries.size() + " 项：\n" + String.join("\n", entries);
    }

    @Tool(description = "在指定目录下递归进行全文搜索，返回所有包含关键词的文件及匹配行（格式：文件路径:行号:内容）。适合查找代码、配置中的某个类名、方法名、配置项等。会自动跳过二进制文件、大文件和 .git、node_modules、target 等无关目录")
    public String searchFileContent(
            @ToolParam(description = "要搜索的关键词，支持正则表达式，例如：ChatMemory 或 api-key") String keyword,
            @ToolParam(description = "搜索的目录路径，可以是相对路径或绝对路径，例如：src 或 .") String directory) {

        Path resolved = resolvePath(directory);
        if (resolved == null) {
            return "搜索失败：路径 " + directory + " 不在允许访问的范围内（仅允许当前工作目录及其子目录）";
        }
        if (!Files.isDirectory(resolved)) {
            return "搜索失败：" + resolved + " 不是一个目录";
        }

        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(keyword, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (java.util.regex.PatternSyntaxException e) {
            return "搜索失败：关键词不是合法的正则表达式 - " + e.getMessage();
        }

        List<String> matches = new ArrayList<>();
        int[] scanned = { 0 };
        boolean[] truncated = { false };
        try (Stream<Path> walk = Files.walk(resolved, 10)) {
            walk.filter(p -> !isSkippedDir(p))
                    .filter(Files::isRegularFile)
                    .limit(MAX_SCAN_FILES)
                    .forEach(p -> {
                        scanned[0]++;
                        if (matches.size() >= MAX_MATCHES) {
                            truncated[0] = true;
                            return;
                        }
                        try {
                            if (Files.size(p) > MAX_SEARCH_FILE_SIZE) {
                                return;
                            }
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (isLikelyBinary(content)) {
                                return;
                            }
                            String[] lines = content.split("\n");
                            String relative = resolved.relativize(p).toString();
                            for (int i = 0; i < lines.length; i++) {
                                if (pattern.matcher(lines[i]).find()) {
                                    String line = lines[i].strip();
                                    if (line.length() > 200) {
                                        line = line.substring(0, 200) + "...";
                                    }
                                    matches.add(relative + ":" + (i + 1) + ": " + line);
                                    if (matches.size() >= MAX_MATCHES) {
                                        truncated[0] = true;
                                        break;
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                            // 无权限或编码问题，跳过
                        }
                    });
        } catch (IOException e) {
            return "搜索失败：" + e.getMessage();
        }
        if (matches.isEmpty()) {
            return "未找到匹配（已扫描 " + scanned[0] + " 个文件）：关键词 [" + keyword + "]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(matches.size()).append(" 处匹配");
        if (truncated[0]) {
            sb.append("（结果过多，已截断为前 ").append(MAX_MATCHES).append(" 条，请缩小搜索范围或换更精确的关键词）");
        }
        sb.append("（已扫描 ").append(scanned[0]).append(" 个文件）：\n");
        sb.append(String.join("\n", matches));
        return sb.toString();
    }

    /** 判断路径是否是应该跳过的目录（隐藏目录或构建产物目录） */
    private boolean isSkippedDir(Path p) {
        String name = p.getFileName() != null ? p.getFileName().toString() : "";
        return SKIP_DIRS.contains(name) || (name.startsWith(".") && name.length() > 1 && Files.isDirectory(p));
    }

    /** 简单的二进制检测：包含 NUL 字符或不可打印字符占比过高时视为二进制文件 */
    private boolean isLikelyBinary(String content) {
        if (content.indexOf('\0') >= 0) {
            return true;
        }
        int sample = Math.min(content.length(), 8000);
        int nonPrintable = 0;
        for (int i = 0; i < sample; i++) {
            char c = content.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t' && (c < 32 || c == 65533)) {
                nonPrintable++;
            }
        }
        return nonPrintable > sample * 0.1;
    }

    /**
     * 解析并校验路径：相对路径基于当前工作目录解析；
     * 解析（含软链接）后必须仍位于工作目录内，防止路径穿越。
     */
    private Path resolvePath(String path) {
        try {
            Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path target = base.resolve(path).normalize();
            if (!target.startsWith(base)) {
                return null;
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }
}
