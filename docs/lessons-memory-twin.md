# 记忆孪生开发经验教训

> v0.12.1 → v0.12.12, 2026-07-24

## 调试日志

手机 `MengPawTwin` tag 记录: 6次部署, 3个关键BUG, 1个架构重构

## 修复清单

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 5连击激活失败 | PluginManager.coreVersion 默认 "0.2.0", 插件要求 ≥0.12.0 | `initializeGlobalInstance(CORE_VERSION)` |
| 2 | 激活后对方无弹窗 | `AcpHttpTransport.startListener()` 未调用 → 端口没监听 | 显式调用 `startListener()` |
| 3 | 发起配对 → HTTP 400 | 手动 JSON 构造时 payload 未转义 (String字段嵌入JSON对象) | `JSONObject.quote()` |
| 4 | HTTP 200 但无弹窗 | PromptFirewall 拦截未信任节点的 CAPABILITY_ANNOUNCE | 配对消息绕过防火墙 |
| 5 | inbox 文件写入但UI不弹窗 | 文件写入不会触发 Compose 重组 | LaunchedEffect 2s轮询 |
| 6 | 重启需要重新5连击 | ACP/孪生服务未持久化恢复 | `twin_activated` 标记 + `autoRestoreTwinIfNeeded()` |

## 架构经验

1. **Compose 无法感知文件系统变化** — StateFlow/文件轮询是必需的桥接
2. **ACP 防火墙拦截了自己的协议** — 新增消息类型需评估是否应绕过防火墙 (配对/信任建立类应绕过)
3. **插件版本检查是硬门槛** — `PluginManager` 初始化时必须注入真实版本
4. **传输层不会自动启动** — 构造函数 != 开始监听, 需显式调用 `startListener()`
5. **手动 JSON 序列化极其容易出错** — 能用 `kotlinx.serialization` 的地方不要手写

## 教训

- 分布式系统调试成本极高, 每轮需: 改代码→构建→安装2台设备→双方5连击→点配对→看日志→查原因
- `adb logcat -s TAG` 比肉眼找崩溃快 100 倍, 应该第一时间开日志过滤
- 文件式通信 (inbox) 比 StateFlow 跨层传递更可靠, hermes 的做法是对的
- 1 个 5连击隐藏手势 + 2 个弹窗 = 足够的安全门槛, 不需要 CLI 命令
