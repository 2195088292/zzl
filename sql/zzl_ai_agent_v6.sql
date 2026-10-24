-- V6 Agent 工作台持久化：运行记录、消息明细、附件元数据。
-- 文件正文仍由 zzl.profile/ai-files/{scope} 保存；本表保存可检索的元数据。

CREATE TABLE IF NOT EXISTS `zzl_ai_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` VARCHAR(64) NOT NULL COMMENT '运行 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `conversation_id` VARCHAR(80) NOT NULL COMMENT '会话 ID',
  `model` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '模型 ID',
  `question` LONGTEXT NULL COMMENT '用户问题',
  `status` VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCEEDED/FAILED',
  `started_at` DATETIME NOT NULL COMMENT '开始时间',
  `ended_at` DATETIME NULL COMMENT '结束时间',
  `duration_ms` BIGINT NULL COMMENT '耗时毫秒',
  `answer` LONGTEXT NULL COMMENT '最终回答',
  `error_message` VARCHAR(2000) NULL COMMENT '错误信息',
  `events` LONGTEXT NOT NULL COMMENT 'Agent 事件 JSON 数组',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_run_id` (`run_id`),
  KEY `idx_ai_run_scope_conversation` (`scope`, `conversation_id`, `started_at`),
  KEY `idx_ai_run_scope_status` (`scope`, `status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Agent 运行记录';

CREATE TABLE IF NOT EXISTS `zzl_ai_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `message_id` VARCHAR(64) NOT NULL COMMENT '消息 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `conversation_id` VARCHAR(80) NOT NULL COMMENT '会话 ID',
  `run_id` VARCHAR(64) NULL COMMENT '运行 ID',
  `role` VARCHAR(20) NOT NULL COMMENT 'user/assistant/tool',
  `content` LONGTEXT NOT NULL COMMENT '消息内容',
  `model` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '模型 ID',
  `token_count` INT NULL COMMENT '估算 Token 数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_message_id` (`message_id`),
  KEY `idx_ai_message_scope_conversation` (`scope`, `conversation_id`, `created_at`),
  KEY `idx_ai_message_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话消息明细';

CREATE TABLE IF NOT EXISTS `zzl_ai_attachment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file_id` VARCHAR(64) NOT NULL COMMENT '文件 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `conversation_id` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '会话 ID',
  `original_name` VARCHAR(255) NOT NULL COMMENT '原文件名',
  `extension` VARCHAR(32) NOT NULL DEFAULT '' COMMENT '扩展名',
  `content_type` VARCHAR(128) NULL COMMENT '媒体类型',
  `size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小',
  `path` VARCHAR(500) NULL COMMENT '服务器文件路径',
  `extracted_chars` INT NOT NULL DEFAULT 0 COMMENT '抽取字符数',
  `text_extracted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否抽取成功',
  `extract_message` VARCHAR(500) NULL COMMENT '抽取提示',
  `uploaded_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_attachment_file` (`file_id`),
  KEY `idx_ai_attachment_scope_conversation` (`scope`, `conversation_id`, `uploaded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 工作区附件元数据';

