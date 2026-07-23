# 记忆孪生开发经验教训

> v0.12.1 → v0.12.12, 2026-07-24
> 6 次部署 · 6 个 BUG · 1 次架构重构 · 2 台真机调试

## BUG 清单

| # | 现象 | 根因 | 修复 | 教训 |
|---|------|------|------|------|
| 1 | 5连击激活失败 | PluginManager.coreVersion 默认 "0.2.0" | `initializeGlobalInstance(CORE_VERSION)` | 默认值不是真实值, 全局单例需要显式初始化 |
| 2 | 激活后对方无响应 | `startListener()` 未调用, 端口没监听 | 构造函数后显式 `transport.startListener()` | 构造函数只创建对象, 不启动 I/O |
| 3 | HTTP 400 | 手动拼 JSON, payload 未转义 | `JSONObject.quote()` | 不要手写 JSON, 用序列化库 |
| 4 | HTTP 200 但不弹窗 | PromptFirewall 拦截配对消息 | CAPABILITY_ANNOUNCE 绕过防火墙 | 新协议需评估安全策略 |
| 5 | inbox 文件有内容, UI 不显示 | 文件 I/O 不触发 Compose 重组 | `LaunchedEffect` + 2s 轮询 | Compose ≠ 响应式文件系统 |
| 6 | 重启需重新 5连击 | 服务状态未持久化 | `twin_activated` 标记 + autoRestore | 后台服务需要启动恢复机制 |

## 架构原则

1. **文件式通信 > StateFlow 跨层** — hermes 的 inbox 模式是对的。Compose 感知不到文件系统, 但轮询比维护跨模块 StateFlow 链路简单可靠
2. **构造函数 ≠ 启动** — `AcpHttpTransport()` 不监听端口, `TwinSyncEngine()` 不同步数据。显式 `start()` 是必须的
3. **全局单例需要注入** — `PluginManager.globalInstance` 默认版本 0.2.0, 启动时必须注入真实版本
4. **不要手写 JSON** — shell 模块没有 serialization 插件, 用 `org.json.JSONObject` 也比字符串拼接安全
5. **新增协议类型 = 审计防火墙** — 信任建立类消息应绕过防火墙, 数据操作类需要检查

## 调试方法论

- **第一时间开 `adb logcat -s TAG`**, 比肉眼找崩溃快 100 倍
- **分布式调试 = 单机成本 × 设备数 × 2**, 每轮: 改代码 → 构建 → 装2台 → 激活 → 配对 → 看日志
- **用 `MengPawTwin` tag 集中所有孪生日志**, grep 一个 tag 就能看到全链路
- **先验证端口** (`curl` / `netstat`), 再验证消息
- **inbox 文件是最可靠的调试证据** — 文件存在说明代码跑到了, 不存在说明没跑到

## 安全设计

- 5连击隐藏手势 + 发起方弹窗 + 接收方弹窗 = 足够的安全门槛
- 配对请求绕过防火墙是**刻意的** — 信任建立必须先于信任检查
- `.trusted` 文件是信任锚点, 必须持久化到磁盘
- 卸载 = 丢失所有信任关系, 这是正确的安全行为

## 项目管理

- Release 文案和内部代码名称可以不同, 但改动前需确认范围
- Release APK 和 Debug APK 签名不同, 不可混装
- 卸载重装清空 `filesDir`, 包括 trusted/activated 标记
- v1.0.0 之前, Release 使用对外名称 "自动备份"
