// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
        // ── self: Agent 进化 (16) ──
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
        idx("self.time", "获取当前日期时间 (支持多种格式)", "self.time [iso|date|time|timestamp]",
            listOf("时间", "日期", "当前", "时钟", "今天", "现在"),
            listOf("time", "date", "current", "now", "today", "clock"))
        idx("notify.message", "Agent 主动向用户聊天推送一条消息 (System 角色)", "self.notify.message <text>",
            listOf("通知", "推送", "消息", "提醒", "发送", "告知"),
            listOf("notify", "push", "message", "alert", "send", "inform"))
        idx("notify.banner", "Agent 向用户显示顶部横幅通知 (4 秒消失)", "self.notify.banner <text> [--level info|success|warn|error]",
            listOf("横幅", "通知", "弹窗", "提示", "警告", "成功"),
            listOf("banner", "notify", "popup", "alert", "warning", "success"))

        // ── evolution: Agent 进化 (从失败中学习) ──
        idx("evolution.audit", "查看进化绩效: 失败分布/复现率/已沉淀教训", "evolution.audit",
            listOf("进化", "绩效", "失败", "复盘", "教训", "复现", "自省"),
            listOf("evolution", "audit", "failure", "lessons", "review", "repeat"))
        idx("evolution.report", "发现框架缺陷时写技术反馈给开发者 (落盘+推送)", "evolution.report <描述>",
            listOf("反馈", "缺陷", "Bug", "框架", "报错", "上报"),
            listOf("report", "feedback", "bug", "framework", "issue"))
        idx("evolution.learn.command", "把正确命令用法/同义词登记进指令集搜索索引", "evolution.learn.command <命令> <描述> [--keywords 词,词]",
            listOf("指令", "命令", "学习", "关键词", "索引", "丰富"),
            listOf("learn", "command", "keyword", "index"))
        idx("evolution.reactions", "查看用户反应档案 (用户如何纠正过我 — 用户分身数据源)", "evolution.reactions",
            listOf("用户", "反应", "纠正", "反馈", "档案", "偏好"),
            listOf("reactions", "correction", "feedback", "user"))
        idx("evolution.mark-corrected", "标记某条失败模式已沉淀修正 (绩效闭环)", "evolution.mark-corrected <failure-id>",
            listOf("修正", "标记", "教训", "已沉淀", "完成"),
            listOf("mark", "corrected", "fixed", "resolved"))

        // ── agent: 文档/记忆管理 ──
        idx("agent.docs", "列出 Agent 工作区的所有文档文件 (Soul/Agents/Memory/Boost/Profile)", "agent.docs",
            listOf("文档", "工作区", "文件", "列表", "所有", "Soul", "Profile", "Agents"),
            listOf("docs", "documents", "workspace", "files", "list", "soul", "profile"))
        idx("agent.cli", "查看 Agent 所有可用命令的完整参考手册", "agent.cli",
            listOf("CLI", "命令", "手册", "参考", "帮助", "文档"),
            listOf("CLI", "command", "manual", "reference", "help"))
        idx("agent.profile", "查看 Agent 身份档案 (名称/角色/偏好)", "agent.profile",
            listOf("档案", "身份", "Profile", "角色", "偏好", "介绍"),
            listOf("profile", "identity", "role", "preference", "intro"))
        idx("agent.soul", "查看 Agent 核心灵魂设定和行为准则", "agent.soul",
            listOf("灵魂", "Soul", "设定", "准则", "性格", "行为", "规则"),
            listOf("soul", "personality", "rules", "behavior", "character"))
        idx("agent.boost", "阅读 Agent 初始化引导手册 (新 Agent 第一步)", "agent.boost",
            listOf("引导", "入门", "初始化", "新手", "教程", "Boost", "手册"),
            listOf("boost", "guide", "tutorial", "init", "getting started", "onboarding"))
        idx("agent.boost.delete", "删除 Agent 引导加速文件", "agent.boost.delete",
            listOf("删除", "Boost", "引导", "清理"),
            listOf("delete", "remove", "boost", "clean"))
        idx("agent.memory", "查看长期记忆 (已注入系统提示词, 最重要的记忆)", "agent.memory [query]",
            listOf("记忆", "长期", "查看", "已记住", "读记忆", "Memory"),
            listOf("memory", "long-term", "view", "remember", "read", "recall"))
        idx("agent.memory.keep", "将重要信息写入长期记忆 (注入系统提示词)", "agent.memory.keep <内容>",
            listOf("持久化", "保存", "记住", "存储", "记录", "写记忆", "Keep", "重要"),
            listOf("persist", "save", "remember", "store", "write memory", "keep", "important"))
        idx("agent.memory.record", "记录对话摘要到中期记忆 (按日分片, 不注入提示词)", "agent.memory.record <内容>",
            listOf("记录", "中期", "摘要", "对话", "日志", "临时", "日记"),
            listOf("record", "mid-term", "summary", "log", "note", "daily", "journal"))
        idx("agent.memory.mid", "查看/搜索中期记忆 (按日期分片, 需要时查阅)", "agent.memory.mid [日期|关键词]",
            listOf("中期", "记忆", "日", "历史", "查阅", "过往", "回顾"),
            listOf("mid", "history", "daily", "review", "past", "query"))
        idx("agent.memory.mid.delete", "删除指定日期的中期记忆分片", "agent.memory.mid.delete <日期>",
            listOf("删除", "中期记忆", "清理", "日"),
            listOf("delete", "mid", "remove", "clean", "daily"))
        idx("agent.memory.project", "查看项目记忆 (里程碑/闭环时总结的可复用方法论)", "agent.memory.project [项目名]",
            listOf("项目", "经验", "方法", "总结", "闭环", "里程碑", "Project"),
            listOf("project", "experience", "method", "summary", "milestone"))
        idx("agent.memory.project.save", "将里程碑总结写入项目记忆", "agent.memory.project.save <项目名> <内容>",
            listOf("保存", "项目经验", "总结", "方法", "Save", "写入"),
            listOf("save", "project", "summary", "method", "write", "experience"))
        idx("agent.memory.rm", "从长期记忆中删除一条指定条目", "agent.memory.rm <时间戳>",
            listOf("删除", "记忆", "移除", "清理", "Rm"),
            listOf("remove", "delete", "memory", "clean", "erase"))
        idx("agent.memory.edit", "编辑长期记忆中的一条指定条目", "agent.memory.edit <时间戳> <新内容>",
            listOf("编辑", "修改", "记忆", "更新", "Edit"),
            listOf("edit", "modify", "update", "memory", "change"))
        idx("agent.session.delete", "删除指定历史会话", "agent.session.delete <id>",
            listOf("删除", "会话", "历史", "清理"),
            listOf("delete", "session", "history", "remove"))
        idx("agent.session.archive", "归档指定会话到历史记录", "agent.session.archive <id>",
            listOf("归档", "会话", "存档", "保存"),
            listOf("archive", "session", "save", "store"))
        idx("agent.session.current", "查看当前活跃会话 ID 和状态", "agent.session.current",
            listOf("当前", "会话", "ID", "状态", "活跃"),
            listOf("current", "session", "id", "status", "active"))
        idx("agent.audit", "对工作区执行 7 类安全检查 (危险命令/强制解包/IO 无 try/catch/明文 HTTP)", "agent.audit",
            listOf("审计", "安全", "检查", "审查", "扫描", "漏洞", "Audit"),
            listOf("audit", "security", "check", "review", "scan", "vulnerability"))
        idx("agent.browser-tools", "查看浏览器操控命令的完整参考 (45 命令)", "agent.browser-tools",
            listOf("浏览器", "WebView", "操控", "命令", "Browser"),
            listOf("browser", "WebView", "tools", "commands", "control"))
        idx("agent.dream", "触发梦境模式: 分析中期记忆 → 提炼洞察 → 写入长期记忆", "agent.dream",
            listOf("梦境", "整理", "压缩", "归档", "分析", "回顾", "Dream", "记忆管理"),
            listOf("dream", "compress", "archive", "analyze", "review", "organize", "memory"))
        idx("agent.cleanup", "清理过期文件 (30 天旧截图/收件箱/临时文件)", "agent.cleanup",
            listOf("清理", "删除", "过期", "临时", "空间", "释放", "Cleanup"),
            listOf("cleanup", "clean", "delete", "purge", "free space", "temp"))
        idx("agent.storage", "查看工作区存储占用和限额", "agent.storage",
            listOf("存储", "空间", "占用", "磁盘", "容量", "限额"),
            listOf("storage", "disk", "space", "usage", "quota", "capacity"))
        idx("agent.sessions", "搜索跨会话的历史记录 (支持关键词过滤)", "agent.sessions [keyword] [limit]",
            listOf("会话", "历史", "搜索", "过往", "记录", "查找"),
            listOf("sessions", "history", "search", "past", "conversation", "find"))
        idx("agent.output", "查看或管理用户输出目录 (HTML/MD/PDF 导出)", "agent.output",
            listOf("输出", "导出", "文件", "目录", "生成", "保存"),
            listOf("output", "export", "file", "directory", "generate", "save"))

        // ── 工作区文件操作 ──
        idx("agent.read", "在工作区中读取文件内容 (只读)", "agent.read <路径>",
            listOf("读取", "读文件", "查看", "打开", "Read", "Cat"),
            listOf("read", "open", "view", "cat", "file", "content"))
        idx("agent.write", "在工作区中写入文件 (原子操作 tmp→rename)", "agent.write <路径> <内容>",
            listOf("写入", "写文件", "创建", "保存", "Write", "生成文件"),
            listOf("write", "create", "save", "file", "generate", "output"))
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
        idx("plugin.auto", "管理插件自动更新策略 (开启/关闭/状态/空闲时自动)", "plugin.auto <wake|sleep|status|sleep-idle>",
            listOf("自动", "更新", "策略", "休眠", "唤醒"),
            listOf("auto", "update", "policy", "sleep", "wake"))

        // ── framework: 框架协议 ──
        idx("framework.discover", "扫描局域网中发现 MengPaw 框架节点 (mDNS)", "framework.discover",
            listOf("发现", "扫描", "局域网", "框架", "节点", "搜索", "探测"),
            listOf("discover", "scan", "LAN", "network", "find", "peer", "mDNS"))
        idx("framework.peers", "列出所有已知框架节点 (含信任状态和在线状态)", "framework.peers",
            listOf("节点", "对等", "框架", "列表", "已发现", "在线"),
            listOf("peers", "nodes", "list", "known", "online", "discovered"))
        idx("framework.trust", "信任指定框架节点 (允许任务委派和记忆共享)", "framework.trust <peer>",
            listOf("信任", "授权", "允许", "框架", "节点", "配对"),
            listOf("trust", "authorize", "allow", "peer", "pair", "accept"))
        idx("framework.untrust", "取消信任指定框架节点", "framework.untrust <peer>",
            listOf("取消", "信任", "撤销", "移除", "断开"),
            listOf("untrust", "revoke", "remove", "disconnect", "block"))
        idx("framework.info", "查看指定框架节点的详细信息 (名称/版本/Agent 列表)", "framework.info <peer>",
            listOf("详情", "信息", "框架", "版本", "Agent"),
            listOf("info", "details", "framework", "version", "agent"))
        idx("framework.ping", "测试与指定框架节点的网络连通性", "framework.ping <peer>",
            listOf("Ping", "连通", "测试", "网络", "延迟"),
            listOf("ping", "test", "network", "latency", "check"))
    }

    // ── 便捷注册 ─────────────────────────────────────────────────────

    private fun idx(
        fullName: String, description: String, usage: String = "",
        zhKeywords: List<String> = emptyList(), enKeywords: List<String> = emptyList()
    ) {
        val parts = fullName.split(".", limit = 2)
        CommandSearch.register(
            CommandIndex(
                fullName = fullName,
                namespace = parts.getOrElse(0) { "" },
                description = description,
                usage = if (usage.isNotBlank()) usage else fullName,
                zhKeywords = zhKeywords,
                enKeywords = enKeywords
            )
        )
    }
}
