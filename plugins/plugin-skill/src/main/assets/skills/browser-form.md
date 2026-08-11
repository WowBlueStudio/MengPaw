---
name: browser-form
description: 表单自动化指南 — 登录、注册、搜索、多步骤向导、CAPTCHA、文件上传、错误处理 (经 page.* 命令面)。触发词：「填表单」「帮我登录」「注册账号」「提交表单」「选下拉选项」
enabled: true
category: browser
source: core
---
# 表单自动化 (经 MCP 工具)

> 通道: am 桥 `page.*` 命令 (推荐) / `browser.mcp.invoke` (过渡)。主手册: `skill.run browser-control`。
> am 桥形式: `am startservice -n com.mengpaw.browser/.service.RunCommandService --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,<命令串>"`。

## 适用场景

表单填写、登录注册、搜索提交、多步骤向导、下拉选择、错误处理。

## 执行步骤

```
1. sys.browser.open https://example.com/login
2. page.goto https://example.com/login
3. page.fill #username <用户>
4. page.fill #password <密码>
5. page.click button[type=submit]
6. page.title   # 验证跳转
```

## 命令清单（page.*）

`page.goto`（跳转）/ `page.fill`（输入）/ `page.click`（点击）/ `page.eval`（JS 执行）/
`page.content`（页面提取）/ `page.screenshot`（截图核对）/ `page.select`（下拉选值）。
表单常用：goto → fill → click → eval/title 验证。

## 常见场景

### 搜索
```
page.fill #q MengPaw
page.eval document.querySelector('#q').form.submit()
```

### 多步骤向导
每步: fill → click → `page.eval document.querySelector('.step-indicator').textContent` 确认进度。

### 下拉选择
`page.click` 打开下拉 → `page.select` 选值:
```
page.select select option-2
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
