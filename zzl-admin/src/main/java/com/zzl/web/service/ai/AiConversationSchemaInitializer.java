package com.zzl.web.service.ai;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 开发环境自动准备会话表；也可直接执行 sql/zzl_ai_conversation.sql。 */
@Component
public class AiConversationSchemaInitializer
{
    private static final Logger log = LoggerFactory.getLogger(AiConversationSchemaInitializer.class);

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_conversation ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "conversation_id VARCHAR(80) NOT NULL COMMENT '会话 ID',"
            + "title VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',"
            + "model VARCHAR(80) NOT NULL DEFAULT '' COMMENT '最近使用模型',"
            + "turns LONGTEXT NOT NULL COMMENT '消息 JSON 数组',"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_ai_conversation_scope_id (scope, conversation_id),"
            + "KEY idx_ai_conversation_scope_updated (scope, updated_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话记录'";

    private static final String CREATE_RUN_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_run ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "run_id VARCHAR(64) NOT NULL COMMENT '运行 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "conversation_id VARCHAR(80) NOT NULL COMMENT '会话 ID',"
            + "model VARCHAR(80) NOT NULL DEFAULT '' COMMENT '模型 ID',"
            + "question LONGTEXT COMMENT '用户问题',"
            + "status VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT '运行状态',"
            + "started_at DATETIME NOT NULL COMMENT '开始时间',"
            + "ended_at DATETIME NULL COMMENT '结束时间',"
            + "duration_ms BIGINT NULL COMMENT '耗时毫秒',"
            + "answer LONGTEXT COMMENT '最终回答',"
            + "error_message VARCHAR(2000) NULL COMMENT '错误信息',"
            + "events LONGTEXT NOT NULL COMMENT '事件 JSON 数组',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_run_id (run_id),"
            + "KEY idx_ai_run_scope_conversation (scope, conversation_id, started_at),"
            + "KEY idx_ai_run_scope_status (scope, status, started_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Agent 运行记录'";

    private static final String CREATE_MESSAGE_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_message ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "message_id VARCHAR(64) NOT NULL COMMENT '消息 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "conversation_id VARCHAR(80) NOT NULL COMMENT '会话 ID',"
            + "run_id VARCHAR(64) NULL COMMENT '运行 ID',"
            + "role VARCHAR(20) NOT NULL COMMENT '消息角色',"
            + "content LONGTEXT NOT NULL COMMENT '消息内容',"
            + "model VARCHAR(80) NOT NULL DEFAULT '' COMMENT '模型 ID',"
            + "token_count INT NULL COMMENT '估算 Token 数',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_message_id (message_id),"
            + "KEY idx_ai_message_scope_conversation (scope, conversation_id, created_at),"
            + "KEY idx_ai_message_run (run_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话消息明细'";

    private static final String CREATE_ATTACHMENT_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_attachment ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "file_id VARCHAR(64) NOT NULL COMMENT '文件 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "conversation_id VARCHAR(80) NOT NULL DEFAULT '' COMMENT '会话 ID',"
            + "original_name VARCHAR(255) NOT NULL COMMENT '原文件名',"
            + "extension VARCHAR(32) NOT NULL DEFAULT '' COMMENT '扩展名',"
            + "content_type VARCHAR(128) NULL COMMENT '媒体类型',"
            + "size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小',"
            + "path VARCHAR(500) NULL COMMENT '服务器文件路径',"
            + "extracted_chars INT NOT NULL DEFAULT 0 COMMENT '抽取字符数',"
            + "text_extracted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否抽取成功',"
            + "extract_message VARCHAR(500) NULL COMMENT '抽取提示',"
            + "uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_attachment_file (file_id),"
            + "KEY idx_ai_attachment_scope_conversation (scope, conversation_id, uploaded_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 工作区附件元数据'";

    private static final String CREATE_DOCUMENT_CHUNK_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_document_chunk ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "chunk_id VARCHAR(64) NOT NULL COMMENT '切片 ID',"
            + "file_id VARCHAR(64) NOT NULL COMMENT '文件 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "conversation_id VARCHAR(80) NOT NULL DEFAULT '' COMMENT '会话 ID',"
            + "source_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '来源文件名',"
            + "chunk_index INT NOT NULL COMMENT '切片序号',"
            + "start_offset INT NOT NULL DEFAULT 0 COMMENT '原文起始字符位置',"
            + "end_offset INT NOT NULL DEFAULT 0 COMMENT '原文结束字符位置',"
            + "content LONGTEXT NOT NULL COMMENT '切片正文',"
            + "keywords VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '检索关键词',"
            + "embedding LONGTEXT NULL COMMENT '文本向量 JSON',"
            + "embedding_model VARCHAR(80) NOT NULL DEFAULT '' COMMENT '向量模型',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_document_chunk_id (chunk_id),"
            + "KEY idx_ai_document_chunk_file (scope, file_id, chunk_index),"
            + "KEY idx_ai_document_chunk_conversation (scope, conversation_id, chunk_index)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 文档 RAG 切片'";

