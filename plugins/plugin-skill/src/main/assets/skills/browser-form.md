---
name: browser-form
description: 表单自动化指南 — 登录、注册、搜索、多步骤向导、CAPTCHA、文件上传、错误处理 (经 MCP 工具)。触发词：「填表单」「帮我登录」「注册账号」「提交表单」「选下拉选项」
enabled: true
category: browser
source: core
---
# 表单自动化 (经 MCP 工具)

> 通道: `browser.mcp.invoke` 6 工具。主手册: `skill.run browser-control`。

## 适用场景

表单填写、登录注册、搜索提交、多步骤向导、下拉选择、错误处理。

## 执行步骤

```
1. sys.browser.open https://example.com/login
2. browser.mcp.invoke browser_navigate {"url":"https://example.com/login"}
3. browser.mcp.invoke browser_type {"selector":"#username","text":"<用户>"}
4. browser.mcp.invoke browser_type {"selector":"#password","text":"<密码>"}
5. browser.mcp.invoke browser_click {"selector":"button[type=submit]"}
6. browser.mcp.invoke browser_eval {"script":"document.title"}   # 验证跳转
```

## MCP 工具清单（browser.mcp.invoke）

`browser_navigate`（跳转）/ `browser_type`（输入）/ `browser_click`（点击）/ `browser_eval`（JS 执行）/ `browser_extract`（页面提取）/ `browser_screenshot`（截图核对）。表单常用：navigate → type → click → eval 验证。

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

### 表单状态探测 (before/after)

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
| 输入疑似被清空 | 页面重渲染 — type 后立即 eval 验证 value 再继续 |

## 注意事项

- 凭据只放 Vault / 用户输入, 不写进命令历史
- 敏感操作前先 `browser_extract` 核对页面状态
- 提交后必须验证（跳转/title/DOM 状态），不要假定成功

## 进化目标

- 目标: 覆盖常见表单自动化全场景——登录/向导/选择/验证/错误恢复
- 稳定锚点: MCP 工具调用格式（selector+text/script）与「提交后必须验证」原则
- 收敛原则: 升级朝场景覆盖度收敛；新自动化需求开新技能，不污染本技能
