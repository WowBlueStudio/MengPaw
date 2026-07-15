package com.mengpaw.core.memory

/**
 * Built-in CLI reference document for Agent use.
 * Agent can read this via: memory read cli-reference
 */
object CliReference {
    val content = """# MengPaw CLI 参考文档

> 这是 Agent CLI 的完整参考文档。你可以通过 `memory read cli-reference` 随时查阅。
> 版本: 0.1.0-alpha | 更新: 2026-07-13

---

## 快速入门

所有 CLI 命令格式:
```
namespace.command arg1 arg2 "arg with spaces" --flag value
```

示例:
```
fs.cat /data/data/com.mengpaw/files/config.json
ui.click 540 1200
net.curl https://api.example.com
memory write note.md "今天的工作记录"
skill.run 搜索
```

---

## 1. fs — 文件系统 (8 命令)

### fs.cat
读取文件内容。
```
用法: fs.cat <路径>
示例: fs.cat /data/data/com.mengpaw/files/config.json
返回: 文件文本内容
错误: "File not found" / "Permission denied"
```

### fs.ls
列出目录内容。显示文件和目录，目录以 `d` 标记。
```
用法: fs.ls [路径]
示例: fs.ls /data/data/com.mengpaw/files/
返回: 文件列表（每行一个），格式: "d 目录名" 或 "- 文件名 (大小)"
默认: 当前工作目录
```

### fs.write
写入文件。自动创建父目录。
```
用法: fs.write <路径> <内容>
示例: fs.write /tmp/note.txt "Hello from Agent"
返回: "Written N bytes to /路径"
```

### fs.rm
删除文件或目录（递归删除）。
```
用法: fs.rm <路径>
示例: fs.rm /tmp/old_file.txt
返回: "Deleted: /路径"
```

### fs.mkdir
创建目录。
```
用法: fs.mkdir <路径>
示例: fs.mkdir /data/new_folder
返回: "Created directory: /路径"
```

### fs.cp
复制文件。
```
用法: fs.cp <源路径> <目标路径>
示例: fs.cp /tmp/a.txt /data/b.txt
返回: "Copied 源 到 目标"
```

### fs.mv
移动或重命名文件。
```
用法: fs.mv <源路径> <目标路径>
示例: fs.mv /tmp/a.txt /data/a.txt
返回: "Moved 源 到 目标"
```

### fs.stat
获取文件详细信息。
```
用法: fs.stat <路径>
示例: fs.stat /data/data/com.mengpaw/files/config.json
返回:
  Path: 绝对路径
  Size: 文件大小
  Is Dir: 是否目录
  Readable: 是否可读
  Writable: 是否可写
  Last Modified: 最后修改时间
```

---

## 2. ui — 界面操控 (7 命令)

### ui.click
点击屏幕指定坐标。
```
用法: ui.click <x> <y>
示例: ui.click 540 1200
参数: x (整数), y (整数)
返回: "Click at (x, y) dispatched"
注意: 需要无障碍服务或 root 权限
```

### ui.swipe
从起点滑动到终点。
```
用法: ui.swipe <x1> <y1> <x2> <y2> [时长_ms]
示例: ui.swipe 300 800 300 400 200
参数: x1, y1, x2, y2 (整数), 时长可选 (毫秒)
返回: "Swipe from (x1,y1) to (x2,y2)"
```

### ui.input
输入文本。
```
用法: ui.input <文本>
示例: ui.input "Hello World"
返回: "Input text: 文本"
```

### ui.screenshot
保存截图。
```
用法: ui.screenshot [路径]
示例: ui.screenshot /sdcard/screen.png
返回: "Screenshot saved to 路径"
默认路径: /data/data/com.mengpaw/files/screenshots/session_{sessionId}.png
```

### ui.back
模拟返回键。
```
用法: ui.back
返回: "Back navigation dispatched"
```

### ui.home
模拟主页键。
```
用法: ui.home
返回: "Home navigation dispatched"
```

### ui.wait
等待指定毫秒。
```
用法: ui.wait [毫秒数]
示例: ui.wait 2000
默认: 1000ms
返回: "Waited Nms"
```

---

## 3. proc — 进程管理 (3 命令)

### proc.ps
列出进程。
```
用法: proc.ps
返回: 进程列表 (需要 Android API 权限)
```

### proc.kill
终止进程。
```
用法: proc.kill <PID>
示例: proc.kill 1234
返回: "Kill signal sent to PID 1234"
```

### proc.exec
执行系统命令（安全沙箱禁用）。
```
用法: proc.exec <命令>
注意: 此命令被 SecurityPolicy 拦截，默认禁用
返回: "proc exec disabled in sandbox mode"
```

---

## 4. net — 网络 (3 命令)

### net.curl
发送 HTTP GET 请求。
```
用法: net.curl <URL>
示例: net.curl https://api.example.com/data
返回: 响应内容 (最多 10000 字符)
错误: "HTTP error: 错误信息"
```

### net.get
net.curl 的别名。
```
用法: net.get <URL>
```

### net.post
发送 HTTP POST 请求。
```
用法: net.post <URL> <请求体>
示例: net.post https://api.example.com/submit '{"key":"value"}'
返回: 响应内容 (最多 10000 字符)
```

---

## 5. self — 内省 (4 命令)

### self.status
查看 Agent 当前状态。
```
用法: self.status
返回:
  Session: 当前会话 ID
  User: 用户标识
  WorkDir: 工作目录
  Uptime: 运行时间
  Memory: 堆内存使用
```

### self.config
查看或设置配置。
```
用法: self.config [键=值]
示例:
  self config           # 查看所有配置
  self config maxSteps=100  # 设置配置
返回: 配置列表或设置结果
```

### self.stats
查看系统统计信息。
```
用法: self.stats
返回:
  Memory: 已用MB / 总计MB
  Processors: CPU 核心数
  Threads: 活跃线程数
```

### self.version
查看版本信息。
```
用法: self.version
返回: "MengPaw v0.1.0-alpha (core)"
```

---

## 6. memory — 记忆系统 (6 命令)

### memory.ls
列出所有记忆。
```
用法: memory.ls
返回: 记忆列表 (每行一个)，格式: "• id — 标题 [标签] (字数)"
示例: "• meeting-notes — 会议记录 [work] (245 chars)"
```

### memory.read
读取一条记忆的完整内容。
```
用法: memory.read <ID>
示例: memory.read meeting-notes
返回: 记忆完整 Markdown 内容
错误: "Memory not found: ID"
```

### memory.write
创建或更新一条记忆。
```
用法: memory.write <ID> <内容>
示例: memory.write task-list "- 任务1\n- 任务2"
返回: "Memory saved: ID"
注意: 标题自动从 ID 生成（连字符转空格）
```

### memory.rm
删除一条记忆。
```
用法: memory.rm <ID>
示例: memory.rm old-note
返回: "Deleted: ID"
错误: "Not found: ID"
```

### memory.search
搜索记忆（标题、内容、标签）。
```
用法: memory.search <关键词>
示例: memory.search 会议
返回: 匹配的记忆列表
```

### memory.stats
记忆系统统计。
```
用法: memory.stats
返回: "Memories: N | Size: NKB"
```

---

## 7. skill — Skill 系统 (4 命令)

### skill.ls
列出所有已安装的 Skill。
```
用法: skill.ls
返回:
  ✅ 搜索 — 搜索互联网并返回结果摘要
  ⛔ 翻译 — 翻译文本到指定语言
(✅ = 已启用, ⛔ = 已禁用)
```

### skill.run
执行一个 Skill。
```
用法: skill.run <名称>
示例: skill.run 搜索
返回: Skill 的执行步骤和提示
错误: "Skill 未找到: 名称" 或 "Skill 已禁用: 名称"
```

### skill.enable
启用一个 Skill。
```
用法: skill.enable <名称>
示例: skill.enable 翻译
返回: "已启用: 名称"
```

### skill.disable
禁用一个 Skill。
```
用法: skill.disable <名称>
示例: skill.disable 搜索
返回: "已禁用: 名称"
```

---

## 响应格式

Agent 必须以 ReAct 格式响应：

```
Thought: 你的推理过程
Action: 命令名称
Action Input: {"参数1": "值1", "参数2": "值2"}
```

或当任务完成时：

```
Thought: 你的推理过程
Final Answer: 最终的答案
```

### 循环检测

如果同一命令连续执行 3 次以上，Agent 会自动停止并报错：
"Error: Detected command loop"

### 最大步数

默认最多执行 50 步 ReAct 循环。可通过 `self.config maxSteps=N` 调整。

---

## 安全限制

1. **API Key** 不可通过 CLI 访问，只能通过设置界面配置
2. `proc.exec` 默认禁用
3. 文件操作限定在应用私有目录
4. 日志中的 API Key 会自动脱敏

---

## 常用工作流

### 读取文件并总结
```
fs.cat /path/to/file
→ (文件内容)
→ Final Answer: 总结内容
```

### 搜索信息并保存
```
net.curl https://api.example.com/search?q=关键词
→ (搜索结果)
memory.write search-result "搜索结果内容"
→ "Memory saved: search-result"
```

### 运行预定义 Skill
```
skill.ls
→ (Skill 列表)
skill.run 搜索
→ (搜索步骤)
```
"""
}
