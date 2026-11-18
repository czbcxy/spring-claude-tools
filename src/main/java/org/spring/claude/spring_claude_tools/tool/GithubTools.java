package org.spring.claude.spring_claude_tools.tool;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * GitHub 工具，基于 GitHub REST API v3（https://api.github.com）。
 *
 * <p>能力：
 * <ul>
 *   <li>仓库信息查询（star/fork/issue、语言、许可证等）</li>
 *   <li>搜索仓库 / 搜索代码 / 搜索 issue</li>
 *   <li>查看仓库文件内容、目录列表</li>
 *   <li>查看最近提交、issue 列表、Pull Request 列表</li>
 *   <li>查看当前登录用户信息（需配置 Token）</li>
 * </ul>
 *
 * <p>认证：可选配置 {@code agent.github.token}（PAT）。未配置时以匿名方式访问，
 * 受 GitHub 限流约束（60 次/小时）；配置后为 5000 次/小时，且可访问私有仓库。
 * 未配置 Token 时工具仍可用，只是能力受限，因此 {@link #isAvailable()} 始终返回 true。
 */
@Component
public class GithubTools implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GithubTools.class);

    private static final String API_BASE = "https://api.github.com";
    private static final int MAX_BODY_CHARS = 15_000;

    /** 可选：GitHub Personal Access Token，未配置则匿名访问 */
    @Value("${agent.github.token:}")
    private String token;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 可用性 ====================

    @Override
    public boolean isAvailable() {
        // 无 Token 也能用（匿名限流），所以始终可用
        return true;
    }

    @Override
    public String unavailableReason() {
        return "未配置 agent.github.token（匿名访问可用，但限流 60 次/小时且无法访问私有仓库）";
    }

    /** 是否配置了 Token，用于提示信息 */
    private boolean hasToken() {
        return token != null && !token.isBlank();
    }

    // ==================== 1. 仓库信息 ====================

    @Tool(description = "查询 GitHub 仓库的基本信息：描述、Star 数、Fork 数、Issue 数、主语言、许可证、默认分支、最近更新时间。当用户想了解某个开源项目时调用")
    public String getRepoInfo(
            @ToolParam(description = "仓库全名，格式 owner/repo，例如：openai/openai-java") String repo) {

        String[] parts = splitRepo(repo);
        if (parts == null) {
            return "参数错误：仓库格式应为 owner/repo，例如 openai/openai-java";
        }

        JsonNode node = getJson("/repos/" + parts[0] + "/" + parts[1]);
        if (node == null) {
            return "查询失败：仓库不存在或网络异常 - " + repo;
        }

        return """
                仓库：%s
                描述：%s
                主语言：%s
                Star：%d　Fork：%d　Watch：%d
                未关闭 Issue：%d
                许可证：%s
                默认分支：%s
                ⭐ 最近更新：%s
                最近推送：%s
                地址：%s
                """.formatted(
                text(node, "full_name"),
                text(node, "description"),
                text(node, "language"),
                node.path("stargazers_count").asInt(),
                node.path("forks_count").asInt(),
                node.path("subscribers_count").asInt(),
                node.path("open_issues_count").asInt(),
                node.path("license").path("spdx_id").asString("-"),
                text(node, "default_branch"),
                text(node, "updated_at"),
                text(node, "pushed_at"),
                text(node, "html_url"));
    }

    // ==================== 2. 搜索仓库 ====================

    @Tool(description = "搜索 GitHub 仓库，返回匹配的仓库列表（名称、描述、Star 数、语言）。适合找某个主题的开源项目。支持 GitHub 搜索语法，如 language:java stars:>1000")
    public String searchRepos(
            @ToolParam(description = "搜索关键词，例如：spring ai 或 language:java stars:>5000") String query,
            @ToolParam(description = "返回条数，1-30，默认 10") int limit) {

        int n = Math.max(1, Math.min(limit, 30));
        String q = URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8);
        JsonNode node = getJson("/search/repositories?q=" + q + "&sort=stars&order=desc&per_page=" + n);

        if (node == null) {
            return "搜索失败：" + query;
        }
        JsonNode items = node.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "未搜索到匹配的仓库：" + query;
        }

        StringBuilder sb = new StringBuilder("搜索 \"" + query + "\" 共 "
                + node.path("total_count").asLong() + " 个结果，返回前 " + items.size() + " 个：\n");
        int i = 1;
        for (JsonNode r : items) {
            sb.append("\n").append(i++).append(". ")
                    .append(text(r, "full_name"))
                    .append("  ⭐ ").append(r.path("stargazers_count").asInt())
                    .append("  [" ).append(text(r, "language")).append("]")
                    .append("\n    ").append(text(r, "description"))
                    .append("\n    ").append(text(r, "html_url"));
        }
        return sb.toString();
    }

    // ==================== 3. 搜索代码 ====================

    @Tool(description = "在 GitHub 上搜索代码。注意：匿名访问时 GitHub 已不开放代码搜索，需配置 Token。适合查找某个 API 或类的实际使用示例")
    public String searchCode(
            @ToolParam(description = "代码搜索关键词，可带限定语法，例如：ChatClient language:java") String query,
            @ToolParam(description = "返回条数，1-30，默认 10") int limit) {

        if (!hasToken()) {
            return "代码搜索需要 GitHub Token（未配置 agent.github.token）。匿名访问下该接口不可用，建议改用 searchRepos 或读取具体仓库文件。";
        }

        int n = Math.max(1, Math.min(limit, 30));
        String q = URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8);
        JsonNode node = getJson("/search/code?q=" + q + "&per_page=" + n);

        if (node == null) {
            return "代码搜索失败：" + query;
        }
        JsonNode items = node.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "未搜索到匹配代码：" + query;
        }

        StringBuilder sb = new StringBuilder("代码搜索 \"" + query + "\" 共 "
                + node.path("total_count").asLong() + " 个结果：\n");
        int i = 1;
        for (JsonNode it : items) {
            sb.append("\n").append(i++).append(". ")
                    .append(text(it, "repository").isEmpty() ? "" : it.path("repository").path("full_name").asString())
                    .append(" → ").append(text(it, "path"))
                    .append("\n    ").append(text(it, "html_url"));
        }
        return sb.toString();
    }

    // ==================== 4. 文件内容 / 目录 ====================

    @Tool(description = "读取 GitHub 仓库中指定路径的文件内容或目录列表。path 为空时列出仓库根目录。文件会返回文本内容（截断到 15000 字符）")
    public String getRepoContent(
            @ToolParam(description = "仓库全名 owner/repo，例如：openai/openai-java") String repo,
            @ToolParam(description = "文件或目录路径，例如：README.md 或 src/main/java；留空表示根目录") String path,
            @ToolParam(description = "分支或 tag，留空使用默认分支") String ref) {

        String[] parts = splitRepo(repo);
        if (parts == null) {
            return "参数错误：仓库格式应为 owner/repo";
        }

        String url = "/repos/" + parts[0] + "/" + parts[1] + "/contents/"
                + (path == null ? "" : path.trim().replaceAll("^/+", ""));
        if (ref != null && !ref.isBlank()) {
            url += "?ref=" + URLEncoder.encode(ref.trim(), StandardCharsets.UTF_8);
        }

        String raw = getRaw(url);
        if (raw == null) {
            return "读取失败：" + repo + " / " + path;
        }

        try {
            JsonNode node = mapper.readTree(raw);

            // 目录：数组
            if (node.isArray()) {
                StringBuilder sb = new StringBuilder(repo + " / "
                        + (path == null || path.isBlank() ? "(根目录)" : path) + " 目录列表：\n");
                for (JsonNode it : node) {
                    sb.append("\n  [").append(text(it, "type")).append("] ")
                            .append(text(it, "name"))
                            .append("  (").append(it.path("size").asLong()).append(" B)");
                }
                return sb.toString();
            }

            // 文件
            String type = text(node, "type");
            if (!"file".equals(type)) {
                return "该路径不是文件：" + path + "（类型 " + type + "）";
            }
            String content = node.path("content").asString("");
            if (node.path("encoding").asString("").equals("base64") && !content.isBlank()) {
                String decoded = new String(java.util.Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
                if (decoded.length() > MAX_BODY_CHARS) {
                    decoded = decoded.substring(0, MAX_BODY_CHARS)
                            + "\n...(已截断，原始长度 " + decoded.length() + " 字符)";
                }
                return "文件：" + text(node, "path")
                        + "（" + node.path("size").asLong() + " B）\n\n" + decoded;
            }
            return "文件：" + text(node, "path") + "\n（内容过大或为空，请用 web 端查看）\n" + text(node, "html_url");

        } catch (Exception e) {
            return "解析失败：" + e.getMessage();
        }
    }

    // ==================== 5. 最近提交 ====================

    @Tool(description = "查询 GitHub 仓库最近的提交记录（提交信息、作者、时间、SHA）。适合了解项目最近在做什么")
    public String getRecentCommits(
            @ToolParam(description = "仓库全名 owner/repo") String repo,
            @ToolParam(description = "返回条数，1-30，默认 10") int limit) {

        String[] parts = splitRepo(repo);
        if (parts == null) {
            return "参数错误：仓库格式应为 owner/repo";
        }
        int n = Math.max(1, Math.min(limit, 30));

        String raw = getRaw("/repos/" + parts[0] + "/" + parts[1] + "/commits?per_page=" + n);
        if (raw == null) {
            return "查询失败：" + repo;
        }
        try {
            JsonNode arr = mapper.readTree(raw);
            if (!arr.isArray() || arr.isEmpty()) {
                return "该仓库没有提交记录：" + repo;
            }
            StringBuilder sb = new StringBuilder(repo + " 最近 " + arr.size() + " 次提交：\n");
            for (JsonNode c : arr) {
                String msg = text(c.path("commit"), "message").split("\n")[0];
                sb.append("\n- ").append(c.path("sha").asString("").substring(0, 7))
                        .append("  ").append(text(c.path("commit").path("author"), "date"))
                        .append("  ").append(text(c.path("commit").path("author"), "name"))
                        .append("\n  ").append(msg);
            }
            return sb.toString();
        } catch (Exception e) {
            return "解析失败：" + e.getMessage();
        }
    }

    // ==================== 6. Issue / PR ====================

    @Tool(description = "查询 GitHub 仓库的 issue 列表或 Pull Request 列表。state 可选 open/closed/all，默认 open；kind 选 issue 或 pr")
    public String listIssues(
            @ToolParam(description = "仓库全名 owner/repo") String repo,
            @ToolParam(description = "issue 或 pr（Pull Request），默认 issue") String kind,
            @ToolParam(description = "状态：open / closed / all，默认 open") String state,
            @ToolParam(description = "返回条数，1-30，默认 10") int limit) {

        String[] parts = splitRepo(repo);
        if (parts == null) {
            return "参数错误：仓库格式应为 owner/repo";
        }
        int n = Math.max(1, Math.min(limit, 30));
        String st = (state == null || state.isBlank()) ? "open" : state.trim();
        String k = (kind == null || kind.isBlank()) ? "issue" : kind.trim().toLowerCase();

        // GitHub 的 issues 接口会混入 PR，用参数区分
        String url;
        if ("pr".equals(k) || "pull".equals(k) || "pullrequest".equals(k)) {
            url = "/repos/" + parts[0] + "/" + parts[1] + "/pulls?state=" + st + "&per_page=" + n;
        } else {
            url = "/repos/" + parts[0] + "/" + parts[1] + "/issues?state=" + st + "&per_page=" + n;
        }

        String raw = getRaw(url);
        if (raw == null) {
            return "查询失败：" + repo;
        }
        try {
            JsonNode arr = mapper.readTree(raw);
            if (!arr.isArray() || arr.isEmpty()) {
                return repo + " 没有 " + st + " 状态的 " + k + " 记录";
            }
            String label = ("pr".equals(k) || "pull".equals(k)) ? "Pull Request" : "Issue";
            StringBuilder sb = new StringBuilder(repo + " 的 " + st + " " + label + "（" + arr.size() + " 条）：\n");
            for (JsonNode it : arr) {
                sb.append("\n#").append(it.path("number").asInt())
                        .append("  ").append(text(it, "title"))
                        .append("\n    作者：").append(text(it.path("user"), "login"))
                        .append("　更新：").append(text(it, "updated_at"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "解析失败：" + e.getMessage();
        }
    }

    // ==================== 7. 当前用户 ====================

    @Tool(description = "查询当前配置的 GitHub Token 对应的用户信息（用户名、仓库数、关注者等）。未配置 Token 时调用会提示")
    public String getCurrentUser() {
        if (!hasToken()) {
            return "未配置 GitHub Token（agent.github.token），无法获取登录用户信息。";
        }
        JsonNode node = getJson("/user");
        if (node == null) {
            return "获取用户信息失败：Token 可能无效或已过期";
        }
        return """
                GitHub 用户：%s（%s）
                公开仓库：%d　私有仓库：%d
                关注者：%d　正在关注：%d
                注册时间：%s
                """.formatted(
                text(node, "login"),
                text(node, "name"),
                node.path("public_repos").asInt(),
                node.path("total_private_repos").asInt(),
                node.path("followers").asInt(),
                node.path("following").asInt(),
                text(node, "created_at"));
    }

    // ==================== 内部实现 ====================

    /** 拆解 owner/repo */
    private String[] splitRepo(String repo) {
        if (repo == null) {
            return null;
        }
        String s = repo.trim()
                .replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replaceAll("\\.git$", "")
                .replaceAll("/+$", "");
        String[] p = s.split("/");
        if (p.length < 2 || p[0].isBlank() || p[1].isBlank()) {
            return null;
        }
        return new String[]{p[0], p[1]};
    }

    private JsonNode getJson(String pathAndQuery) {
        String raw = getRaw(pathAndQuery);
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("GitHub 响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 发起 GET 请求，返回响应体字符串；失败返回 null */
    private String getRaw(String pathAndQuery) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + pathAndQuery))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "spring-claude-tools/1.0")
                    .GET();
            if (hasToken()) {
                b.header("Authorization", "Bearer " + token.trim());
            }

            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();

            if (code == 403 || code == 429) {
                log.warn("GitHub API 限流或拒绝访问: {} {}", code, resp.body());
                return null;
            }
            if (code == 404) {
                log.info("GitHub 资源不存在: {}", pathAndQuery);
                return null;
            }
            if (code >= 400) {
                log.warn("GitHub API 错误 {}: {}", code, resp.body());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.error("GitHub 请求异常: {}", pathAndQuery, e);
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? "-" : v.asString("-");
    }
}
