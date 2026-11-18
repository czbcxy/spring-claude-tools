package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具。
 *
 * <p>安全边界：
 * <ul>
 *   <li>默认关闭，需 {@code agent.shell.enabled=true} 才注册给模型。</li>
 *   <li>工作目录限制在当前项目工作目录内。</li>
 *   <li>超时强制中断（默认 30 秒），输出截断上限 20000 字符。</li>
 *   <li>危险命令黑名单（rm -rf /、mkfs、dd of=/dev 等）直接拒绝。</li>
 * </ul>
 */
@Component
public class ShellTools implements AgentTool {

    private static final int MAX_OUTPUT_CHARS = 20_000;

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private static final List<String> DENY_PATTERNS = List.of(
            "rm -rf /", "rm -rf /*", ":(){:|:&};:", "mkfs", "dd of=/dev/",
            "shutdown", "reboot", "> /dev/sda", "chmod -R 777 /");

    private final boolean enabled;

    private final Path baseDir;

    public ShellTools(@org.springframework.beans.factory.annotation.Value("${agent.shell.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String unavailableReason() {
        return "配置项 agent.shell.enabled=false（默认关闭，开启后模型可执行本机 shell 命令）";
    }

    @Tool(description = "在当前项目目录下执行 shell 命令并返回标准输出/stderr 与退出码。默认 30 秒超时，输出最长 20000 字符。适合运行构建、测试、git 查看、进程检查等。危险命令会被拒绝")
    public String runShell(
            @ToolParam(description = "要执行的命令，例如：mvn -q -DskipTests package 或 git status") String command,
            @ToolParam(description = "超时秒数，默认 30，最大 300") int timeoutSeconds) {

        if (!enabled) {
            return "拒绝执行：shell 执行能力未开启（agent.shell.enabled=false）";
        }
        if (command == null || command.isBlank()) {
            return "执行失败：命令不能为空";
        }
        String lowered = command.toLowerCase();
        for (String deny : DENY_PATTERNS) {
            if (lowered.contains(deny)) {
                return "拒绝执行：命令命中危险模式 [" + deny + "]";
            }
        }

        long timeout = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeoutSeconds, 300);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> argv = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);

        try {
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(baseDir.toFile())
                    .redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("LC_ALL", "C.UTF-8");
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append('\n');
                    }
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时（>" + timeout + " 秒），已强制终止\n已捕获输出：\n" + truncate(output.toString());
            }
            int exit = process.exitValue();
            return "退出码：" + exit + "\n工作目录：" + baseDir + "\n输出：\n" + truncate(output.toString());
        } catch (Exception e) {
            return "执行失败：" + e.getMessage();
        }
    }

    @Tool(description = "在当前项目目录下按通配符查找文件路径（类似 find），例如 *.java、pom.xml。适合定位文件位置")
    public String findFiles(
            @ToolParam(description = "文件名通配符，例如：*.java 或 application*.properties") String pattern,
            @ToolParam(description = "起始目录（相对当前项目目录），留空则为项目根目录") String startDir,
            @ToolParam(description = "最多返回条数，默认 100") int maxResults) {

        if (pattern == null || pattern.isBlank()) {
            return "查找失败：文件名通配符不能为空";
        }
        int limit = maxResults <= 0 ? 100 : Math.min(maxResults, 500);
        Path start;
        try {
            start = (startDir == null || startDir.isBlank())
                    ? baseDir
                    : baseDir.resolve(startDir).normalize();
        } catch (Exception e) {
            return "查找失败：起始目录无效";
        }
        if (!start.startsWith(baseDir) || !Files.isDirectory(start)) {
            return "查找失败：起始目录必须位于项目目录内且存在";
        }

        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        List<String> hits = new java.util.ArrayList<>();
        try (var stream = Files.walk(start)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> p.matcher(f.getFileName().toString()).matches())
                    .limit(limit)
                    .forEach(f -> hits.add(baseDir.relativize(f).toString()));
        } catch (Exception e) {
            return "查找失败：" + e.getMessage();
        }
        if (hits.isEmpty()) {
            return "没有匹配 [" + pattern + "] 的文件";
        }
        return "匹配 [" + pattern + "] 共 " + hits.size() + " 个（上限 " + limit + "）：\n- "
                + String.join("\n- ", hits);
    }

    private String truncate(String s) {
        return s.length() > MAX_OUTPUT_CHARS ? s.substring(0, MAX_OUTPUT_CHARS) + "\n...(已截断)" : s;
    }
}
