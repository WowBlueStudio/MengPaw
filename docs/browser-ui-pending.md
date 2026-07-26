# 浏览器 UI 需求

> 最后更新: 2026-07-27 | 来源: BrowserActivity.kt 因多次编辑/恢复循环导致大量修改丢失

---

## ✅ 已完成 (2026-07-27 会话)

### 架构拆分
| 阶段 | 说明 |
|------|------|
| Phase 1 | 提取 11 个文件: data/BrowserTypes, BrowserPrefs, HistoryStore, service/GoogleTranslate, web/WebViewFactory, util/AdBlocker, util/SmartNavigate, util/DownloadUtil, ui/components/BrowserTabChip, ui/components/SearchEngineLogo, ui/theme/BrowserThemeConfig |
| Phase 2 | 提取 7 个弹窗: BrowserSettingsDialog, BrowserAgentSettingsDialog, BrowserHistoryDialog, BrowserPasswordDialog, BrowserTranslateDialog, BrowserImagePickerDialog, BrowserMarkdownViewerDialog |
| 结果 | BrowserActivity.kt: 1594 → 741 行 (-54%) |

### P0 核心功能缺陷 ✅
| # | 项目 | 说明 |
|---|------|------|
| 1 | 暗色模式设置开关 | BrowserSettingsDialog 新增 Switch，含 webViewMap reload |
| 2 | 浏览器 UA 不写死 | WebViewFactory.kt 删除硬编码 UA 行 |
| 3 | 后退键关闭标签 | handleBack lambda + onSystemBack + DisposableEffect |
| 4 | 搜索框引擎按钮防焦点 | pointerInput + detectTapGestures 替代 IconButton.clickable |
| 5 | Tab 键切换搜索引擎 | onPreviewKeyEvent 拦截 KEYCODE_TAB |

### P1 UI 细节修复 ✅
| # | 项目 | 说明 |
|---|------|------|
| 6 | 顶栏图标统一品牌色 | 主页/后退/前进/刷新/菜单全部改为 ThemeColors.brand |
| 7 | 地址栏 UI 修复 | fillMaxWidth + 48dp + Go 按钮偏移 1dp + 去关闭按钮 |
| 8 | 搜索页垂直居中 | Box(weight(1f)) 上下平衡 |
| 9 | 搜索引擎图标 SVG | painterResource 替换 Canvas generateLogoBitmap |

### P2 功能增强 (部分)
| # | 项目 | 状态 |
|---|------|------|
| 10 | WebView 版本显示 + 更新源 | ✅ Settings 新增版本+酷安/APKCombo 按钮 |
| 15 | file:// + onReceivedError + navigate 等待 | ✅ WebViewFactory 修复 |

---

## ❌ 未完成需求

| # | 项目 | 优先级 |
|---|------|--------|
| 11 | 收藏功能 (BrowserPrefs 扩展 + 星标 UI + 弹窗) | P2 |
| 12 | 标签页 UI 重构 (平板 TabChip 标签样式 + 长按菜单) | P2 |
| 13 | 标签页弹窗 (手机模式 favicon + 菜单) | P2 |
| 14 | 菜单项去 emoji (等 P2-12/13 时一并处理) | P2 |

### 🚫 暂不可行
| 项目 | 原因 |
|------|------|
| 右击支持 | Compose BOM 2024.12.01 缺少 onPointerEvent API |

---

## ⚠️ 编辑安全守则 (已完成拆分，风险大幅降低)

1. BrowserActivity.kt 已从 1594 → 741 行，每个弹窗独立文件
2. createWebView 在 web/WebViewFactory.kt，WebViewClient 回调区不再脆弱
3. 每次修改后 `./gradlew :mengpaw-browser:compileDebugKotlin`
4. 恢复文件: `git show HEAD:<path> > <target>`
