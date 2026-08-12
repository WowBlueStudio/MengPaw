---
name: android
description: Android 开发专家知识库。操控设备的权威参考。。触发词：「查安卓命令」「怎么操控设备」「安卓开发」
enabled: true
category: system
source: plugin
---

# Android 开发专家

你是 Android 设备操控专家。本文档是你了解 Android 系统、排查问题、获取最新 API 信息的知识源。

## 信息获取

Android 官方中文文档镜像，`net.curl` 按需拉取：

```
# 站内搜索 (最常用)
net.curl "https://android-docs.cn/s/results?q=Permission"

# API 参考
net.curl "https://android-docs.cn/reference/android/Manifest.permission"

# 开发者指南
net.curl "https://android-docs.cn/guide/components/intents-filters"

# 特定版本特性
net.curl "https://android-docs.cn/about/versions/14/behavior-changes-14"
```

## Android 架构基础

```
应用层 (APK)
  → 应用框架 (ActivityManager / PackageManager / WindowManager)
    → ART 运行时 (Android Runtime)
      → HAL (硬件抽象层)
        → Linux 内核
```

**四大组件**:
- **Activity** — 一个屏幕/UI 界面。生命周期: onCreate→onStart→onResume→onPause→onStop→onDestroy
- **Service** — 后台任务。前台服务需通知栏常驻。`startForeground(id, notification)`
- **BroadcastReceiver** — 接收系统广播。Android 14+ 注册需 `RECEIVER_EXPORTED`/`NOT_EXPORTED`
- **ContentProvider** — 跨应用数据共享。`content://` URI 需要 `ContentResolver`

**API 级别对照**:
| API | Android | 关键变更 |
|:--:|------|------|
| 26 | 8.0 | 通知渠道、画中画 |
| 28 | 9 | 后台限制加强 |
| 29 | 10 | Scoped Storage |
| 30 | 11 | 分区存储强制 |
| 31 | 12 | 前台服务限制、exported 必须声明 |
| 33 | 13 | 通知运行时权限、`setAttribute` API |
| 34 | 14 | `registerReceiver` 需标志、前台服务类型验证 |
| 35 | 15 | MengPaw targetSdk |

## 权限系统

MengPaw 已声明的主要 Android 权限 (完整清单以 AndroidManifest.xml 为唯一基准):

| 权限 | 用途 | 运行时 |
|------|------|:--:|
| INTERNET | 网络访问 | ❌ |
| ACCESS_NETWORK_STATE | 网络状态检测 | ❌ |
| FOREGROUND_SERVICE | 前台服务 | ❌ |
| FOREGROUND_SERVICE_DATA_SYNC | 数据同步前台服务 | ❌ |
| FOREGROUND_SERVICE_SPECIAL_USE | 特殊用途前台服务 | ❌ |
| POST_NOTIFICATIONS | 发送通知 (Android 13+) | ✅ |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 电池优化白名单 | ✅ |
| WAKE_LOCK | CPU 唤醒锁 | ❌ |
| SYSTEM_ALERT_WINDOW | 悬浮窗 | ✅ |
| ACCESS_FINE_LOCATION | 精确定位 | ✅ |
| ACCESS_COARSE_LOCATION | 粗略定位 | ✅ |
| CAMERA | 相机 | ✅ |
| QUERY_ALL_PACKAGES | 查询已安装应用 | ❌ |
| REQUEST_INSTALL_PACKAGES | 安装 APK | ✅ |
| READ_MEDIA_IMAGES | 读取图片 (Android 13+) | ✅ |
| RECORD_AUDIO | 录音 | ✅ |
| VIBRATE | 震动 | ❌ |
| SCHEDULE_EXACT_ALARM | 精确闹钟 | ❌ |
| MANAGE_EXTERNAL_STORAGE | 所有文件访问 | ✅ |
| READ_EXTERNAL_STORAGE | 读取存储 (≤12) | ✅ |
| WRITE_EXTERNAL_STORAGE | 写入存储 (≤12) | ✅ |
| SEND_SMS | 发送短信 (sys.sms.send) | ✅ |
| READ_SMS | 读取短信 (sys.sms.list) | ✅ |
| READ_CONTACTS | 读取联系人 (sys.contacts.list) | ✅ |
| READ_CALL_LOG | 读取通话记录 (sys.calllog.list) | ✅ |
| CALL_PHONE | 拨打电话 (sys.phone.call) | ✅ |
| SET_WALLPAPER | 设置壁纸 (sys.wallpaper.set) | ❌ |
| CHANGE_WIFI_STATE | 开关/扫描 WiFi (sys.wifi.enable/scan) | ❌ |

