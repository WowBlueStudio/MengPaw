# 教训记录

> 每次犯错都记下来，防止再犯。按日期倒序。

---

## 2026-07-19 — v0.2.2 发布日

### 致命错误

1. **硬编码路径 `/Android/data/com.mengpaw`**
   - 后果：真实设备上所有文件操作失败 → 首次启动闪退 ×2 + 新建智能体闪退
   - 教训：永远不要硬编码 Android 文件路径。用 `Context.filesDir`、`Context.getExternalFilesDir()` 或 `Environment.getExternalStorageDirectory()`
   - 修复：`DataPaths.initialize(context)` 动态初始化

2. **APK 上传漏掉**
   - 后果：发了 tag 但没上传 APK → 用户没有下载链接
   - 教训：版本发布必须：commit → tag → push → `gh release create <tag> <apk>` 四步都执行

3. **假数据留在代码里**
   - 后果：框架通讯录显示不存在的设备和 Agent
   - 教训：Mock 数据仅用于开发测试，提交前必须检查并替换为空态 UI

4. **Google Play Protect 拦截**
   - 后果：debug 签名的 APK 被 Play Protect 直接标记为可疑
   - 教训：对外分发的 APK 必须用 release key 签名。debug.keystore 全网通用，不被信任

### 编码错误

5. **`!!` 强制解包**
   - 后果：SharedPreferences 返回 null → NPE 崩溃
   - 教训：永远不用 `!!`。用 `?: defaultValue` 或 `as?` 安全转型

6. **文件 IO 无 try/catch**
   - 后果：文件损坏或权限不足 → 崩溃
   - 教训：所有 `readText()` / `writeText()` / `listFiles()` 必须包裹 try/catch

7. **广播接收器不注销**
   - 后果：`EventReceiver.register()` 创建新实例但引用丢失 → 永不注销 → 内存泄漏
   - 教训：`registerReceiver()` 后必须有对应的 `unregisterReceiver()`，保存引用

8. **HttpClient 不关闭**
   - 后果：每个 AgentSession 创建新的 Ktor HttpClient → 资源泄漏
   - 教训：实现了 `Closeable` 的资源在 ViewModel/Service 销毁时必须关闭

9. **状态跨智能体共享**
   - 后果：Agent A 在运行 → 切到 Agent B → isRunning=true → 无法发送消息
   - 教训：每个 AgentSession 必须持有独立的状态 Flow

### 流程错误

