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

## 版本规则

按用户要求，MengPaw 版本号规则：
- **X** (0.X.0)：发布正式版时递增
- **Y** (0.0.Y)：变更底层逻辑时递增
- **Z** (0.0.Z)：修复漏洞或 UI 问题时递增

提交指令自动化：版本号 → CHANGELOG → Tag → Push → APK 上传