```
sys.permission.list                   # 列出权限状态
sys.permission.request <name>          # 请求运行时权限 (弹出系统对话框)
sys.permission.check <name>            # 检查权限是否已授予
```

## 前台服务

MengPaw ShellService 以前台服务运行，通知栏常驻。

```
# 关键约束:
- startForeground() 必须 5 秒内调用, 否则系统杀进程
- 每个 foregroundServiceType 需要对应权限声明
- Android 14+ 必须声明具体服务类型

# 服务命令:
ShellService.start(context)    # 启动 (try/catch SecurityException)
WakeLock 防止 CPU 休眠
IMPORTANCE_DEFAULT 级别保活 (国产ROM 最低要求)
```

## 存储访问

MengPaw 数据目录 (`{BASE}` = `Context.filesDir`):

```
{filesDir}/
├── Agent文档/{name}/     ← 工作区 (Agent 可读写删, 含 memory/ 三轨记忆)
├── 技能剧本/              ← skill 文件
├── 会话检查点/            ← 会话持久化
├── 插件仓库/              ← 下载的插件 JAR
├── 截图存档/              ← UI 截图
├── sessions/              ← 会话消息文件
└── session_history.json   ← 会话索引
```

```
agent.ls / agent.read / agent.write / agent.rm / agent.mkdir  ← 工作区内操作
fs.ls / fs.cat ← 插件文件命令 (需安装 plugin-fs)
```

## 后台限制

| 版本 | 限制 |
|------|------|
| Android 8+ | 后台服务限制, startService→startForegroundService |
| Android 12+ | 后台启动限制, ForegroundServiceStartNotAllowedException |
| Android 13+ | 通知运行时权限, startForegroundService 从广播接收器可能抛异常 |
| Android 14+ | registerReceiver 必须带 RECEIVER_EXPORTED/NOT_EXPORTED |

**OEM 厂商特殊限制**:

| 厂商 | 关键特性 | 用户操作 |
|------|---------|------|
| 小米 MIUI/HyperOS | 后台自启动管理 | 设置→应用→MengPaw→自启动:允许 |
| 华为 HarmonyOS | 关联启动+电池优化 | 手机管家→启动管理→手动管理 |
| OPPO ColorOS | 应用速冻 | 设置→电池→应用速冻→关闭 |
| vivo OriginOS | 后台高耗电 | 设置→电池→后台高耗电→允许 |
| 荣耀 MagicOS | 类似华为,更激进 | 所有华为建议 + specialUse |

**保活策略**: WAKE_LOCK + FOREGROUND_SERVICE_DATA_SYNC + 前台通知 IMPORTANCE_DEFAULT

## ADB 命令速查

```
# 无线调试
adb connect 192.168.x.x:port
adb devices
adb disconnect 192.168.x.x:port

# 安装/卸载
adb install -r app.apk
adb uninstall com.mengpaw.shell

# 日志
adb logcat -s MengPawTwin:I     # 只看孪生日志
adb logcat | grep -E "FATAL|mengpaw"
adb logcat -v time *:E          # 只看错误

# 设备信息
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell dumpsys package com.mengpaw.shell | grep version

# 文件操作
adb shell ls /sdcard/
adb pull /sdcard/file.txt
adb push file.txt /sdcard/

# 截图
adb exec-out screencap -p > screen.png
```

## 设备操控增强 (MengPaw 特有)

### 悬浮窗
```
sys.overlay.show <文本> [--x 100] [--y 200] [--size 14] [--color #FFF]
sys.overlay.update <文本>
sys.overlay.hide
```
- 需要 SYSTEM_ALERT_WINDOW 权限（系统设置类，需用户手动在设置→应用→在其他应用上层显示中开启）
- 单例管理：show 覆盖旧窗口
- 用途：进度提示、状态监控、警告信息

### 日历
```
sys.calendar.calendars                                    # 列出所有可用日历
sys.calendar.add "标题" "yyyy-MM-dd HH:mm" [--end ...] [--desc ...] [--cal ID]
sys.calendar.list [--days 7]
sys.calendar.delete <ID>
```
- 需要 READ_CALENDAR + WRITE_CALENDAR 权限（标准运行时弹窗）
- 日历 ID 自动检测可写入日历，也可用 --cal 指定
- delete 需要从 list 获取的 ID

