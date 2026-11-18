package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据库 / SQL 查询工具。
 *
 * <p>通过 Spring Boot 自动装配的 {@link DataSource}（HikariCP 连接池，
 * 由 {@code spring.datasource.*} 配置驱动）获取连接，提供表结构探查
 * 与 SQL 执行能力。
 *
 * <p>安全：默认只读（SELECT/SHOW/EXPLAIN/DESC/WITH），
 * 写操作需 {@code agent.db.allow-write=true}；单次最多返回 200 行。
 */
@Component
public class DatabaseTools implements AgentTool {

    private static final int MAX_ROWS = 200;

    private static final int MAX_COL_WIDTH = 200;

    /** Spring 自动注入的连接池数据源 */
    private final DataSource dataSource;

    private final boolean allowWrite;

    public DatabaseTools(DataSource dataSource,
            @Value("${agent.db.allow-write:false}") boolean allowWrite) {
        this.dataSource = dataSource;
        this.allowWrite = allowWrite;
    }

    @Override
    public boolean isAvailable() {
        return dataSource != null;
    }

    @Override
    public String unavailableReason() {
        return "DataSource 未注入，请检查 spring.datasource.* 配置";
    }

    // ---------------- 工具方法 ----------------

    @Tool(description = "列出当前 MySQL 库中的所有表及其注释，用于在写 SQL 前了解数据结构")
    public String listTables() {
        return withConnection(conn -> {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    String remark = rs.getString("REMARKS");
                    tables.add(remark == null || remark.isBlank() ? name : name + "  -- " + remark);
                }
            }
            if (tables.isEmpty()) {
                return "当前数据库中没有任何表";
            }
            return "共 " + tables.size() + " 张表：\n- " + String.join("\n- ", tables);
        });
    }

    @Tool(description = "查看指定表的字段结构（字段名、类型、长度、是否可空、注释）。写 SQL 前建议先调用")
    public String describeTable(
            @ToolParam(description = "表名，例如：sys_user") String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "查询失败：表名不能为空";
        }
        return withConnection(conn -> {
            List<String> cols = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, tableName.trim(), "%")) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    String nullable = rs.getInt("NULLABLE") == ResultSetMetaData.columnNullable ? "可空" : "非空";
                    String remark = rs.getString("REMARKS");
                    String line = col + " " + type + "(" + size + ") " + nullable;
                    cols.add(remark == null || remark.isBlank() ? line : line + "  -- " + remark);
                }
            }
            if (cols.isEmpty()) {
                return "未找到表 [" + tableName + "]，可先调用 listTables 查看可用表";
            }
            return "表 [" + tableName + "] 共 " + cols.size() + " 个字段：\n- " + String.join("\n- ", cols);
        });
    }

    @Tool(description = "执行 SQL 并以表格文本返回结果。默认只允许 SELECT / SHOW / EXPLAIN / DESC / WITH 查询；写操作（INSERT/UPDATE/DELETE/DDL）需开启 agent.db.allow-write。最多返回 200 行")
    public String executeSql(
            @ToolParam(description = "要执行的 SQL，例如：SELECT id, name FROM sys_user LIMIT 10") String sql) {

        if (sql == null || sql.isBlank()) {
            return "执行失败：SQL 不能为空";
        }
        String trimmed = sql.strip();
        if (!allowWrite && !isReadOnly(trimmed)) {
            return "拒绝执行：当前仅允许只读查询（SELECT/SHOW/EXPLAIN/DESC/WITH）。如需写操作请开启 agent.db.allow-write=true";
        }
        return withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(MAX_ROWS);
                boolean hasResultSet = stmt.execute(trimmed);
                if (!hasResultSet) {
                    return "执行成功，影响行数：" + stmt.getUpdateCount();
                }
                try (ResultSet rs = stmt.getResultSet()) {
                    return format(rs);
                }
            }
        });
    }

    // ---------------- 内部实现 ----------------

    /** 统一处理连接生命周期与异常，避免每个工具方法重复 try-catch */
    private String withConnection(SqlWork work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        } catch (SQLException e) {
            return "执行失败：" + e.getMessage();
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        String run(Connection conn) throws SQLException;
    }

    private boolean isReadOnly(String sql) {
        String head = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return head.startsWith("select") || head.startsWith("show") || head.startsWith("explain")
                || head.startsWith("desc") || head.startsWith("with");
    }

    private String format(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            headers.add(clip(meta.getColumnLabel(i)));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", headers)).append('\n');
        sb.append("-".repeat(Math.min(80, headers.size() * 6))).append('\n');
        int rowCount = 0;
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "NULL" : clip(String.valueOf(v)));
            }
            sb.append(String.join(" | ", row)).append('\n');
            rowCount++;
        }
        sb.append("共 ").append(rowCount).append(" 行");
        if (rowCount >= MAX_ROWS) {
            sb.append("（已达返回上限 ").append(MAX_ROWS).append(" 行，请加 LIMIT 或条件缩小范围）");
        }
        return sb.toString();
    }

    private String clip(String s) {
        if (s == null) {
            return "NULL";
        }
        return s.length() > MAX_COL_WIDTH ? s.substring(0, MAX_COL_WIDTH) + "..." : s;
    }
}
