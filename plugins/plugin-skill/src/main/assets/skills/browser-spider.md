---
name: browser-spider
description: 网页抓取工作流指南 — 导航、提取、解析、分页、去重、持久化
enabled: true
category: browser
---
# 网页抓取工作流指南

## 标准爬取流水线

```
[导航] → [内容提取] → [数据解析] → [分页] → [去重] → [持久化]
```

## 一、页面导航

### 基本导航
```
browser.open "https://example.com"
browser.wait.nav 10000
```

### 设置视口与 UA（反检测）
```
browser.viewport 360x740                 # 模拟移动端
browser.userAgent "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 ..."
```

### Cookie 管理（维持登录态）
```
browser.cookies.set "session=abc123" ".target-site.com"
browser.open "https://target-site.com/dashboard"
```

## 二、内容提取

### 提取纯文本
```
let $title = browser.text "h1.article-title"
let $body = browser.text "div.article-content"
```

### 提取属性
```
let $imgUrl = browser.attr "img.cover" "src"
let $linkUrl = browser.attr "a.read-more" "href"
```

### 批量提取（通过 eval）
```
let $items = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('.item')).map(el => ({
    title: el.querySelector('.title')?.innerText,
    link: el.querySelector('a')?.href,
    price: el.querySelector('.price')?.innerText
  }))
)"
```

## 三、数据解析

### JSON-LD（结构化数据）
```
browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('script[type=\"application/ld+json\"]'))
    .map(s => JSON.parse(s.textContent))
)"
```

### 微数据 (Microdata)
```
browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('[itemscope]')).map(el => ({
    type: el.getAttribute('itemtype'),
    props: Array.from(el.querySelectorAll('[itemprop]')).map(p => ({
      name: p.getAttribute('itemprop'),
      value: p.getAttribute('content') || p.innerText
    }))
  }))
)"
```

### HTML 表格解析
```
browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('table')).map(table => ({
    headers: Array.from(table.querySelectorAll('th')).map(th => th.innerText),
    rows: Array.from(table.querySelectorAll('tr')).map(
      tr => Array.from(tr.querySelectorAll('td')).map(td => td.innerText)
    ).filter(r => r.length > 0)
  }))
)"
```

## 四、分页处理

### 1. 「下一页」按钮
```
for (let $page = 1; $page <= 10; $page++) {
  browser.wait.selector ".item" 5000
  # 提取当前页数据...
  browser.click "a.next-page"
  browser.wait.nav 8000
}
```

### 2. 无限滚动（触发加载）
```
for (let $i = 0; $i < 20; $i++) {
  browser.scroll 1000
  browser.wait 1500
  let $newContent = browser.eval "document.querySelectorAll('.item').length"
  # 监测是否还有新内容加载
}
```

### 3. URL 参数分页
```
for (let $p = 1; $p <= 50; $p++) {
  browser.nav "https://example.com/list?page=$p"
  browser.wait.selector ".item"
  # 提取数据...
}
```

## 五、去重策略

```
# 使用内存集合记录已访问 URL
let $visited = browser.eval "JSON.parse(localStorage.getItem('crawled_urls') || '[]')"
let $newLinks = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('a[href]'))
    .map(a => a.href)
    .filter(h => !$visited.includes(h))
)"
# 处理新链接后更新 visited 列表
browser.eval "localStorage.setItem('crawled_urls', JSON.stringify([...new Set([...$visited, ...$newLinks])]))"
```

## 六、持久化

### 存储到文件
```
# 方式 1：浏览器内暂存后 eval 导出
browser.eval "localStorage.setItem('scraped_data', JSON.stringify(allData))"
fs.write "scraped_data.json" (browser.eval "localStorage.getItem('scraped_data')")

# 方式 2：逐批追加
fs.append "results.txt" $extractedData + "\n---\n"
```

## 七、站点的爬取模式

### 1. Sitemap 驱动
```
browser.open "https://example.com/sitemap.xml"
let $urls = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('loc')).map(l => l.textContent)
)"
# 遍历 $urls 逐一访问
```

### 2. 文章列表页
```
browser.open "https://blog.example.com"
browser.wait.selector "article"
let $articles = browser.eval "JSON.stringify(
  Array.from(document.querySelectorAll('article')).map(a => ({
    url: a.querySelector('a')?.href,
    title: a.querySelector('h2')?.innerText,
    summary: a.querySelector('p.summary')?.innerText
  }))
)"
```

### 3. 登录墙内容
```
browser.open "https://example.com/login"
browser.type "#email" "user@example.com"
browser.type "#password" "secret"
browser.click "#login-btn"
browser.wait.nav
browser.nav "https://example.com/restricted-content"
```

## 八、频率控制与反反爬

```
# 每次请求间等待，模拟人类行为
browser.wait (2000 + Math.random() * 3000)
browser.scroll (100 + Math.random() * 500)
browser.wait (500 + Math.random() * 1000)

# 随机 User-Agent 轮换
browser.userAgent $randomUA
```

## 九、错误恢复

```
对于爬取过程中的失败：
1. 捕获 browser.wait.selector 超时 → 跳过当前项
2. 捕获 browser.wait.nav 超时 → browser.tab.close → 新标签页重试
3. 遇到 CAPTCHA → browser.screenshot → 通知用户手动处理
4. 保持已提取数据的阶段性持久化，避免全量重爬
```
