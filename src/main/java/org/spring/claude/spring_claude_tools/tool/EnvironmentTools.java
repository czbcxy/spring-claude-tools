package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 动态感知工具（Environment Tools）。
 *
 * <p>让模型在需要时"看一眼"当前运行环境：时间时区、系统与 JVM 信息、
 * 工作目录磁盘占用、CPU 负载、可用处理器、网络主机信息等。
 * 全部为只读探测，不修改任何系统状态。
 */
@Component
public class EnvironmentTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(description = "获取当前精确时间与时区信息（本地时间、UTC 时间、时区 ID、是否夏令时、今天星期几）。涉及'现在'、'今天'、'几点'的问题先调用")
    public String currentTime(
            @ToolParam(description = "时区 ID，例如 Asia/Shanghai、America/New_York；留空使用系统默认时区") String zoneId) {
        ZoneId zone;
        try {
            zone = (zoneId == null || zoneId.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zoneId.trim());
        } catch (Exception e) {
            return "时区无效：" + zoneId + "，请使用标准 ID，如 Asia/Shanghai";
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        StringBuilder sb = new StringBuilder();
        sb.append("本地时间：").append(now.format(FMT))
                .append("（").append(now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINA)).append("）\n");
        sb.append("UTC 时间：").append(ZonedDateTime.now(ZoneId.of("UTC")).format(FMT)).append('\n');
        sb.append("时区：").append(now.getZone().getId())
                .append("（UTC").append(now.getOffset().getId().replace("Z", "+00:00")).append("）\n");
        sb.append("夏令时：").append(now.getZone().getRules().isDaylightSavings(now.toInstant()) ? "是" : "否").append('\n');
        sb.append("本年第 ").append(now.getDayOfYear()).append(" 天，本年第 ").append(now.getDayOfWeek().getValue()).append(" 天（周一起算）");
        return sb.toString();
    }

    @Tool(description = "感知当前运行环境：操作系统、架构、JVM 版本与堆内存、可用处理器数、系统负载、运行时长、工作目录及磁盘剩余空间")
    public String systemInfo() {
        Runtime rt = Runtime.getRuntime();
        long totalMb = rt.totalMemory() / 1024 / 1024;
        long freeMb = rt.freeMemory() / 1024 / 1024;
        long usedMb = totalMb - freeMb;
        long maxMb = rt.maxMemory() / 1024 / 1024;

        List<String> lines = new ArrayList<>();
        lines.add("操作系统：" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "（" + System.getProperty("os.arch") + "）");
        lines.add("Java：" + System.getProperty("java.version") + "，厂商 " + System.getProperty("java.vendor"));
        lines.add("可用处理器：" + rt.availableProcessors() + " 核");
        lines.add("JVM 堆内存：已用 " + usedMb + " MB / 已分配 " + totalMb + " MB / 上限 " + maxMb + " MB");
        lines.add("系统负载：" + fmtLoad(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        lines.add("JVM 运行时长：" + human(Duration.ofMillis(uptimeMs)));
        lines.add("进程 PID：" + ProcessHandle.current().pid());

        File cwd = new File(".").getAbsoluteFile();
        lines.add("工作目录：" + cwd.getPath());
        lines.add("磁盘：" + "总 " + cwd.getTotalSpace() / 1024 / 1024 / 1024 + " GB，剩余 "
                + cwd.getUsableSpace() / 1024 / 1024 / 1024 + " GB");
        lines.add("文件编码：" + System.getProperty("file.encoding") + "，默认时区：" + TimeZone.getDefault().getID());
        return String.join("\n", lines);
    }

    @Tool(description = "探测本机网络身份：主机名、本机 IP、是否可解析公网、常用端口占用概览（只读探测，不发外网数据）")
    public String networkInfo() {
        List<String> lines = new ArrayList<>();
        try {
            InetAddress local = InetAddress.getLocalHost();
            lines.add("主机名：" + local.getHostName());
            lines.add("本机地址：" + local.getHostAddress());
            lines.add("回环地址：" + InetAddress.getLoopbackAddress().getHostAddress());
        } catch (Exception e) {
            lines.add("主机信息获取失败：" + e.getMessage());
        }
        lines.add("代理配置 http.proxyHost：" + orNone(System.getProperty("http.proxyHost")));
        return String.join("\n", lines);
    }

    @Tool(description = "距离某个指定日期时间的剩余时间（用于倒计时、'还有多久'类问题）。参数格式：yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd")
    public String timeUntil(
            @ToolParam(description = "目标时间，格式 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd") String target) {
        if (target == null || target.isBlank()) {
            return "目标时间不能为空";
        }
        LocalDateTime targetTime;
        try {
            String t = target.trim();
            targetTime = t.length() <= 10
                    ? LocalDateTime.parse(t + " 00:00:00",
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return "时间格式错误，请使用 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd";
        }
        Duration d = Duration.between(LocalDateTime.now(), targetTime);
        if (d.isNegative()) {
            return "[" + targetTime.format(FMT) + "] 已经过去 " + human(d.negated());
        }
        return "距离 [" + targetTime.format(FMT) + "] 还有 " + human(d);
    }

    private String fmtLoad(double load) {
        if (load < 0) {
            return "不可用";
        }
        return String.format(Locale.ROOT, "%.2f（1 分钟平均）", load);
    }

    private String orNone(String v) {
        return (v == null || v.isBlank()) ? "无" : v;
    }

    private String human(Duration d) {
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        long seconds = d.getSeconds() % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" 天 ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append(" 小时 ");
        }
        sb.append(minutes).append(" 分 ").append(seconds).append(" 秒");
        return sb.toString().trim();
    }
}
