---
name: termux
description: Termux 脚本执行桥接。通过 am startservice 在 Termux 环境中执行命令和脚本。
enabled: true
category: system
---

# Termux 脚本执行桥接

你可以在 Android 设备上通过 Termux 执行 shell 命令和脚本——无需 root。

## 前置条件

1. 确认 Termux 已安装：
   ```
   sys.app.launch com.termux
   ```
   如果未安装，引导用户从 F-Droid 或 GitHub 安装 Termux。

2. Termux 需配置允许外部调用。让用户在 Termux 中执行：
   ```
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   ```
   然后重启 Termux。

3. Android 10+ 需要授予 Termux "在其他应用上层显示"权限（设置 → 应用 → Termux → 高级）。

## 执行命令

使用 `am startservice` 调用 Termux 的 RUN_COMMAND 服务：

```bash
am startservice --user 0 -n com.termux/com.termux.app.RunCommandService \
  -a com.termux.RUN_COMMAND \
  --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash' \
  --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,你的命令' \
  --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home' \
  --ez com.termux.RUN_COMMAND_BACKGROUND true
```

注意：`--esa` 参数用逗号分隔多个参数值，不能用空格（空格会被解析为新参数）。

## 执行脚本

**方式 A：每次重写脚本文件**
```
agent.write /sdcard/tmp_script.sh "#!/bin/bash
echo 'Hello from MengPaw'
date
uname -a"

# 然后执行
am startservice ... --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,bash /sdcard/tmp_script.sh'
```

**方式 B：直接在命令中内联**
```
am startservice ... --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,ls -la /sdcard/ && echo done'
```

## 常用场景

### 网络诊断（含结果）
```
# 1. 执行并保存输出
-c,ping -c 3 8.8.8.8 > /sdcard/ping_result.txt 2>&1

# 2. 稍后读取
agent.read /sdcard/ping_result.txt

# 3. 清理
agent.rm /sdcard/ping_result.txt --force
```

### Python 脚本
```
-c,python3 -c "print('Hello from Python')"
```

### 数据分析（写脚本→执行→读结果）
```
# 1. 写脚本
agent.write /sdcard/analyze.py "
import os
total = 0
for f in os.listdir('/sdcard/Download'):
    print(f)
    total += 1
print(f'Total: {total} files')
"

# 2. 执行
-c,python3 /sdcard/analyze.py > /sdcard/result.txt 2>&1

# 3. 读结果
agent.read /sdcard/result.txt
```

## 获取执行结果

Termux 后台执行默认无结果回传。用文件重定向获取输出：

```
# 1. 执行命令，输出写入文件
am startservice ... --esa com.termux.RUN_COMMAND_ARGUMENTS \
  '-c,ls -la /sdcard/ > /sdcard/termux_out.txt 2>&1'

# 2. 等待 1-2 秒
# （Termux 后台执行需要时间）

# 3. 读取结果
agent.read /sdcard/termux_out.txt

# 4. 清理
agent.rm /sdcard/termux_out.txt --force
```

## 限制

- 命令总长度 < 128KB（Android Intent 限制）
- 后台执行无终端 UI，用 > 文件重定向获取结果
- 复杂脚本写文件 → 执行 → `agent.read` 读结果 → 清理
- 脚本放 `/sdcard/` 可能被其他应用修改，敏感操作优先每次重写
- 首次执行时系统可能弹窗要求确认
- Termux 需要在后台保持运行（关闭电池优化）
