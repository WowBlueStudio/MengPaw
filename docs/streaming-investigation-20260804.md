# 流式传输调查记录 — 2026-08-04 (v0.28.4 → v0.28.5)

> 会话中断/重启后的恢复入口。三秒读完:流式链路全通 + 网关突发到达均为事实, 打字机观感由 **UI 播放器**保证(Default 线程独立节奏播放, run() 返回后播完再收尾)。v0.28.5 已实现并验证, 打点已移除。

## 最终结论(两轮实测 + curl 铁证)

### 1. 流式链路全链路正常 ✅(v0.28.4 结论保留)

传输层(SSE 逐行)、双格式解析(OpenAI/Anthropic)、引擎透传、UI 推送全部工作。

### 2. Token "突发到达"属实, 但源头在网关侧, 客户端无截流(v0.28.5 修正)

**curl 直连 api.deepseek.com 铁证**(新鲜 prompt, 无缓存):
```
FIRST_CHUNK_AT=1000ms   ← TTFB 正常 ~1s, 首个 token 确实是流式的
chunk_40/80/120 → 1000ms  ← 前 120 chunks 同一秒内全部到达!
chunk_240 → 2000ms, chunk_360 → 3000ms, chunk_440 → 4000ms
```
- **DeepSeek 网关按 ~1s 批次批量 flush**(~120 chunks/批), 非逐 token 流
- 模拟器实测: 8.4s TTFB + 846 chunks/166ms 全到 = **命中服务端 prompt cache 后整段回放**(相同 prompt 二次请求)
- 客户端代码 `AdaptiveLlmProvider.consumeSseStream` 是增量读(`bodyAsChannel()` + `readUTF8Line()`), 无聚合无缓冲 — **"截流"在网关侧, 客户端改不了, 只能 UI 兜底**

### 3. UI 播放器节奏化(v0.28.5 实现, 已验证)✅

```
S-OPEN 38.138 → S-DONE 38.208 (70ms 突发 142 chunks — 服务器行为依旧)
UI-FINAL 38.217                 ← run() 返回, streamFinished=true
38.234 → 47.708 UI-PUSH 153 次, played=13→25→36→…→572/572 每 ~50ms 渐进!
```
**run() 返回后播放器继续把剩余缓冲按节奏播完(~9s 实测含模拟器调度拖慢), 再 final replace。**

### 4. 真机零日志之谜(v0.28.4 结论保留)

release 包 R8 minify 裁剪 Log.d(`proguard-android-optimize.txt` 默认 `-assumenosideeffects`); 打点只在 debug 包生效。**已移除全部打点, 此问题不再存在。**

## 实现要点(v0.28.5, AgentViewModel.kt)

- **onDelta 只累积**: `synchronized(streamBuf) { streamBuf.append(delta) }` — 不再直推/节流
- **播放协程必须用 `Dispatchers.Default`**(关键坑): SSE 突发时全部数据已在内存缓冲, `readUTF8Line` 永不挂起 → 主线程被读取循环占死 → Main 调度的播放协程被饿死(实测 UI-PUSH 零输出)。Default 线程独立调度, 不受影响
- **节奏自适应**: 每 tick 消费 `ceil(剩余/50)` 字符 → 长文 ~2.5s 播完, 短文逐字, 播速不随突发暴涨
- **收尾**: run() 返回后 `synchronized(streamBuf) { streamFinished = true }` → `playbackJob.join()`(等播放器播完)→ cancel → 兜底 flush(正常播完时无操作)→ final replace。join 防 Default 线程晚到 tick 覆盖最终消息
- **并发安全**: streamBuf/streamPlayed/streamFinished 统一在 `synchronized(streamBuf)` 监视器内读写; `traces` 用 `Collections.synchronizedList`(播放协程 toList 与 onStep add 跨线程); 播放器体 try/catch 保证 join() 永不抛异常
- 参数: `STREAM_PLAYBACK_INTERVAL_MS=50`、`STREAM_PLAYBACK_TARGET_TICKS=50`
- doTranslate 场景: 跳过 join 与 flush(最终 replace 整段替换为中文, 英文逐字播放无意义)

## 当前代码状态

v0.28.4(已发布): Step1-6 打点/超时分离/fallback 流式化/resolveRunningIndex/PLAN 通道/Swarm 合成流式化
v0.28.5(工作区, 未发版): UI 播放器节奏化 + 打点清理

## 剩余工作

1. **重打 release 包**: 构建 + apksigner 验证 + 推两台设备(按用户指令执行)
2. 之前的完整性检查 latch(空 assistant 消息)已被 `!agent.repair` 或清数据解决; 若再现, 用正确拼写 `!agent.repair`

## 环境与操作记录

- **模拟器**: `MengPaw_Test` AVD, emulator-5554; debug 包安装(签名与 release 不同, 先卸载)
- **真机**: 手机 192.168.2.9 (无线端口每次配对变, 会话确认); 平板 192.168.2.7 (荣耀, 安装必弹 ICP 框)
- **LLM 配置**: endpoint `https://api.deepseek.com/chat/completions`, model `deepseek-v4-flash`; API key 会话中提供(敏感, 重启后重新向用户索取)
- **UI 自动化操作**: `adb shell input tap/text/keyevent`; **输入文字后键盘弹起, 发送键/输入框坐标会变(y≈2910 → y≈1692), 必须先 uiautomator dump 再点发送**; 模拟器截屏 `exec-out screencap -p > file.png`
- **logcat**: `adb -s <serial> logcat -s MengPawStream -v time`(打点已移除, 仅 debug 包且需临时加回才有输出)
