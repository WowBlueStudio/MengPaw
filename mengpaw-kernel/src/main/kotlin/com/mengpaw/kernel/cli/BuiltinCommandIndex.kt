// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * 内置命令索引工厂 — 为所有 ~150 条内核/插件命令生成 BM25 检索索引.
 *
 * 每条命令配备: 中英文同义词表 (BM25 的 "树枝"), 描述, 用法.
 * Agent 用自然语言查询时, 同义词桥接 LLM 用词和命令描述之间的语义鸿沟.
 */
object BuiltinCommandIndex {

    /** 构建完整命令索引并注册到 CommandSearch. 在 AgentEngine 初始化时调用一次. */
    fun buildAll() {
        // ── self: Agent 自我管理 (16) ──
        idx("self.status", "查看 Agent 当前运行状态 (在线/空闲/版本)", "self.status",
            listOf("状态", "运行", "在线", "检查", "信息", "概况"),
            listOf("status", "state", "info", "health", "check", "running"))
        idx("self.config", "查看或修改 Agent 全局配置 (API Key/Provider/模型/语言/代理)", "self.config [key=value]",
            listOf("配置", "设置", "参数", "API", "Key", "Provider", "模型", "修改", "切换"),
            listOf("config", "settings", "configure", "setup", "API", "provider", "model"))
        idx("self.stats", "查看 token 缓存统计和命中率", "self.stats",
            listOf("统计", "token", "缓存", "命中率", "用量", "消耗"),
            listOf("stats", "statistics", "token", "cache", "hit rate", "usage"))
        idx("self.version", "查看当前 MengPaw 框架版本号", "self.version",
            listOf("版本", "版本号", "升级", "更新"),
            listOf("version", "release", "upgrade"))
        idx("self.avatar", "切换 Agent 头像 (预设或自定义)", "self.avatar [name|file]",
            listOf("头像", "图标", "肖像", "换头像", "更换"),
            listOf("avatar", "icon", "portrait", "image"))
        idx("self.theme", "切换 UI 主题配色 (亮色/暗色/跟随系统)", "self.theme [light|dark|system]",
            listOf("主题", "颜色", "配色", "暗色", "亮色", "深色", "浅色", "外观"),
            listOf("theme", "color", "dark", "light", "appearance", "style"))
        idx("self.mcp", "查看 MCP 协议配置和已连接的 MCP 服务列表", "self.mcp",
            listOf("MCP", "协议", "服务", "连接", "设备", "工具"),
            listOf("MCP", "protocol", "service", "connect", "device", "tool"))
        idx("self.trigger", "查看和管理已注册的触发器 (Cron/Lifetime)", "self.trigger [ls|add|rm]",
            listOf("触发器", "定时", "Cron", "计划任务", "自动", "唤醒"),
            listOf("trigger", "cron", "schedule", "timer", "automatic", "wake"))
        idx("self.acp", "查看 ACP 框架通讯协议状态和已配对设备", "self.acp",
            listOf("ACP", "框架", "通讯", "发现", "设备", "配对", "节点"),
            listOf("ACP", "framework", "discovery", "peer", "device", "pair"))
        idx("self.tools", "列出指定命名空间下的所有可用命令 (完整遍历)", "self.tools [namespace]",
            listOf("工具", "命令", "列表", "命名空间", "所有", "可用", "帮助", "手册"),
            listOf("tools", "commands", "list", "namespace", "help", "available", "manual"))
        idx("self.search", "用自然语言搜索可用命令 (BM25 索引, 同义词桥接)", "self.search <自然语言描述> [--top N]",
            listOf("搜索", "查找", "命令", "发现", "自然语言", "帮助"),
            listOf("search", "find", "command", "discover", "natural language", "help"))
        idx("self.search.stats", "查看命令搜索索引统计 (条数/命名空间覆盖)", "self.search.stats",
            listOf("搜索统计", "索引", "条数", "覆盖", "统计"),
            listOf("search", "stats", "index", "coverage", "count"))
        idx("self.ports", "查看网络端口与接口一览 (监听/外联/可配置)", "self.ports [--json]",
            listOf("端口", "网络", "接口", "监听", "协议"),
            listOf("ports", "network", "interface", "listen", "protocol"))
        idx("self.time", "获取当前日期时间 (支持多种格式)", "self.time [iso|date|time|timestamp]",
            listOf("时间", "日期", "当前", "时钟", "今天", "现在"),
            listOf("time", "date", "current", "now", "today", "clock"))
        idx("self.notify.message", "Agent 主动向用户聊天推送一条消息 (System 角色)", "self.notify.message <text>",
            listOf("通知", "推送", "消息", "提醒", "发送", "告知"),
            listOf("notify", "push", "message", "alert", "send", "inform"))
        idx("self.notify.banner", "Agent 向用户显示顶部横幅通知 (4 秒消失)", "self.notify.banner <text> [--level info|success|warn|error]",
            listOf("横幅", "通知", "弹窗", "提示", "警告", "成功"),
            listOf("banner", "notify", "popup", "alert", "warning", "success"))

        // ── swarm: 火种模式运行时状态 (v0.35.5) ──
        idx("swarm.status", "查看火种模式 (Swarm/Fleet) 进行中或未完成的运行时状态 (任务/步数预算/子任务进度)", "swarm.status",
            listOf("火种", "Swarm", "Fleet", "进度", "子任务", "预算", "状态", "并行"),
            listOf("swarm", "fleet", "progress", "subtask", "budget", "status", "parallel"))
        idx("swarm.run", "主动以火种模式执行任务 (拆解→并行 Worker→验证→合成; 评分 8+ 也会自动进入)", "swarm.run <任务>",
            listOf("火种", "Swarm", "并行", "执行", "拆解", "Fleet", "任务"),
            listOf("swarm", "fleet", "run", "parallel", "decompose", "execute"))

        // ── evolution: Agent 进化 (从失败中学习) ──
        idx("evolution.audit", "查看进化绩效: 失败分布/复现率/已沉淀教训", "evolution.audit",
            listOf("进化", "绩效", "失败", "复盘", "教训", "复现", "自省"),
            listOf("evolution", "audit", "failure", "lessons", "review", "repeat"))
        idx("evolution.report", "发现框架缺陷时写技术反馈给开发者 (落盘+推送)", "evolution.report <描述>",
            listOf("反馈", "缺陷", "Bug", "框架", "报错", "上报"),
            listOf("report", "feedback", "bug", "framework", "issue"))
        idx("evolution.feedback", "查看/标记框架反馈状态 (new/ack/scheduled/fixed 闭环)", "evolution.feedback [ls|mark <文件> <状态>]",
            listOf("反馈", "状态", "闭环", "已读", "已修复", "已排期"),
            listOf("feedback", "status", "closed loop", "ack", "fixed", "scheduled"))
        idx("evolution.learn.command", "把正确命令用法/同义词登记进指令集搜索索引", "evolution.learn.command <命令> <描述> [--keywords 词,词]",
            listOf("指令", "命令", "学习", "关键词", "索引", "丰富"),
            listOf("learn", "command", "keyword", "index"))
        idx("evolution.reactions", "查看用户反应档案 (用户如何纠正过我 — 用户分身数据源)", "evolution.reactions",
            listOf("用户", "反应", "纠正", "反馈", "档案", "偏好"),
            listOf("reactions", "correction", "feedback", "user"))
        // 两组合并注册 (CommandSearch.register 同 fullName 已存在时不覆盖 —
        // 此前第二组关键词 "修正/教训/已沉淀/完成/fixed/resolved" 被静默忽略, 永久失效)
        idx("evolution.mark-corrected", "标记某条失败模式已沉淀修正 (绩效闭环)", "evolution.mark-corrected <failure-id>",
            listOf("标记", "已修正", "失败", "闭环", "绩效", "修正", "教训", "已沉淀", "完成"),
            listOf("mark", "corrected", "failure", "closed loop", "resolve", "fixed", "resolved"))

        // ── agent: 文档/记忆管理 ──
        idx("agent.docs", "列出 Agent 工作区的所有文档文件 (Soul/Agents/Memory/Boost/Profile)", "agent.docs",
            listOf("文档", "工作区", "文件", "列表", "所有", "Soul", "Profile", "Agents"),
            listOf("docs", "documents", "workspace", "files", "list", "soul", "profile"))
        idx("agent.cli", "命令发现指引 (CLI.md 已移除 — 完整命令用 self.tools / self.search)", "agent.cli",
            listOf("CLI", "命令", "指引", "参考", "帮助", "文档"),
            listOf("CLI", "command", "guide", "reference", "help"))
        idx("agent.profile", "查看 Agent 身份档案 (名称/角色/偏好)", "agent.profile",
            listOf("档案", "身份", "Profile", "角色", "偏好", "介绍"),
            listOf("profile", "identity", "role", "preference", "intro"))
        idx("agent.soul", "查看 Agent 核心灵魂设定和行为准则", "agent.soul",
            listOf("灵魂", "Soul", "设定", "准则", "性格", "行为", "规则"),
            listOf("soul", "personality", "rules", "behavior", "character"))
        idx("agent.boost", "阅读 Agent 初始化引导手册 (新 Agent 第一步)", "agent.boost",
            listOf("引导", "入门", "初始化", "新手", "教程", "Boost", "手册"),
            listOf("boost", "guide", "tutorial", "init", "getting started", "onboarding"))
        idx("agent.modes", "查看斜杠命令模式菜单 (7 种执行模式说明)", "agent.modes",
            listOf("模式", "斜杠", "命令菜单", "执行方式", "Swarm", "Goal", "Plan", "Fleet", "Research", "Silent"),
            listOf("modes", "slash", "command menu", "execution mode", "Swarm", "Goal", "Plan", "Fleet", "Research", "Silent"))
        idx("agent.boost.delete", "删除 Agent 引导加速文件", "agent.boost.delete",
            listOf("删除", "Boost", "引导", "清理"),
            listOf("delete", "remove", "boost", "clean"))
        // memory.* 三轨入口化 (P2-10): 20+ 子命令曾稀释 BM25 — self.search "记忆" 返回
        // 整页近义词条目。现按三轨分组, 同组搜索只保留得分最高一条 (组内共享入口关键词):
        // memory.long = 长期记忆入口 (keep/write/edit/rm/read), memory.mid = 中期记忆入口
        // (record/mid*), memory.project = 项目记忆入口 (project*), memory.core = 核心入口
        // (memory + 梦境 — 梦境本质是记忆管理整理通道); memory.search/stats 不分组,
        // 作为独立入口出现在通用"记忆"查询结果中。
        idx("agent.memory", "查看长期记忆 (已注入系统提示词, 最重要的记忆)", "agent.memory [query]",
            listOf("记忆", "长期", "查看", "已记住", "读记忆", "Memory"),
            listOf("memory", "long-term", "view", "remember", "read", "recall"),
            group = "memory.core")
        idx("agent.memory.keep", "写长期记忆 — 用户说记住/你判断重要时 (注入系统提示词)", "agent.memory.keep <内容>",
            listOf("持久化", "保存", "记住", "存储", "记录", "写记忆", "Keep", "重要"),
            listOf("persist", "save", "remember", "store", "write memory", "keep", "important"),
            group = "memory.long")
        idx("agent.memory.record", "写中期记忆 — 对话摘要/值得回溯的临时信息 (梦境自动整理, 不编辑)", "agent.memory.record <内容>",
            listOf("记录", "中期", "摘要", "对话", "日志", "临时", "日记"),
            listOf("record", "mid-term", "summary", "log", "note", "daily", "journal"),
            group = "memory.mid")
        idx("agent.memory.read", "按 ID 读取一条记忆 (跨长期/中期/项目三轨)", "agent.memory.read <id>",
            listOf("读", "读取", "条目", "时间戳", "单条", "Read"),
            listOf("read", "entry", "timestamp", "single", "fetch"),
            group = "memory.long")
        idx("agent.memory.search", "跨轨搜索记忆 (长期/中期/项目, 默认全轨)", "agent.memory.search <关键词> [--track long|mid|project]",
            listOf("搜索", "检索", "查找", "关键词", "查询", "Search"),
            listOf("search", "query", "find", "keyword", "lookup"))
        idx("agent.memory.stats", "记忆统计 (三轨条数/日期分布/项目数)", "agent.memory.stats",
            listOf("统计", "数量", "概览", "报告", "Stats"),
            listOf("stats", "count", "overview", "report", "summary"))
        idx("agent.memory.write", "按指定 ID 写长期记忆 (已存在则更新)", "agent.memory.write <id> <内容>",
            listOf("写入", "指定", "更新", "标题", "Write", "存储"),
            listOf("write", "update", "store", "title", "create"),
            group = "memory.long")
        idx("agent.memory.mid", "查看中期记忆 — 用户提及「某日聊过…」时按日期查 (不注入提示词)", "agent.memory.mid [日期|关键词]",
            listOf("中期", "记忆", "日", "历史", "查阅", "过往", "回顾"),
            listOf("mid", "history", "daily", "review", "past", "query"),
            group = "memory.mid")
        idx("agent.memory.mid.delete", "删除中期分片 — 梦境自动整理, Agent 一般不使用", "agent.memory.mid.delete <日期>",
            listOf("删除", "中期记忆", "清理", "日"),
            listOf("delete", "mid", "remove", "clean", "daily"),
            group = "memory.mid")
        idx("agent.memory.mid.rm", "删中期条目 — Agent 一般不使用 (梦境自动整理)", "agent.memory.mid.rm <日期> <时间戳>",
            listOf("删除", "中期记忆", "移除", "清理"),
            listOf("remove", "mid", "delete", "entry", "clean"),
            group = "memory.mid")
        idx("agent.memory.mid.edit", "改中期条目 — Agent 一般不使用 (梦境自动整理)", "agent.memory.mid.edit <日期> <时间戳> <新内容>",
            listOf("编辑", "修改", "中期记忆", "更新"),
            listOf("edit", "modify", "mid", "update", "entry"),
            group = "memory.mid")
        idx("agent.memory.project", "查看项目记忆 (里程碑/闭环时总结的可复用方法论)", "agent.memory.project [项目名]",
            listOf("项目", "记忆", "经验", "方法", "总结", "闭环", "里程碑", "Project"),
            listOf("project", "experience", "method", "summary", "milestone"),
            group = "memory.project")
        idx("agent.memory.project.save", "项目经验 — 完成某任务阶段/里程碑时被动提交", "agent.memory.project.save <项目名> <内容>",
            listOf("保存", "项目经验", "总结", "方法", "Save", "写入"),
            listOf("save", "project", "summary", "method", "write", "experience"),
            group = "memory.project")
        idx("agent.memory.project.delete", "删除一个项目记忆分片", "agent.memory.project.delete <项目名>",
            listOf("删除", "项目", "清理", "移除"),
            listOf("delete", "project", "remove", "clean"),
            group = "memory.project")
        idx("agent.memory.project.rm", "从项目记忆中删除一条指定条目", "agent.memory.project.rm <项目名> <时间戳>",
            listOf("删除", "移除", "条目"),
            listOf("remove", "project", "delete", "entry"),
            group = "memory.project")
        idx("agent.memory.project.edit", "编辑项目记忆中的一条指定条目", "agent.memory.project.edit <项目名> <时间戳> <新内容>",
            listOf("编辑", "修改", "更新"),
            listOf("edit", "modify", "project", "update", "entry"),
            group = "memory.project")
        idx("agent.memory.rm", "从长期记忆中删除一条指定条目", "agent.memory.rm <时间戳>",
            listOf("删除", "记忆", "移除", "清理", "Rm"),
            listOf("remove", "delete", "memory", "clean", "erase"),
            group = "memory.long")
        idx("agent.memory.edit", "编辑长期记忆中的一条指定条目", "agent.memory.edit <时间戳> <新内容>",
            listOf("编辑", "修改", "记忆", "更新", "Edit"),
            listOf("edit", "modify", "update", "memory", "change"),
            group = "memory.long")
        idx("agent.session.delete", "删除指定历史会话", "agent.session.delete <id>",
            listOf("删除", "会话", "历史", "清理"),
            listOf("delete", "session", "history", "remove"))
        idx("agent.session.archive", "归档指定会话到历史记录", "agent.session.archive <id>",
            listOf("归档", "会话", "存档", "保存"),
            listOf("archive", "session", "save", "store"))
        idx("agent.session.current", "查看当前活跃会话 ID 和状态", "agent.session.current",
            listOf("当前", "会话", "ID", "状态", "活跃"),
            listOf("current", "session", "id", "status", "active"))
        idx("agent.audit", "查看命令执行审计日志 (最近 N 条: 成败/会话/命令/输出摘要)", "agent.audit [条数]",
            listOf("审计", "日志", "历史", "记录", "命令", "Audit"),
            listOf("audit", "log", "history", "record", "command"))
        idx("agent.browser-tools", "查看浏览器协作能力参考 (唤醒 sys.browser.open / MCP 工具 browser.mcp.* / 网页转档 search.*)", "agent.browser-tools",
            listOf("浏览器", "WebView", "操控", "MCP", "转档", "Browser"),
            listOf("browser", "WebView", "tools", "mcp", "commands", "control"))
        idx("agent.dream", "触发梦境模式: 分析中期记忆 → 提炼洞察 → 写入长期记忆", "agent.dream",
            listOf("梦境", "整理", "压缩", "归档", "分析", "回顾", "Dream", "记忆管理"),
            listOf("dream", "compress", "archive", "analyze", "review", "organize", "memory"),
            group = "memory.core")
        idx("agent.cleanup", "清理过期文件 (3 天以上旧截图原图 / 30 天以上收件箱)", "agent.cleanup [--dry-run]",
            listOf("清理", "删除", "过期", "临时", "空间", "释放", "Cleanup"),
            listOf("cleanup", "clean", "delete", "purge", "free space", "temp"))
        idx("agent.storage", "查看工作区存储占用和限额", "agent.storage",
            listOf("存储", "空间", "占用", "磁盘", "容量", "限额"),
            listOf("storage", "disk", "space", "usage", "quota", "capacity"))
        idx("agent.sessions", "搜索跨会话的历史记录 (支持关键词过滤)", "agent.sessions [keyword] [limit]",
            listOf("会话", "历史", "搜索", "过往", "记录", "查找"),
            listOf("sessions", "history", "search", "past", "conversation", "find"))
        idx("agent.output", "查看用户输出目录 (HTML/MD/PDF 导出位置, 只读)", "agent.output",
            listOf("输出", "导出", "文件", "目录", "生成", "保存"),
            listOf("output", "export", "file", "directory", "generate", "save"))

        // ── 工作区文件操作 ──
        idx("agent.read", "在工作区中读取文件内容 (只读)", "agent.read <路径>",
            listOf("读取", "读文件", "查看", "打开", "Read", "Cat"),
            listOf("read", "open", "view", "cat", "file", "content"))
        idx("agent.write", "在工作区中写入文件 (原子操作 tmp→rename; 多行内容用 --from <源文件> 导入)", "agent.write <路径> <内容> | agent.write <路径> --from <源文件>",
            listOf("写入", "写文件", "创建", "保存", "Write", "生成文件"),
            listOf("write", "create", "save", "file", "generate", "output"))
        idx("agent.policy", "per-agent 命令前缀级授权 — 多 Agent 场景按 agent 放开受限命令 (blockList 恒优先)", "agent.policy [allow|deny <前缀> [--to <agent>]]",
            listOf("权限", "授权", "策略", "命令权限", "放行", "限制", "多Agent"),
            listOf("permission", "policy", "grant", "allow", "deny", "access"))
        idx("agent.ls", "列出工作区中的文件和目录", "agent.ls [路径]",
            listOf("列表", "目录", "列出", "文件", "浏览", "Ls", "Dir"),
            listOf("list", "ls", "dir", "files", "directory", "browse"))
        idx("agent.rm", "删除工作区中的文件", "agent.rm <路径>",
            listOf("删除", "移除", "清理", "Rm", "文件"),
            listOf("remove", "delete", "rm", "erase", "clean"))
        idx("agent.mkdir", "在工作区中创建目录", "agent.mkdir <路径>",
            listOf("目录", "创建", "新建", "文件夹", "Mkdir"),
            listOf("mkdir", "directory", "create", "folder", "new"))

        // ── plugin: 插件管理 ──
        idx("plugin.marketplace", "浏览插件市场 (自动路由 GitHub/Gitee, 含描述)", "plugin.marketplace [--refresh]",
            listOf("市场", "商店", "插件", "浏览", "市场", "所有", "列表"),
            listOf("marketplace", "store", "browse", "plugins", "catalog", "all"))
        idx("plugin.search", "在插件市场中搜索插件 (按名称/描述)", "plugin.search <query>",
            listOf("搜索", "查找", "插件", "寻找", "Search"),
            listOf("search", "find", "lookup", "plugin", "query"))
        idx("plugin.install", "安装指定插件 (支持 marketplace ID 或 GitHub URL)", "plugin.install <id|url> [--from <source>]",
            listOf("安装", "下载", "添加", "获取", "Install", "装"),
            listOf("install", "download", "add", "get", "fetch"))
        idx("plugin.uninstall", "卸载指定插件 (清理文件 + 注销命令)", "plugin.uninstall <id>",
            listOf("卸载", "移除", "删除", "去掉", "Uninstall"),
            listOf("uninstall", "remove", "delete", "uninstall"))
        idx("plugin.list", "列出所有已安装的插件 (含状态: active/disabled)", "plugin.list",
            listOf("列表", "已安装", "插件", "所有", "List"),
            listOf("list", "installed", "plugins", "all", "status"))
        idx("plugin.info", "查看指定插件的详细信息 (版本/命令/依赖)", "plugin.info <id>",
            listOf("详情", "信息", "版本", "插件", "Info", "查看"),
            listOf("info", "details", "version", "plugin", "view", "description"))
        idx("plugin.enable", "启用已禁用的插件 (恢复命令注册)", "plugin.enable <id>",
            listOf("启用", "激活", "开启", "Enable"),
            listOf("enable", "activate", "turn on", "start"))
        idx("plugin.disable", "禁用插件 (保留文件, 暂停命令注册)", "plugin.disable <id>",
            listOf("禁用", "停用", "暂停", "关闭", "Disable"),
            listOf("disable", "deactivate", "pause", "stop", "turn off"))
        idx("plugin.update", "更新指定插件到最新版本", "plugin.update <id>",
            listOf("更新", "升级", "最新", "版本", "Update"),
            listOf("update", "upgrade", "latest", "version"))
        idx("plugin.upgrade", "批量升级所有可更新的插件", "plugin.upgrade --all",
            listOf("升级", "全部", "批量", "所有", "更新", "Upgrade"),
            listOf("upgrade", "update all", "batch", "everything"))
        idx("plugin.auto", "插件省电管理 (唤醒/休眠/状态/空闲自动休眠)", "plugin.auto <wake|sleep|status|sleep-idle>",
            listOf("省电", "休眠", "唤醒", "空闲", "功耗"),
            listOf("power", "sleep", "wake", "idle", "battery"))
        idx("plugin.verify", "校验已安装插件文件完整性 (单个或 --all 批量)", "plugin.verify <id> | plugin.verify --all",
            listOf("验证", "校验", "检查", "完整性", "批量", "文件"),
            listOf("verify", "check", "validate", "integrity", "batch", "file"))

        // ── security: 攻击来源黑名单 (v0.34.1) ──
        idx("security.block", "将攻击来源 (域名/路径) 加入黑名单 — 拉黑后同来源内容直接阻止", "security.block <来源>",
            listOf("拉黑", "黑名单", "阻止", "封禁", "攻击来源", "屏蔽", "加入黑名单"),
            listOf("block", "blacklist", "ban", "blocklist", "source", "blocked"))
        idx("security.unblock", "从黑名单移除来源 — 误拉黑可撤销", "security.unblock <来源>",
            listOf("解除", "撤销", "移除", "取消拉黑", "恢复", "解封"),
            listOf("unblock", "remove", "undo", "unban", "restore"))
        idx("security.blocklist", "列出全部攻击来源黑名单条目", "security.blocklist",
            listOf("黑名单", "列表", "查看", "全部", "已阻止来源"),
            listOf("blocklist", "list", "view", "all", "blocked"))

        // ── framework: 框架协议 ──
        idx("framework.discover", "扫描局域网中发现 MengPaw 框架节点 (mDNS; --wait 同步等待 3 秒看结果)", "framework.discover [--wait]",
            listOf("发现", "扫描", "局域网", "框架", "节点", "搜索", "探测"),
            listOf("discover", "scan", "LAN", "network", "find", "peer", "mDNS"))
        idx("framework.peers", "列出所有已知框架节点 (含信任状态和在线状态)", "framework.peers",
            listOf("节点", "对等", "框架", "列表", "已发现", "在线"),
            listOf("peers", "nodes", "list", "known", "online", "discovered"))
        idx("framework.add", "手动添加框架节点 (mDNS 发现不可用时)", "framework.add <name> <address> [port] [--type <type>]",
            listOf("添加", "手动", "节点", "IP", "地址", "通讯录"),
            listOf("add", "manual", "node", "peer", "address", "contact"))
        // 描述刻意不带"记忆"二字 — 避免通用"记忆"查询混入 framework 结果 (P2-10 收敛)
        idx("framework.trust", "信任指定框架节点 (允许任务委派与数据共享, 需 --yes 确认)", "framework.trust <fingerprint> [--yes]",
            listOf("信任", "授权", "允许", "框架", "节点", "配对", "确认"),
            listOf("trust", "authorize", "allow", "peer", "pair", "accept", "confirm"))
        idx("framework.untrust", "取消信任指定框架节点", "framework.untrust <fingerprint>",
            listOf("取消", "信任", "撤销", "移除", "断开"),
            listOf("untrust", "revoke", "remove", "disconnect", "block"))
        idx("framework.info", "查看指定框架节点的详细信息 (名称/版本/Agent 列表)", "framework.info <fingerprint>",
            listOf("详情", "信息", "框架", "版本", "Agent"),
            listOf("info", "details", "framework", "version", "agent"))
        idx("framework.ping", "测试与指定框架节点的网络连通性", "framework.ping <fingerprint>",
            listOf("Ping", "连通", "测试", "网络", "延迟"),
            listOf("ping", "test", "network", "latency", "check"))
        idx("framework.connect", "通过连接器插件连接外部框架节点 (OpenClaw/QwenPaw 等)", "framework.connect <peer-name>",
            listOf("连接", "外部", "框架", "OpenClaw", "QwenPaw", "连接器", "适配器"),
            listOf("connect", "external", "framework", "adapter", "connector"))
        idx("framework.call", "调用已连接外部框架的工具 (翻译成远端协议)", "framework.call <peer-name> <tool> [jsonArgs]",
            listOf("调用", "工具", "委派", "外部", "连接", "执行"),
            listOf("call", "tool", "invoke", "remote", "execute", "delegate"))
        idx("framework.disconnect", "断开与外部框架节点的连接", "framework.disconnect <peer-name>",
            listOf("断开", "连接", "外部", "取消"),
            listOf("disconnect", "close", "stop", "detach"))
        // v0.35.2 审查闭环: 配对请求 Agent 侧操作入口 (framework.pair.*)
        idx("framework.pair.ls", "列出框架通讯录配对请求 (待处理/已同意/已拒绝; 顺带清理 7 天前过期记录)", "framework.pair.ls",
            listOf("配对", "请求", "待处理", "红点", "通讯录", "同意", "拒绝"),
            listOf("pair", "request", "pending", "accept", "decline", "directory"))
        idx("framework.pair.accept", "同意框架配对请求 (双方加入通讯录)", "framework.pair.accept <requestId>",
            listOf("同意", "配对", "请求", "接受", "确认"),
            listOf("pair", "accept", "request", "confirm", "approve"))
        idx("framework.pair.decline", "拒绝框架配对请求", "framework.pair.decline <requestId>",
            listOf("拒绝", "配对", "请求", "驳回"),
            listOf("pair", "decline", "request", "reject"))
        idx("framework.delegate", "指挥舰委派 — 把任务直发已信任框架执行 (对端 Agent 自主处理, 可自行进入火种模式, 结果经孪生同步回传)", "framework.delegate <peer-name> <task>",
            listOf("委派", "指挥", "远程", "执行", "跨设备", "调度", "任务", "指挥舰"),
            listOf("delegate", "command", "dispatch", "remote", "cross-device", "task"))
        idx("fleet.peers", "舰队成员总览 — 已信任框架列表 (名称/类型/地址/在线状态)", "fleet.peers",
            listOf("舰队", "成员", "总览", "框架", "信任", "在线"),
            listOf("fleet", "peers", "members", "frameworks", "trusted", "online"))
        idx("fleet.delegate", "指挥舰委派 — 委派任务到已信任框架执行 (带委派 ID, 对端完成后自动回传结果)", "fleet.delegate <peer-name> <task>",
            listOf("舰队", "委派", "指挥", "远程", "执行", "跨设备", "调度"),
            listOf("fleet", "delegate", "dispatch", "remote", "execute", "cross-device"))
        idx("fleet.status", "舰队任务状态 — 委派 ID/任务/状态 (SENT/DONE/FAILED)/结果回收", "fleet.status",
            listOf("舰队", "任务", "状态", "委派", "进度", "结果"),
            listOf("fleet", "status", "task", "delegate", "progress", "result"))
        idx("fleet.reply", "执行方回传结果 — 委派执行完后把结果发回指挥舰 (inbox 任务文件注明委派 ID)", "fleet.reply <delegateId> <结果> [--fail]",
            listOf("舰队", "回传", "结果", "委派", "回复", "完成"),
            listOf("fleet", "reply", "result", "delegate", "complete", "callback"))
        idx("framework.adapters", "列出已注册的连接器及其在线状态", "framework.adapters",
            listOf("连接器", "适配器", "列表", "在线", "MCP网关"),
            listOf("adapters", "connectors", "list", "gateway", "status"))
    }

    // ── 便捷注册 ─────────────────────────────────────────────────────

    private fun idx(
        fullName: String, description: String, usage: String = "",
        zhKeywords: List<String> = emptyList(), enKeywords: List<String> = emptyList(),
        group: String = ""
    ) {
        val parts = fullName.split(".", limit = 2)
        CommandSearch.register(
            CommandIndex(
                fullName = fullName,
                namespace = parts.getOrElse(0) { "" },
                description = description,
                usage = if (usage.isNotBlank()) usage else fullName,
                zhKeywords = zhKeywords,
                enKeywords = enKeywords,
                searchGroup = group
            )
        )
    }
}