    private static final String CREATE_INDEX_TASK_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_rag_index_task ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "task_id VARCHAR(64) NOT NULL COMMENT '索引任务 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "file_id VARCHAR(64) NOT NULL COMMENT '文件 ID',"
            + "file_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件名',"
            + "status VARCHAR(24) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/UNSUPPORTED',"
            + "chunks INT NOT NULL DEFAULT 0 COMMENT '写入切片数',"
            + "message VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '状态说明',"
            + "attempts INT NOT NULL DEFAULT 0 COMMENT '执行次数',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "started_at DATETIME NULL COMMENT '开始时间',"
            + "finished_at DATETIME NULL COMMENT '结束时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_rag_index_task_id (task_id),"
            + "KEY idx_ai_rag_index_task_file (scope, file_id, created_at),"
            + "KEY idx_ai_rag_index_task_status (status, created_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI RAG 索引任务'";

    private static final String CREATE_MODEL_CONFIG_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_model_config ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "model_id VARCHAR(64) NOT NULL COMMENT '模型标识',"
            + "label VARCHAR(128) NOT NULL DEFAULT '' COMMENT '显示名称',"
            + "base_url VARCHAR(500) NOT NULL DEFAULT '' COMMENT '兼容接口地址',"
            + "api_key TEXT NULL COMMENT '模型 API Key',"
            + "model_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '模型名称',"
            + "model_catalog LONGTEXT NULL COMMENT '可选模型目录 JSON',"
            + "enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',"
            + "temperature DECIMAL(5,3) NOT NULL DEFAULT 0.2 COMMENT '温度',"
            + "max_tokens INT NOT NULL DEFAULT 1200 COMMENT '最大 Token',"
            + "provider_type VARCHAR(24) NOT NULL DEFAULT 'auto' COMMENT '厂商类型',"
            + "capability_protocol VARCHAR(48) NOT NULL DEFAULT 'auto' COMMENT '模型能力协议',"
            + "capabilities LONGTEXT NULL COMMENT '模型能力 JSON',"
            + "reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT '推理强度',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_model_scope_id (scope, model_id),"
            + "KEY idx_ai_model_scope_updated (scope, updated_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 用户模型配置'";

    private static final String CREATE_KNOWLEDGE_BASE_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_knowledge_base ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "knowledge_base_id VARCHAR(64) NOT NULL COMMENT '知识库 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "name VARCHAR(128) NOT NULL COMMENT '知识库名称',"
            + "description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '知识库说明',"
            + "enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',"
            + "chunk_size INT NOT NULL DEFAULT 1200 COMMENT '切片大小',"
            + "chunk_overlap INT NOT NULL DEFAULT 180 COMMENT '切片重叠长度',"
            + "top_k INT NOT NULL DEFAULT 6 COMMENT '召回数量',"
            + "similarity_threshold DECIMAL(6,4) NOT NULL DEFAULT 0.1000 COMMENT '向量相似度阈值',"
            + "rerank_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用规则重排',"
            + "embedding_model VARCHAR(80) NOT NULL DEFAULT 'global' COMMENT '向量模型标识',"
            + "embedding_config LONGTEXT NULL COMMENT '独立向量服务配置',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_knowledge_scope_id (scope, knowledge_base_id),"
            + "KEY idx_ai_knowledge_scope_updated (scope, updated_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 知识库'";

    private static final String CREATE_KNOWLEDGE_DOCUMENT_TABLE = "CREATE TABLE IF NOT EXISTS zzl_ai_knowledge_document ("
            + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "knowledge_base_id VARCHAR(64) NOT NULL COMMENT '知识库 ID',"
            + "scope VARCHAR(64) NOT NULL COMMENT '用户作用域',"
            + "file_id VARCHAR(64) NOT NULL COMMENT '附件 ID',"
            + "title VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文档标题',"
            + "index_signature VARCHAR(100) NOT NULL DEFAULT '' COMMENT '已完成索引配置版本',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
            + "PRIMARY KEY (id), UNIQUE KEY uk_ai_knowledge_document_file (scope, knowledge_base_id, file_id),"
            + "KEY idx_ai_knowledge_document_base (scope, knowledge_base_id, updated_at)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 知识库文档'";

