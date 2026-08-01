---
name: browser-form
description: 表单自动化指南 — 登录、注册、搜索、多步骤向导、CAPTCHA、文件上传、错误处理 (经 MCP 工具)
enabled: true
category: browser
---
# 表单自动化 (经 MCP 工具)

> 通道: `browser.mcp.invoke` 6 工具。主手册: `skill.run browser-control`。

## 基础流程

```
1. sys.browser.open https://example.com/login
2. browser.mcp.invoke browser_navigate {"url":"https://example.com/login"}
3. browser.mcp.invoke browser_type {"selector":"#username","text":"<用户>"}
4. browser.mcp.invoke browser_type {"selector":"#password","text":"<密码>"}
5. browser.mcp.invoke browser_click {"selector":"button[type=submit]"}
6. browser.mcp.invoke browser_eval {"script":"document.title"}   # 验证跳转
```

## 常见场景

### 搜索
```
browser.mcp.invoke browser_type {"selector":"#q","text":"MengPaw"}
browser.mcp.invoke browser_eval {"script":"document.querySelector('#q').form.submit()"}
```

### 多步骤向导
每步: type → click → `browser_eval {"script":"document.querySelector('.step-indicator').textContent"}` 确认进度。

### 下拉选择
`browser_click` 打开下拉 → `browser_eval` 选值:
```
browser.mcp.invoke browser_eval {"script":"var s=document.querySelector('select');s.value='option-2';s.dispatchEvent(new Event('change'))"}
```

## 表单状态探测 (before/after)

```
browser.mcp.invoke browser_eval {"script":"JSON.stringify({errs:document.querySelectorAll('.error').length,ok:!!document.querySelector('.success')})"}
```

## 失败处理

| 现象 | 处理 |
|------|------|
| 元素找不到 | 页面 JS 渲染 — navigate 后等 1-2s 再操作; 用 eval 探测 DOM |
| 提交无反应 | eval 检查表单 action/method; 换 `form.submit()` |
| CAPTCHA | 无法自动过 — 告知用户手动完成, 完成后继续 |
| 文件上传 | 无法经 MCP 上传 — 用 `agent.write` 存文件 + 引导用户 |

## 安全

- 凭据只放 Vault / 用户输入, 不写进命令历史
- 敏感操作前先 `browser_extract` 核对页面状态
