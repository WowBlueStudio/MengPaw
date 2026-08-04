# 流式传输调查记录 — 2026-08-04 (v0.28.4)

> 会话中断/重启后的恢复入口。三秒读完:流式链路全部正常, 唯一问题是 token 突发到达 + UI 节流 → 无打字机观感; 修复方向 = UI 播放器节奏化。

## 结论(模拟器 MengPaw_Test 实测, logcat 铁证)

### 流式链路全链路正常 ✅

```
UI-DETECT score=REACT autoUpgrade=false     → 无自动升级, 走 REACT
UI-MODE executionMode=null loopMode=REACT
ENG-REACT step=0 stream=true                → 引擎拿到 onDelta
S-OPEN stream=true model=deepseek-v4-flash endpoint=.../chat/completions
UI-PUSH dlen=2 blen=2 blank=false           → UI 收到增量
S-FIRST len=2 first=**                      → 首帧
S-DONE chunks=179 chars=297                 → 179 个 SSE 增量全部到达!
UI-FINAL resultLen=297 translate=false      → 完成
```

传输层(SSE 逐行)、双格式解析、引擎透传、UI 推送全部工作。**179 chunks ≠ 单帧**——之前"单帧响应"假设被排除。

### 真正的根因:token 突发到达 + 50ms 节流

```
S-OPEN 01:42:04.941 → S-DONE 01:42:05.147   ← 整个流 206ms 内全部到达
```

- DeepSeek 端点在 ~200ms 内突发发送全部增量(服务端/网络段行为, 非代码问题)
- UI 50ms 节流只在"delta 到达时"检查 → 只推送 3 次
- 随后最终 replace 整段替换 → 用户观感 = "整段弹出", 无打字机

**结论: 打字机效果需要 UI 播放器节奏化 — 不管 token 多快到达, 按固定间隔(如 40-60ms)播放增量, 模拟逐字出现观感。**

### 真机零日志之谜(已破)

- **release 包 R8 minify 裁剪了全部 Log.d**: `mengpaw-shell/build.gradle.kts:47-54` `isMinifyEnabled = true` + `proguard-android-optimize.txt`(Google 默认规则 `-assumenosideeffects` 移除 Log.d/v)
- 打点只在 **debug 包**生效(无 minify)。验证阶段必须用 debug 包
- 真机上的"零日志"不是链路问题, 是日志本身被 R8 删了

## 当前代码状态(commit 后)

v0.28.4 包含:
- **Step1 打点**: `MengPawStream` 标签 K1-K10(传输层 S-OPEN/S-FIRST/S-DONE/S-ERR、引擎 ENG-REACT、UI UI-DETECT/UI-MODE/UI-PUSH/UI-FINAL、重试 RETRY)
- **Step2 超时/EOF 分离**: socketTimeoutMs=180s(AdaptiveConfig + RemoteApi);CancellationException 先行 rethrow;首 token 前超时抛 LlmApiException 触发重试, 已有内容返回部分
- **Step3 fallback 流式化**: executeWithRetry else 分支 → completeStreamingWithMessages;RemoteApi override completeStreamingWithMessages(消死代码) + HTTP 错误改抛 LlmApiException
- **Step4 UI**: resolveRunningIndex 快路径(4 处替换点同步 ref/index);computeStreamDisplayText 提取;节流尾段 flush(!doTranslate 时)
- **Step5 PLAN 通道**: runWithPlan/executePlanStep 透传 onDelta
- **Step6 Swarm/Mission 合成流式化**: synthesize 阶段 completeStreaming
- 版本 0.28.4 (versionCode 28004) + CHANGELOG

## 剩余工作(重启后继续)

1. **UI 播放器节奏化(核心)**: onDelta 只累积 buffer;独立播放协程按固定间隔(40-60ms)推送增量, 模拟打字机;run() 结束 flush 尾段。参考: `AgentViewModel.kt` onDelta 区域(约 458-487 行)
2. **验证**: debug 包真机/模拟器复测, logcat 看 UI-PUSH 是否按节奏输出
3. **验证后移除打点**: 全局搜 `MengPawStream` 删除(所有打点集中在 AdaptiveLlmProvider.kt / AgentEngine.kt / AgentViewModel.kt)
4. **重打 release 包**: R8 会裁掉打点, 无需担心残留; 重新构建 + apksigner + 推两台设备
5. 之前的完整性检查 latch(空 assistant 消息)已被 `!agent.repair` 或清数据解决; 若再现, 用正确拼写 `!agent.repair`

## 环境与操作记录

- **模拟器**: `MengPaw_Test` AVD, emulator-5554; debug 包安装(签名与 release 不同, 先卸载)
- **真机**: 手机 192.168.2.9 (无线端口每次配对变, 会话确认); 平板 192.168.2.7 (荣耀, 安装必弹 ICP 框)
- **LLM 配置**: endpoint `https://api.deepseek.com/chat/completions`, model `deepseek-v4-flash`; API key 在会话中提供过(敏感, 重启后重新向用户索取)
- **UI 自动化操作**: `adb shell input tap/text/keyevent`; 模拟器截屏 `exec-out screencap -p > file.png`
- **logcat**: `adb -s <serial> logcat -s MengPawStream -v time`(debug 包才有效)
