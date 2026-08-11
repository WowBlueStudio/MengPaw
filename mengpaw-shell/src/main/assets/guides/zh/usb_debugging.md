# USB 调试指南

USB 调试是连接电脑与设备的标准通道，用于安装 APK、抓取日志、执行 ADB 命令。MengPaw 的 APK 交付与崩溃排查（`adb logcat` / `dumpsys dropbox`）都依赖它。

## 开启步骤

1. 打开系统「设置」→「关于平板电脑 / 关于手机」
2. 连续点击「版本号」7 次，直到提示"已进入开发者模式"
3. 返回「设置」，进入新出现的「开发者选项」
4. 打开「USB 调试」开关（提示风险时选择允许）
5. 用数据线连接电脑，在弹窗中勾选"始终允许"并确认

## 常见用途

- **安装 APK**：`adb install mengpaw-shell-vX.Y.Z-release.apk`
- **抓取崩溃日志**：`adb logcat -d > crash.txt`
- **查看系统 Dropbox 崩溃记录**：`adb shell dumpsys dropbox --print`
- **无线调试**：`adb tcpip 5555` 后 `adb connect <设备IP>:5555`（IP 每次配对可能变化）

## 注意事项

- USB 调试开启后，不要在公共电脑上勾选"始终允许"，防止他人未经授权操作设备
- 调试完成后可关闭「USB 调试」以降低风险
- 若电脑识别不到设备，检查数据线是否支持数据传输（部分线只供电）
