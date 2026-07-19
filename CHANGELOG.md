# Changelog

## v0.2.1 (2026-07-20)

### 手机 UI 重构
- 顶栏适配系统状态栏 (`statusBarsPadding`)，不再被遮挡
- 顶栏从浅蓝色改为白色毛玻璃质感
- 手机上移除侧边栏按钮（右滑打开），平板保留
- 底栏适配输入法 (`imePadding` + `navigationBarsPadding`)
- 发送按钮 "+" 改为 ↑ 箭头，圆形统一 44dp
- 发送按钮增加 ↑ 飞出动画
- 空输入时按钮使用线性图标

### 智能体系统
- "多 Agent" 重命名为 "智能体"（英文 Agents）
- 初始仅有 MengPaw，其他需新建
- "ACP 通讯录" 重命名为 "框架通讯录"
- 框架支持层级展开：框架 → 智能体
- 可调度框架显示 "已信任" 标识
- 长按智能体名称弹出菜单 → "申请智能体调度权限"
- 新建智能体自动创建 6 个初始化 .md 文件（AGENTS/SOUL/BOOTSTRAP/MEMORY/PROFILE/HEARTBEAT）

### Markdown + Emoji 渲染
- 新增 `MarkdownText` 组件：支持粗体、斜体、行内代码、链接、代码块、表格
- 所有聊天气泡支持全功能 Markdown 渲染
- Agent 消息移除强制等宽字体，Emoji 正常显示
- 长按文本支持系统选择 + 复制
- 新增 "大爆炸" 分词弹窗（BigBangPopup）

### 多会话架构
- AgentViewModel 从单例改为多会话 Map：每个智能体独立持有 AgentEngine + LlmProvider + 消息历史
- 切换智能体自动切换会话，消息历史隔离
- 每个智能体可独立配置模型
- 系统 prompt 包含智能体身份：名称、框架归属、驱动模型

### Agent 引擎升级
- 系统 prompt 注入中英双语 3 组 few-shot 示例（设备查询、文件操作、插件发现）
- ReAct 过程可折叠展示：思考 → 工具调用 → 观察结果
- Max steps 从 settings 传入（之前永远用默认 50）
- 错误信息根据 Agent 语言设置显示中文/英文

### 上下文缓存优化（Reasonix 移植，MIT）
- 四级折叠阈值：50% 软通知 → 60% 裁剪旧工具结果 → 80% 完整折叠 → 90% 强制折叠
- 陈旧工具结果裁剪（snip）：60% 时先改写旧 output 为短标记，避免触发昂贵的摘要 API 调用
- tokPerChar 动态校准：从真实 API usage 反算，替代硬编码 `/3`
- 折叠经济性检查：<400 tokens 跳过
- 卡死检测：连续两次折叠后暂停 + 警告

### 跨模型缓存优化
- DeepSeek：自动前缀缓存（PREFIX_STABLE）
- OpenAI / Kimi / GLM / Qwen：注入 `cache_control` 断点（CACHE_CONTROL）
- 设置界面显示 "已优化 ✓" 标签 + 缓存策略说明

### Dream 梦境模式
- WorkManager + 充电广播：仅接通电源 + Doze 空闲时触发
- LLM 分析：Scroll 索引（62x 压缩）+ 记忆 → 单次 API 调用 → DREAM.md（≤500 字）
- 自动工作区清理：3 天前截图、过期 inbox、空缓存目录
- 存储空间汇报 + dream.log 持久化

### 中间件架构
- `AgentMiddleware` — fun interface，零分配 SAM
- `PostCallMiddleware` — LLM 调用后处理
- `PromptBuilder` — 锚点式 prompt 组装
- `ScrollContext` — LinkedHashMap LRU + 文本冷存储

### 致谢
- 重写 ATTRIBUTIONS.md，严格分离「代码参考」与「灵感来源」
- Reasonix (MIT) / QwenPaw (Apache 2.0) / ReAct
