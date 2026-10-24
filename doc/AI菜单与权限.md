# AI 菜单与权限

AI 工作台、模型管理、知识库通过后台 `sys_menu` 和 `/getRouters` 加载，前端不再保留固定 AI 路由。

## 使用

1. 首次启动新版后端时，自动导入 AI 目录、三个页面和操作权限。
2. 管理员在“系统管理 → 菜单管理”中调整名称、顺序、显示和停用状态。
3. 在“系统管理 → 角色管理 → 修改 → 菜单权限”中勾选需要的 AI 页面和操作。普通角色不会自动获得新功能。
4. 用户退出并重新登录，使路由和权限更新。

| 页面 | 进入页面 | 可分别授权的操作 |
| --- | --- | --- |
| AI 工作台 / 顶栏简聊 | `ai:workbench:query` | `ai:chat:send` 发送与停止；`ai:chat:remove` 删除会话与附件；`ai:file:upload` 上传附件；`ai:file:index` 重建附件索引 |
| 模型管理 | `ai:model:list` | `ai:model:add` 新增；`ai:model:edit` 修改；`ai:model:remove` 删除；`ai:model:query` 获取目录与连接测试 |
| 知识库 | `ai:knowledge:list` | `ai:knowledge:add` 新增；`ai:knowledge:edit` 修改与保存配置；`ai:knowledge:remove` 删除知识库与文档；`ai:knowledge:upload` 上传；`ai:knowledge:index` 索引管理；`ai:knowledge:query` 检索测试 |

只授予工作台权限时，仍能读取当前账号的模型选项和知识库选项，不需要开放模型管理。只授予管理页面查询权限时，表单只读，操作按钮按权限显示；接口也使用框架现有 `@ss` 方法权限校验。

## 迁移

唯一 SQL 位于 `zzl-admin/src/main/resources/db/ai-menu.sql`，可在需要时手动执行。`AiMenuInitializer` 在启动时事务执行同一文件。

迁移成功后保存系统参数 `ai.menu.version=1`，以后不会重复创建目录或覆盖修改。删除、停用、排序和改名不会因后端重启恢复；不要为刷新权限删除这个迁移标记。

## 验证

- `AiPermissionTest`：使用真实 Spring 方法权限拦截器，覆盖未授权访问、聊天用户选择模型、模型只读与知识库只读。
- `AiMenuMysqlIntegrationTest`：设置 `ZZL_AI_MYSQL_SMOKE=true` 后运行。使用真实 MySQL 和菜单 Mapper，验证首次导入、角色路由和重复启动保留修改；事务整体回滚。
- `AiMenuBrowserSmokeTest`：仅在设置 `ZZL_AI_BROWSER_SMOKE=true` 时运行，需本地 8080 后端已启动。创建四组临时角色和会话，验证真实 HTTP 路由及拒绝结果，然后在 `output/diagnostics/ai-browser-sessions.json` 提供仅供本地浏览器联调的短期会话。浏览器完成后创建 `output/diagnostics/ai-browser-done.flag`。五分钟超时或正常结束均清理临时账号、角色、Redis 会话和凭证文件。不要将会话文件发出或提交到 Git。

登录与注册两处品牌标记共用 `WorkspaceMark` 内联 SVG，图标随当前深浅底色显示，不依赖外部图片或字体。
