package com.example.difyraglab;

import com.example.difyraglab.config.DifyProperties;
import com.example.difyraglab.config.RewriteProperties;
import com.example.difyraglab.config.WeaviateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Dify RAG Lab 入口。
 *
 * <p>学习目标：理解 Dify 如何使用向量数据库（Weaviate）。
 * 本服务提供三层能力：
 * <ol>
 *   <li>Dify Service API 封装（建库/传文档/检索/问答）</li>
 *   <li>Weaviate 直查（schema / 对象 / nearVector / BM25）</li>
 *   <li>检索对照实验：Dify 检索 vs Java 自实现检索</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties({DifyProperties.class, WeaviateProperties.class, RewriteProperties.class})
public class DifyRagLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DifyRagLabApplication.class, args);
    }
}
