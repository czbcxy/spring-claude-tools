package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具（多引擎回退实现）。
 *
 * <p>依次尝试多个无需 API Key 的数据源，任一成功即返回：
 * <ol>
 *   <li>DuckDuckGo Lite 端点（POST 表单，比 html 端点更稳定）</li>
 *   <li>DuckDuckGo html 端点（GET）</li>
 *   <li>Bing 新闻 RSS（仅新闻类查询有效）</li>
 *   <li>Google News RSS（仅新闻类查询有效）</li>
 * </ol>
 * 任一引擎返回的结果条数不足或为空则自动降级到下一个。
 * 结果包含标题、链接、摘要；RSS 源额外包含发布时间。
 */
@Component
public class WebSearchTools implements AgentTool {

    private static final int MAX_RESULTS = 10;

    private static final int MAX_SNIPPET_CHARS = 400;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** DuckDuckGo Lite：<a rel="nofollow" href="..." class='result-link'>标题</a> */
    private static final Pattern DDG_LITE_RESULT = Pattern.compile(
            "<a[^>]*class=['\"]result-link['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** DuckDuckGo html：<a class="result__a" href="...">标题</a> */
    private static final Pattern DDG_HTML_RESULT = Pattern.compile(
            "<a[^>]*class=['\"]result__a['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern DDG_HTML_SNIPPET = Pattern.compile(
            "<a[^>]*class=['\"]result__snippet['\"][^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** RSS 通用解析 */
    private static final Pattern RSS_ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern RSS_TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern RSS_LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern RSS_DESC = Pattern.compile(
            "<description>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</description>", Pattern.DOTALL);
    private static final Pattern RSS_DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final boolean enabled;

    public WebSearchTools(@Value("${agent.tools.web-search.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String unavailableReason() {
        return "配置项 agent.tools.web-search.enabled=false";
    }

    @Tool(description = "联网搜索：查询互联网信息，返回标题、链接、摘要（RSS 源含发布时间）。适合实时新闻、行情、最新动态、文档查询。多引擎自动回退，不要虚构结果")
    public String webSearch(
            @ToolParam(description = "搜索关键词，例如：半导体 最新新闻 或 Spring AI 2.0 新特性") String query) {

        if (query == null || query.isBlank()) {
            return "搜索失败：关键词不能为空";
        }
        List<String> errors = new ArrayList<>();

        String lite = tryDdgLite(query, errors);
        if (lite != null) {
            return lite;
        }
        String html = tryDdgHtml(query, errors);
        if (html != null) {
            return html;
        }
        String bingNews = tryRss("https://www.bing.com/news/search?format=rss&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8), "Bing 新闻", errors);
        if (bingNews != null) {
            return bingNews;
        }
        String googleNews = tryRss("https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8), "Google 新闻", errors);
        if (googleNews != null) {
            return googleNews;
        }
        return "所有搜索源均失败：\n- " + String.join("\n- ", errors);
    }

    @Tool(description = "只抓取新闻类结果（走 Bing / Google News RSS，含发布时间），适合'最新消息''今天发生了什么''某行业新闻'。比 webSearch 更聚焦时效性")
    public String searchNews(
            @ToolParam(description = "新闻关键词，例如：半导体 出口管制") String query) {

        if (query == null || query.isBlank()) {
            return "搜索失败：关键词不能为空";
        }
        List<String> errors = new ArrayList<>();
        String bing = tryRss("https://www.bing.com/news/search?format=rss&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8), "Bing 新闻", errors);
        if (bing != null) {
            return bing;
        }
        String google = tryRss("https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8), "Google 新闻", errors);
        if (google != null) {
            return google;
        }
        return "新闻源均失败：\n- " + String.join("\n- ", errors);
    }

    // ---------------- 各数据源 ----------------

    private String tryDdgLite(String query, List<String> errors) {
        try {
            String body = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://lite.duckduckgo.com/lite/"))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                errors.add("DuckDuckGo Lite：HTTP " + resp.statusCode());
                return null;
            }
            List<String> items = parseDdg(resp.body(), DDG_LITE_RESULT, null);
            return items.isEmpty() ? null : render("DuckDuckGo Lite", query, items, errors);
        } catch (Exception e) {
            errors.add("DuckDuckGo Lite：" + e.getClass().getSimpleName());
            return null;
        }
    }

    private String tryDdgHtml(String query, List<String> errors) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                errors.add("DuckDuckGo HTML：HTTP " + resp.statusCode());
                return null;
            }
            List<String> items = parseDdg(resp.body(), DDG_HTML_RESULT, DDG_HTML_SNIPPET);
            return items.isEmpty() ? null : render("DuckDuckGo", query, items, errors);
        } catch (Exception e) {
            errors.add("DuckDuckGo HTML：" + e.getClass().getSimpleName());
            return null;
        }
    }

    private String tryRss(String url, String sourceName, List<String> errors) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                errors.add(sourceName + "：HTTP " + resp.statusCode());
                return null;
            }
            List<String> items = parseRss(resp.body());
            if (items.isEmpty()) {
                errors.add(sourceName + "：无结果");
                return null;
            }
            return render(sourceName, null, items, errors);
        } catch (Exception e) {
            errors.add(sourceName + "：" + e.getClass().getSimpleName());
            return null;
        }
    }

