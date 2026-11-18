package org.spring.claude.spring_claude_tools.utils;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 业务数据源（MySQL）显式声明。
 *
 * <p>背景：引入 PGVector 后，容器里会同时存在两个 JDBC 数据源 ——
 * 业务库（MySQL，{@code spring.datasource.*}）与向量库（PostgreSQL）。
 * 若不加区分，{@code DatabaseTools} 的构造注入会因为「找到多个候选 Bean」而失败。
 *
 * <p>解决方式：把业务库标记为 {@code @Primary}，这样所有未指定限定符的注入点
 * （包括 {@code DatabaseTools} 与 Spring Boot 自动装配的 {@code JdbcTemplate}）
 * 都会默认拿到 MySQL；PGVector 则使用本类提供的独立 {@code pgVectorDataSource}。
 *
 * <p>注意：Spring AI 2.x 的 {@code PgVectorStoreProperties} 不再有 url/username/password
 * 字段（旧版独立建连属性已移除），向量库连接必须由应用自己提供 DataSource —
 * 见 {@code RagConfiguration#vectorStore} 中按名字注入 {@code pgVectorDataSource}。
 */
@Configuration
public class DataSourceConfiguration {

    /**
     * 业务数据源：MySQL，连接参数沿用 {@code spring.datasource.*}。
     *
     * <p>用 {@code @ConfigurationProperties} 绑定而非逐项读取，
     * 这样新增连接池参数（如空闲超时、最大生命周期）无需改代码。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.username:}") String username,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:}") String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("business-mysql-pool");
        return ds;
    }

    /**
     * 向量库数据源：PostgreSQL（含 pgvector 扩展），读取
     * {@code spring.ai.vectorstore.pgvector.url/username/password}。
     *
     * <p>不标 {@code @Primary}：业务注入点默认仍拿 MySQL；PGVector 的
     * {@code VectorStore} Bean 在 {@code RagConfiguration} 中按 Bean 名指定本数据源。
     */
    @Bean
    public DataSource pgVectorDataSource(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.pgvector.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.pgvector.username:}") String username,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.pgvector.password:}") String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("pgvector-pool");
        return ds;
    }
}