10. **Bash 中 backtick 被解析为命令替换**
    - 后果：`gh release create` 的 --notes 参数含 `` ` `` 被 shell 执行
    - 教训：GitHub CLI 的 --notes 包含特殊字符时用 `--notes-file CHANGELOG.md`

11. **大版本号跳跃**
    - 后果：浏览器从 1.0.0 改到 0.1.0
    - 教训：首个公开版本从 0.1.0 开始，不要跳版本号

---

## 2026-07-20 — 防御性编程补全

### `!!` 强制解包清理

12. **所有 `!!` 必须消除**
    - 后果：即使上面有 `!= null` 检查，可变属性仍存在线程竞争 → NPE 崩溃
    - 教训：`cached!!` → `cached ?: emptyList()`；`map[key]!!.copy()` → `map[key]?.let { copy() }`
    - 修复数量：4 处（RenderPlugin, BrowserActivity, PluginMarketplaceClient, MemoryPlugin）

### 文件 IO 保护补全

13. **`readText()` 即使有 `exists()` 检查也会崩**
    - 后果：文件在 `exists()` 和 `readText()` 之间被锁定/损坏 → 崩溃
    - 教训：`if (file.exists()) file.readText()` 这样的模式不够，必须再加 `try/catch`
    - 修复数量：20+ 处（FsPlugin, SelfExecutor, MemoryPlugin, SkillPlugin, WorkflowPlugin, IncubatorPlugin, ComfyPlugin, HermesPlugin, DevPlugin）

14. **`writeText()` 同样需要保护**
    - 后果：磁盘满 / 权限变更 / 目录被删 → 写入失败崩溃
    - 教训：所有 `file.writeText()` / `file.writeText()` / `file.appendText()` 必须包裹 try/catch。对于返回 `ExecutionResult` 的命令，catch 中返回 `ExecutionResult.fail(..., ERR_IO)`；对于内部辅助函数，静默吞下
    - 修复数量：25+ 处（AgentDocManager 7 处, HermesPlugin 5 处, DevPlugin 4 处, IncubatorPlugin 3 处, ComfyPlugin 2 处，以及 AgentDocs, DreamEngine, PromptFirewall, SelfExecutor, Checkpoint, MemoryPlugin, SkillPlugin 各 1 处）

15. **新增错误码 `ERR_IO`**
    - 后果：文件 IO 失败时只能用 `ERR_INTERNAL`，不精确
    - 教训：为文件 IO 错误添加专用错误码 `ERR_IO`，方便 Agent 区分和处理
    - 位置：`CommandExecutor.kt` → `ErrorCodes.ERR_IO`

### 流程错误

16. **未授权发布版本**
    - 后果：2026-07-19 在 bug 修复完成后自动发布了版本，用户明确表示"这个不行"
    - 教训：任何发布操作（git tag, GitHub Release, APK 上传）必须等待用户说"发布新版本"。修复后只做本地 commit。

---

## 2026-07-20 — v0.3.1 紧急修复

24. **R8 混淆必须在真机验证后才能发布**
    - 后果：v0.3.0 APK 安装后立即闪退，R8 删除了核心类
    - 教训：开启 isMinifyEnabled 后必须在真机上验证启动。APK 从 13MB→2MB 就是危险信号。未验证前先关混淆。
25. **hotfix 必须迭代版本号**
    - 后果：修了 R8 问题但版本号没变，Release 复用旧的
    - 教训：任何修改（包括 hotfix）都要 0.3.0→0.3.1。删旧 Release+旧 tag，重建新 tag+新 Release。

## 2026-07-20 — v0.3.0 发布教训

### 编译教训

17. **不可编译的代码不能 push**
    - 后果：v0.3.0 push 后编译失败，连续 5 次修复提交
    - 教训：写完代码先 `./gradlew assembleRelease`，通过再 commit+push

18. **跨模块引用的 data class 放 core**
    - 后果：MissionMonitor 在 plugin-agent-loop 中，shell 无法引用
    - 教训：需要多模块共享的对象放在 mengpaw-core

19. **新增模块检查 ProGuard 规则**
    - 后果：Shell 和 Browser 的 R8 混淆删掉了 core 类
    - 教训：每个 application 模块都需要 `-keep class com.mengpaw.core.**`

20. **Kotlin companion object 只能有一个**
    - 后果：CacheStrategy 中两个 companion object 导致 `Unresolved reference`
    - 教训：合并到同一个 companion object 中

21. **sealed class 子类字段名要一致**
    - 后果：`AgentWithTrace.finalContent` vs `Agent.content` / `User.content`
    - 教训：批量替换时检查所有子类，统一字段名

22. **模块间依赖要检查**
    - 后果：shell 引用 kotlinx.serialization 但未声明依赖
    - 教训：能用简单替代方案（Regex）就不要引入新依赖

23. **每次编译通过后再 push+发布+上传 APK**
    - 后果：v0.3.0 首次 push 后有 7 个编译错误，APK 未成功构建
    - 教训：build → 验证 APK → commit → push → tag → release

---

## 版本规则

按用户要求，MengPaw 版本号规则：
- **X** (0.X.0)：发布正式版时递增
- **Y** (0.0.Y)：变更底层逻辑时递增
- **Z** (0.0.Z)：修复漏洞或 UI 问题时递增

提交指令自动化：版本号 → CHANGELOG → Tag → Push → APK 上传