    // ---------------- 解析 ----------------

    private List<String> parseDdg(String html, Pattern resultPattern, Pattern snippetPattern) {
        List<String> out = new ArrayList<>();
        List<String> snippets = new ArrayList<>();
        if (snippetPattern != null) {
            Matcher sm = snippetPattern.matcher(html);
            while (sm.find() && snippets.size() < MAX_RESULTS) {
                snippets.add(clip(clean(sm.group(1))));
            }
        }
        Matcher m = resultPattern.matcher(html);
        while (m.find() && out.size() < MAX_RESULTS) {
            String url = decodeDdgUrl(m.group(1));
            String title = clean(m.group(2));
            if (title.isBlank()) {
                continue;
            }
            StringBuilder item = new StringBuilder(title).append("\n   链接：").append(url);
            int idx = out.size();
            if (idx < snippets.size() && !snippets.get(idx).isBlank()) {
                item.append("\n   摘要：").append(snippets.get(idx));
            }
            out.add(item.toString());
        }
        return out;
    }

    private List<String> parseRss(String xml) {
        List<String> out = new ArrayList<>();
        Matcher item = RSS_ITEM.matcher(xml);
        while (item.find() && out.size() < MAX_RESULTS) {
            String block = item.group(1);
            String title = first(RSS_TITLE, block);
            String link = first(RSS_LINK, block);
            String desc = first(RSS_DESC, block);
            String date = first(RSS_DATE, block);
            if (title == null || title.isBlank()) {
                continue;
            }
            StringBuilder sb = new StringBuilder(clean(title));
            if (link != null && !link.isBlank()) {
                sb.append("\n   链接：").append(link.strip());
            }
            if (date != null && !date.isBlank()) {
                sb.append("\n   时间：").append(date.strip());
            }
            if (desc != null && !desc.isBlank()) {
                sb.append("\n   摘要：").append(clip(clean(desc)));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private String render(String source, String query, List<String> items, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(query == null ? "最新结果" : "搜索 [" + query + "] 的结果");
        sb.append("（来源：").append(source).append("，共 ").append(items.size()).append(" 条）\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
        if (!errors.isEmpty()) {
            sb.append("\n[前序源失败：").append(String.join("；", errors)).append("]");
        }
        return sb.toString();
    }

    private String first(Pattern p, String block) {
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private String clean(String html) {
        if (html == null) {
            return "";
        }
        return TAG_PATTERN.matcher(html)
                .replaceAll("")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String clip(String s) {
        return s.length() > MAX_SNIPPET_CHARS ? s.substring(0, MAX_SNIPPET_CHARS) + "..." : s;
    }

    private String decodeDdgUrl(String url) {
        try {
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            if (url.contains("uddg=")) {
                int start = url.indexOf("uddg=") + 5;
                int end = url.indexOf('&', start);
                url = java.net.URLDecoder.decode(url.substring(start, end > 0 ? end : url.length()),
                        StandardCharsets.UTF_8);
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }
}
