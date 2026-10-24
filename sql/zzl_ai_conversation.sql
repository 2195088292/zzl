-- AI 会话长期存储。执行一次即可；Redis 仍作为热点缓存，Redis 不可用时应用会退回数据库/内存。
CREATE TABLE IF NOT EXISTS `zzl_ai_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scope` VARCHAR(64) NOT NULL COMMENT '用户作用域，通常为用户 ID',
  `conversation_id` VARCHAR(80) NOT NULL COMMENT '前端会话 ID',
  `title` VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
  `model` VARCHAR(80) NOT NULL DEFAULT '' COMMENT '最近使用的模型',
  `turns` LONGTEXT NOT NULL COMMENT '消息 JSON 数组',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_conversation_scope_id` (`scope`, `conversation_id`),
  KEY `idx_ai_conversation_scope_updated` (`scope`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话记录';
