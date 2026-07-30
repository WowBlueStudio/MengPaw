---
name: compilation-issues
description: v0.6.0 开发期间编译问题总结 — 预防性参考
metadata:
  type: reference
---

# 编译问题总结 — v0.6.0 开发期间

> 每个问题包含：现象、根因、修复方式、预防建议

> 以下 10 个编译问题的现象、根因、修复、预防见文末速查表。

## 编译问题速查表

| 编号 | 错误特征 | 根因 | 修复方向 |
|------|---------|------|---------|
| 1 | `RowScope.AnimatedVisibility` cannot be called | weight() 引发 scope 继承 | 提取独立 Composable |
| 2 | `Cannot access private` | 跨方法访问 private 字段 | 添加公开方法 |
| 3 | `Unresolved reference` (变量) | LaunchedEffect 捕获后声明变量 | 移动声明到使用前 |
| 4 | 字符串插值编译失败 | `$${...}` 语法 | 改用拼接 |
| 5 | `Unresolved reference` (函数参数) | 参数名与签名不匹配 | 核对源文件签名 |
| 6 | `Unresolved reference` (类/函数) | 缺少 import | 添加 import |
| 7 | `padding` candidate 不匹配 | 不存在的参数组合 | 拆分为两次调用 |
| 8 | `weight` cannot be invoked | 非 scope 内使用 | 由调用方传入 |
| 9 | 联合类型推断 `Any` | when 返回不同具体类型 | 消费处用 when 区分 |
| 10 | 文件被截断 | Write 工具语义误解 | 用 Edit 代替 Write |