### 用户交互对话框 (v0.36.x)
```
sys.dialog.confirm <标题>                    # 确定/取消
sys.dialog.text <提示> [默认值]              # 文本输入
sys.dialog.radio <标题> <选项1> [选项2...]    # 单选 (逗号分隔)
sys.dialog.checkbox <标题> <选项...>          # 多选
sys.dialog.spinner <标题> <选项...>           # 下拉式单选
sys.dialog.sheet <标题> <选项...>             # 底部选项列表 (点击即选)
sys.dialog.date / time / counter / color      # 日期/时间/数值/颜色
sys.dialog.speech <提示>                      # 语音输入对话框
```
- 需要前台 Activity；命令挂起等待用户操作 (120s 超时)；输出 `key: value` 纯文本
- 风险等级 MID（默认拒绝，TRUSTED 放行）；语音输入需 RECORD_AUDIO

### 语音/朗读/录音 (v0.36.x)
```
sys.stt.listen <提示>            # 语音转文字 (RECORD_AUDIO, 60s 超时)
sys.tts.speak <文本> [lang:zh-CN]  # 文字转语音朗读 (无需权限)
sys.tts.engines                  # 列出 TTS 引擎
sys.mic.record [秒数]             # 录音→输出目录 mic_*.m4a (默认 30s, 最长 600s)
sys.mic.stop                     # 提前停止录音
sys.torch.on / sys.torch.off     # 手电筒 (需 CAMERA)
```
- stt/mic 风险等级 MID；tts/torch 为普通

### 敏感数据命令 (v0.36.x, 全部 MID — 默认拒绝, TRUSTED 放行)
```
sys.contacts.list [条数]          # 联系人列表 (READ_CONTACTS)
sys.sms.send <号码> <内容>        # 发送短信 (SEND_SMS)
sys.sms.list [条数]               # 短信收件箱 (READ_SMS)
sys.calllog.list [条数]           # 通话记录 (READ_CALL_LOG)
sys.phone.call <号码>             # 直接拨号 (CALL_PHONE)
```
- 权限不足时输出引导: 先 `sys.permission.request <权限名>`

### 其他设备能力 (v0.36.x)
```
sys.notification.list               # 系统通知列表 (需开启『通知使用权』)
sys.download <url> [文件名]         # 下载到公共 Downloads (系统 DownloadManager)
sys.download.status <id>            # 查询下载状态/进度/失败原因
sys.wallpaper.set <路径|content://> # 设置壁纸
sys.toast <文本>                    # 气泡提示
sys.wakelock.acquire / release      # 唤醒锁 (30 分钟自动释放)
sys.ir.transmit <频率Hz> <时长>...  # 红外发射 (需红外硬件)
sys.usb.list                        # USB 设备列表
sys.usb.request <设备名>            # 申请 USB 访问授权 (弹系统窗口)
sys.wifi.scan                       # 扫描附近 WiFi (需定位权限+定位开关)
```
- notification.list 非运行时权限：设置 → 通知使用权 → 开启 MengPaw
- usb.request / wifi.scan 风险等级 MID；其余为普通
- job-scheduler 由 sys.alarm.set 承接；lock-screen 由 sys.screen.off 承接

### Termux 脚本执行
```
skill.run termux    # 获取完整使用指南
```
- 通过 `am startservice` 调用 Termux RUN_COMMAND Intent
- 没有脚本运行时？用 > 文件重定向获取输出，然后 agent.read
- 不需要 root，不需要 APK 改动

## 常见问题排查

**INSTALL_FAILED_VERSION_DOWNGRADE**
新 versionCode < 旧 versionCode → 卸载旧版或增加 versionCode。
公式: `versionCode = major*10000 + minor*100 + patch*10`

**ForegroundServiceDidNotStartInTimeException**
`startForeground()` 未在 5s 内调用。try/catch 后必须 `stopSelf()`。

**SecurityException: registerReceiver (Android 14+)**
```
if (Build.VERSION.SDK_INT >= 34) {
    registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
}
```

**NoSuchMethodError: NsdServiceInfo.setAttribute (API 33+)**
```
if (Build.VERSION.SDK_INT >= 33) {
    serviceInfo.setAttribute("key", "value")
}
```

**应用闪退 → 日志获取**:
```
agent.read /sdcard/Download/crash.log    # 崩溃日志
sys.permission.list                      # 检查权限
agent.storage                            # 检查磁盘空间
```