    private final JdbcTemplate jdbcTemplate;

    public AiConversationSchemaInitializer(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize()
    {
        try
        {
            jdbcTemplate.execute(CREATE_TABLE);
            jdbcTemplate.execute(CREATE_RUN_TABLE);
            jdbcTemplate.execute(CREATE_MESSAGE_TABLE);
            jdbcTemplate.execute(CREATE_ATTACHMENT_TABLE);
            jdbcTemplate.execute(CREATE_DOCUMENT_CHUNK_TABLE);
            jdbcTemplate.execute(CREATE_INDEX_TASK_TABLE);
            jdbcTemplate.execute(CREATE_MODEL_CONFIG_TABLE);
            jdbcTemplate.execute(CREATE_KNOWLEDGE_BASE_TABLE);
            jdbcTemplate.execute(CREATE_KNOWLEDGE_DOCUMENT_TABLE);
            addColumnIfMissing("ALTER TABLE zzl_ai_document_chunk ADD COLUMN embedding LONGTEXT NULL COMMENT '文本向量 JSON'");
            addColumnIfMissing("ALTER TABLE zzl_ai_document_chunk ADD COLUMN embedding_model VARCHAR(80) NOT NULL DEFAULT '' COMMENT '向量模型'");
            addColumnIfMissing("ALTER TABLE zzl_ai_model_config ADD COLUMN model_catalog LONGTEXT NULL COMMENT '可选模型目录 JSON'");
            addColumnIfMissing("ALTER TABLE zzl_ai_model_config ADD COLUMN provider_type VARCHAR(24) NOT NULL DEFAULT 'auto' COMMENT '厂商类型'");
            addColumnIfMissing("ALTER TABLE zzl_ai_model_config ADD COLUMN capability_protocol VARCHAR(48) NOT NULL DEFAULT 'auto' COMMENT '模型能力协议'");
            addColumnIfMissing("ALTER TABLE zzl_ai_model_config ADD COLUMN capabilities LONGTEXT NULL COMMENT '模型能力 JSON'");
            addColumnIfMissing("ALTER TABLE zzl_ai_model_config ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT '推理强度'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN chunk_size INT NOT NULL DEFAULT 1200 COMMENT '切片大小'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 180 COMMENT '切片重叠长度'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN top_k INT NOT NULL DEFAULT 6 COMMENT '召回数量'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN similarity_threshold DECIMAL(6,4) NOT NULL DEFAULT 0.1000 COMMENT '向量相似度阈值'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN rerank_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用规则重排'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN embedding_model VARCHAR(80) NOT NULL DEFAULT 'global' COMMENT '向量模型标识'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_base ADD COLUMN embedding_config LONGTEXT NULL COMMENT '独立向量服务配置'");
            addColumnIfMissing("ALTER TABLE zzl_ai_knowledge_document ADD COLUMN index_signature VARCHAR(100) NOT NULL DEFAULT '' COMMENT '已完成索引配置版本'");
            addColumnIfMissing("ALTER TABLE zzl_ai_rag_index_task ADD COLUMN progress INT NOT NULL DEFAULT 0 COMMENT '索引进度'");
            addColumnIfMissing("ALTER TABLE zzl_ai_rag_index_task ADD COLUMN stage VARCHAR(255) NOT NULL DEFAULT '' COMMENT '当前阶段'");
            addColumnIfMissing("ALTER TABLE zzl_ai_rag_index_task ADD COLUMN index_signature VARCHAR(100) NOT NULL DEFAULT '' COMMENT '索引配置版本'");
            addIndexIfMissing("ALTER TABLE zzl_ai_document_chunk ADD FULLTEXT KEY ft_ai_document_chunk_text (content, keywords)");
        }
        catch (Exception error)
        {
            // 数据库不可用时，AiConversationStore 仍会退回 Redis/内存，不阻断应用启动。
            log.debug("AI 会话表自动初始化跳过：{}", error.getMessage());
        }
    }

    private void addColumnIfMissing(String sql)
    {
        try { jdbcTemplate.execute(sql); }
        catch (Exception ignored) { log.debug("AI RAG 字段已存在或当前数据库不支持升级"); }
    }

    private void addIndexIfMissing(String sql)
    {
        try { jdbcTemplate.execute(sql); }
        catch (Exception ignored) { log.debug("AI RAG 全文索引已存在或当前数据库不支持升级"); }
    }
}
