---
name: browser-form
description: 表单自动化指南 — 登录、注册、搜索、多步骤向导、CAPTCHA、文件上传、错误处理
enabled: true
category: browser
---
# 表单自动化指南

## 通用表单填写的五步法则

```
1. 定位到表单元素（CSS 选择器）
2. 填充字段值（type / select / check）
3. 提交表单（click submit / submit）
4. 等待结果（wait.nav / wait.selector）
5. 验证结果（检查反馈文本或 URL 变化）
```

## 一、登录流程

### 标准登录
```
browser.open "https://example.com/login"
browser.wait.selector "#login-form"
browser.type "#username" "user@example.com"
browser.type "#password" "mySecureP@ss123"
browser.click "#login-btn"
browser.wait.nav 10000

# 验证登录成功
let $welcome = browser.text ".user-profile"
if ($welcome contains "欢迎") {
  # 登录成功
} else {
  browser.screenshot "login-failed.png"
}
```

### 带验证码的登录
```
browser.open "https://example.com/login"
browser.type "#username" "user@example.com"
browser.type "#password" "mySecureP@ss123"
browser.screenshot "captcha.png"          # 截图验证码区域

# 提示用户输入验证码（人工介入）
let $captcha = await_user_input("请输入验证码")
browser.type "#captcha-input" $captcha
browser.click "#login-btn"
browser.wait.nav
```

### OAuth / 第三方登录
```
browser.click "button.wechat-login"
browser.wait.selector ".qr-code" 10000
browser.screenshot "qrcode.png"
# 用户扫码后在手机上确认
browser.wait.selector ".redirect-hint" 60000
browser.wait.nav
```

## 二、注册表单

### 字段填充
```
browser.open "https://example.com/register"
browser.type "#nickname" "MengPaw用户"
browser.type "#email" "user@example.com"
browser.type "#password" "Abc@123456"
browser.type "#confirm-password" "Abc@123456"
browser.select "#country" "CN"
browser.check "#agree-terms"
browser.click "#register-btn"
browser.wait.nav 15000

# 检查注册结果
let $msg = browser.text ".notification"
```

## 三、搜索表单

### 普通搜索
```
browser.open "https://www.baidu.com"
browser.type "#kw" "MengPaw 浏览器控制"
browser.click "#su"
browser.wait.selector "#content_left"
let $results = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('.result')).map(r => ({
    title: r.querySelector('h3')?.innerText,
    url: r.querySelector('a')?.href
  }))
)"
```

### 带筛选条件的搜索
```
browser.open "https://example.com/products"
browser.select "#category" "electronics"
browser.type "#min-price" "100"
browser.type "#max-price" "1000"
browser.check "#in-stock"
browser.click "#search-btn"
browser.wait.selector ".product-grid"
```

## 四、多步骤向导

### 步骤式表单
```
# 步骤 1：个人信息
browser.open "https://example.com/wizard"
browser.type "#name" "张三"
browser.select "#gender" "male"
browser.click "#step1-next"

# 步骤 2：联系方式
browser.wait.selector "#step2-form"
browser.type "#phone" "13800138000"
browser.type "#address" "北京市朝阳区"
browser.click "#step2-next"

# 步骤 3：确认提交
browser.wait.selector "#step3-review"
browser.click "#final-submit"
browser.wait.nav

# 验证完成页
let $completed = browser.text ".success-message"
```

## 五、CAPTCHA 处理策略

| 类型 | 处理方式 |
|------|----------|
| 文本验证码 | 截图 → AI 识别（第三方 OCR）→ 填入 |
| 滑块验证码 | 截图 → 通知用户手动滑动 |
| reCAPTCHA | 浏览器控制无法绕过；需切换代理/IP |
| 短信验证码 | 填入手机号 → 等短信 → 用户提供 → 填入 |
| 无感验证 | 检查是否因风控触发；如触发则降低频率或切换账号 |

### CAPTCHA 通用处理模板
```
browser.screenshot "page-state.png"
let $hasCaptcha = browser.visible "#captcha-image, .captcha, .recaptcha"
if ($hasCaptcha) {
  notify_user("遇到验证码，请查看截图并描述验证码内容")
  # 等待用户响应...
  browser.type "#captcha-input" $userResponse
  browser.click "#verify-btn"
  browser.wait.nav
}
```

## 六、文件上传

```
browser.open "https://example.com/upload"
browser.click "#upload-btn"              # 触发文件选择器
# 注意：Android WebView 不支持系统文件选择器交互
# 替代方案：
browser.eval "document.querySelector('#file-input').style.display='block'"
browser.eval "document.querySelector('#file-input').value=''"  # 多数浏览器不支持设置 value
# 最佳方案：通过 WebView 的 shouldIntercept 伪造文件上传
```

文件上传在 Android WebView 中受限，建议通过 `browser.inject` 注入 JS 模拟 FormData 提交，或使用 `eval` 直接构造 AJAX 请求上传文件。

## 七、表单验证错误处理

### 检测验证错误
```
browser.click "#submit-btn"
browser.wait 1000

# 检测内联错误
let $errors = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('.error, .field-error, [data-error]'))
    .map(e => ({field: e.id || e.name, msg: e.innerText}))
)"

# 检测 toast/notification
let $toast = browser.text ".toast, .alert-error"

# 检测 URL 参数中的错误
let $currentUrl = browser.url
```

### 常见错误处理
```
# 邮箱格式错误
→ browser.type "#email" ""

# 必填字段为空
→ document.querySelectorAll('[required]').forEach(el => {...})

# 密码强度不足
→ 调整密码包含大小写 + 数字 + 特殊字符

# 用户名已存在
→ 更换用户名重试

# 提交超时
→ 增加 wait.nav 超时值，或检查网络
```

## 八、最佳实践

1. **每次填写前清空字段**：`browser.type` 内部会 focus + selectAll + 删除，确保干净输入
2. **等待字段可见**：使用 `browser.wait.selector` 确保元素加载完成再操作
3. **逐字段填充**：不要在未验证当前字段的情况下快速填充后续字段
4. **截图存档**：关键步骤后 `browser.screenshot` 留存证
5. **失败重试**：登录/提交失败后清空关键字段重试，而非盲目叠加
