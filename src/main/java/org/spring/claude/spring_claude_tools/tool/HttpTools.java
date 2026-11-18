package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求工具。
 *
 * <p>用于调用外部 REST API、抓取网页内容。支持 GET / POST / PUT / DELETE，
 * 可自定义请求头与请求体，返回状态码、响应头摘要和截断后的响应体。
 * 默认 20 秒超时，响应体上限 20000 字符。
 */
@Component
public class HttpTools {

    private static final int MAX_BODY_CHARS = 20_000;

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(description = "发起 HTTP 请求调用外部接口或抓取网页，返回状态码、关键响应头和响应体（截断到 20000 字符）。适合调 REST API、抓取页面、验证服务可用性")
    public String httpRequest(
            @ToolParam(description = "请求方法：GET / POST / PUT / DELETE / PATCH / HEAD") String method,
            @ToolParam(description = "完整 URL，例如 https://api.github.com/repos/openai/openai-java") String url,
            @ToolParam(description = "请求头，格式为每行一个 key: value，留空表示无自定义头") String headers,
            @ToolParam(description = "请求体，GET/HEAD 留空即可") String body) {

        String m = (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(m)) {
            return "请求失败：不支持的方法 [" + m + "]，允许 " + ALLOWED_METHODS;
        }
        if (url == null || url.isBlank()) {
            return "请求失败：URL 不能为空";
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "spring-claude-tools/1.0");

            if (headers != null && !headers.isBlank()) {
                for (String line : headers.split("\\r?\\n")) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        builder.header(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                }
            }

            HttpRequest.BodyPublisher publisher = (body == null || body.isEmpty())
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(m, publisher);

            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            StringBuilder sb = new StringBuilder();
            sb.append("状态码：").append(resp.statusCode()).append('\n');
            sb.append("内容类型：").append(resp.headers().firstValue("content-type").orElse("未知")).append('\n');
            Map<String, List<String>> h = resp.headers().map();
            if (h.containsKey("location")) {
                sb.append("重定向至：").append(String.join(",", h.get("location"))).append('\n');
            }
            String respBody = resp.body() == null ? "" : resp.body();
            if (respBody.length() > MAX_BODY_CHARS) {
                respBody = respBody.substring(0, MAX_BODY_CHARS) + "\n...(已截断，原始长度 " + resp.body().length() + " 字符)";
            }
            sb.append("响应体：\n").append(respBody.isBlank() ? "(空)" : respBody);
            return sb.toString();
        } catch (Exception e) {
            return "请求失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }
}
