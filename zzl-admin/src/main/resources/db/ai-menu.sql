-- AI 菜单首次迁移；标记存在后不再补建，管理员的删除、改名、排序、停用均保留。
-- 只授权已有 admin 角色，普通角色在角色管理中自行勾选。
SET @ai_seed = NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'ai.menu.version');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT 'AI 智能体', 0, 5, 'ai', '', 'AiAgent', 1, 1, 'M', '0', '0', '', 'skill', 'ai-menu-v1', NOW()
WHERE @ai_seed = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = 'ai' AND menu_type = 'M');
SET @ai_root = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = 0 AND path = 'ai' AND menu_type = 'M');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT 'AI 工作台', @ai_root, 1, 'workbench', 'ai/workbench/index', 'AiWorkbench', 1, 1, 'C', '0', '0', 'ai:workbench:query', 'star', 'ai-menu-v1', NOW()
WHERE @ai_seed = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @ai_root AND path = 'workbench' AND menu_type = 'C');
SET @ai_workbench = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = @ai_root AND path = 'workbench' AND menu_type = 'C');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '模型管理', @ai_root, 2, 'models', 'ai/models/index', 'AiModels', 1, 1, 'C', '0', '0', 'ai:model:list', 'tool', 'ai-menu-v1', NOW()
WHERE @ai_seed = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @ai_root AND path = 'models' AND menu_type = 'C');
SET @ai_models = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = @ai_root AND path = 'models' AND menu_type = 'C');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '知识库', @ai_root, 3, 'knowledge', 'ai/knowledge/index', 'AiKnowledgeBase', 1, 1, 'C', '0', '0', 'ai:knowledge:list', 'documentation', 'ai-menu-v1', NOW()
WHERE @ai_seed = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @ai_root AND path = 'knowledge' AND menu_type = 'C');
SET @ai_knowledge = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = @ai_root AND path = 'knowledge' AND menu_type = 'C');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT seed.menu_name, seed.parent_id, seed.order_num, '#', 1, 1, 'F', '0', '0', seed.perms, '#', 'ai-menu-v1', NOW()
FROM (
  SELECT @ai_workbench AS parent_id, '发送与停止' AS menu_name, 1 AS order_num, 'ai:chat:send' AS perms
  UNION ALL
  SELECT @ai_workbench, '删除会话与附件', 2, 'ai:chat:remove'
  UNION ALL
  SELECT @ai_workbench, '上传附件', 3, 'ai:file:upload'
  UNION ALL
  SELECT @ai_workbench, '重建附件索引', 4, 'ai:file:index'
  UNION ALL
  SELECT @ai_models, '新增模型', 1, 'ai:model:add'
  UNION ALL
  SELECT @ai_models, '修改模型', 2, 'ai:model:edit'
  UNION ALL
  SELECT @ai_models, '删除模型', 3, 'ai:model:remove'
  UNION ALL
  SELECT @ai_models, '获取目录与连接测试', 4, 'ai:model:query'
  UNION ALL
  SELECT @ai_knowledge, '新增知识库', 1, 'ai:knowledge:add'
  UNION ALL
  SELECT @ai_knowledge, '修改知识库与配置', 2, 'ai:knowledge:edit'
  UNION ALL
  SELECT @ai_knowledge, '删除知识库与文档', 3, 'ai:knowledge:remove'
  UNION ALL
  SELECT @ai_knowledge, '上传文档', 4, 'ai:knowledge:upload'
  UNION ALL
  SELECT @ai_knowledge, '管理索引任务', 5, 'ai:knowledge:index'
  UNION ALL
  SELECT @ai_knowledge, '检索测试', 6, 'ai:knowledge:query'
) seed
WHERE @ai_seed = 1 AND seed.parent_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms = seed.perms);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id FROM sys_role r CROSS JOIN sys_menu m
WHERE @ai_seed = 1 AND r.role_key = 'admin' AND r.del_flag = '0'
  AND (m.menu_id = @ai_root OR m.parent_id IN (@ai_root, @ai_workbench, @ai_models, @ai_knowledge))
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 'AI 菜单迁移版本', 'ai.menu.version', '1', 'Y', 'ai-menu-v1', NOW(), '初始化完成后，AI 菜单及授权由菜单管理与角色管理维护'
WHERE @ai_seed = 1;