-- V6.2 文档 RAG 切片。保存关键词、原文位置和可选 embedding 向量。
CREATE TABLE IF NOT EXISTS `zzl_ai_document_chunk` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chunk_id` VARCHAR(64) NOT NULL COMMENT '切片 ID',
  `file_id` VARCHAR(64) NOT NULL COMMENT '文件 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `conversation_id` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '会话 ID',
  `source_name` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '来源文件名',
  `chunk_index` INT NOT NULL COMMENT '切片序号',
  `start_offset` INT NOT NULL DEFAULT 0 COMMENT '原文起始字符位置',
  `end_offset` INT NOT NULL DEFAULT 0 COMMENT '原文结束字符位置',
  `content` LONGTEXT NOT NULL COMMENT '切片正文',
  `keywords` VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '检索关键词',
  `embedding` LONGTEXT NULL COMMENT '文本向量 JSON',
  `embedding_model` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '向量模型',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_document_chunk_id` (`chunk_id`),
  KEY `idx_ai_document_chunk_file` (`scope`, `file_id`, `chunk_index`),
  KEY `idx_ai_document_chunk_conversation` (`scope`, `conversation_id`, `chunk_index`),
  FULLTEXT KEY `ft_ai_document_chunk_text` (`content`, `keywords`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 文档 RAG 切片';

-- V6.3 RAG 索引任务。任务状态与重试次数落库，应用重启后可恢复 QUEUED/RUNNING 任务。
CREATE TABLE IF NOT EXISTS `zzl_ai_rag_index_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` VARCHAR(64) NOT NULL COMMENT '索引任务 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `file_id` VARCHAR(64) NOT NULL COMMENT '文件 ID',
  `file_name` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件名',
  `status` VARCHAR(24) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/UNSUPPORTED',
  `chunks` INT NOT NULL DEFAULT 0 COMMENT '写入切片数',
  `message` VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '状态说明',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '执行次数',
  `progress` INT NOT NULL DEFAULT 0 COMMENT '索引进度',
  `stage` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '当前阶段',
  `index_signature` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '索引配置版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `started_at` DATETIME NULL COMMENT '开始时间',
  `finished_at` DATETIME NULL COMMENT '结束时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_index_task_id` (`task_id`),
  KEY `idx_ai_rag_index_task_file` (`scope`, `file_id`, `created_at`),
  KEY `idx_ai_rag_index_task_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI RAG 索引任务';

CREATE TABLE IF NOT EXISTS `zzl_ai_knowledge_base` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `knowledge_base_id` VARCHAR(64) NOT NULL COMMENT '知识库 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `name` VARCHAR(128) NOT NULL COMMENT '知识库名称',
  `description` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '知识库说明',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `chunk_size` INT NOT NULL DEFAULT 1200 COMMENT '切片大小',
  `chunk_overlap` INT NOT NULL DEFAULT 180 COMMENT '切片重叠长度',
  `top_k` INT NOT NULL DEFAULT 6 COMMENT '召回数量',
  `similarity_threshold` DECIMAL(6,4) NOT NULL DEFAULT 0.1000 COMMENT '向量相似度阈值',
  `rerank_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用规则重排',
  `embedding_model` VARCHAR(80) NOT NULL DEFAULT 'global' COMMENT '向量模型标识',
  `embedding_config` LONGTEXT NULL COMMENT '独立向量服务配置',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_knowledge_scope_id` (`scope`, `knowledge_base_id`),
  KEY `idx_ai_knowledge_scope_updated` (`scope`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 知识库';

CREATE TABLE IF NOT EXISTS `zzl_ai_knowledge_document` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `knowledge_base_id` VARCHAR(64) NOT NULL COMMENT '知识库 ID',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `file_id` VARCHAR(64) NOT NULL COMMENT '附件 ID',
  `title` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文档标题',
  `index_signature` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '已完成索引配置版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_knowledge_document_file` (`scope`, `knowledge_base_id`, `file_id`),
  KEY `idx_ai_knowledge_document_base` (`scope`, `knowledge_base_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 知识库文档';

-- AI 用户模型配置。运行时模型按用户作用域持久化到 MySQL。
CREATE TABLE IF NOT EXISTS `zzl_ai_model_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域',
  `model_id` VARCHAR(64) NOT NULL COMMENT '模型标识',
  `label` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '显示名称',
  `base_url` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '兼容接口地址',
  `api_key` TEXT NULL COMMENT '模型 API Key',
  `model_name` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '模型名称',
  `model_catalog` LONGTEXT NULL COMMENT '可选模型目录 JSON',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `temperature` DECIMAL(5,3) NOT NULL DEFAULT 0.2 COMMENT '温度',
  `max_tokens` INT NOT NULL DEFAULT 1200 COMMENT '最大 Token',
  `provider_type` VARCHAR(24) NOT NULL DEFAULT 'auto' COMMENT '厂商类型',
  `capability_protocol` VARCHAR(48) NOT NULL DEFAULT 'auto' COMMENT '模型能力协议',
  `capabilities` LONGTEXT NULL COMMENT '模型能力 JSON',
  `reasoning_effort` VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT '推理强度',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_model_scope_id` (`scope`, `model_id`),
  KEY `idx_ai_model_scope_updated` (`scope`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 用户模型配置';
