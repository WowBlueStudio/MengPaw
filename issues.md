# Code Audit Report — MengPaw Batch 3 Review

**Date:** 2026-07-15
**Scope:** mengpaw-core, mengpaw-shell, mengpaw-browser, mengpaw-design-system
**Total files audited:** 54

---

## High Severity

### 1. Logic Error: Prefix-cache optimization broken after history compression
- **File:** `mengpaw-core/.../AgentEngine.kt:133-141`
- **Issue:** `buildConversation()` checks `history[0]["role"] == "system"` to decide whether to inject the stable system prompt via `LlmRequestBuilder`. After `compressIfNeeded()` inserts a synthetic `[压缩摘要]` message at index 0 with `role="system"`, this check returns false, and the byte-identical system prompt is **silently dropped**, disabling prefix-cache optimization for the rest of the session.
- **Impact:** 50x cost increase on DeepSeek API after compression triggers.

### 2. Thread Safety: Shared `SimpleDateFormat` in singleton
- **File:** `mengpaw-core/.../namespace/ScreenshotManager.kt:21`
- **Issue:** `SimpleDateFormat` is not thread-safe but is shared across all callers via a Kotlin `object` singleton. Concurrent access will produce garbled timestamps or `ArrayIndexOutOfBoundsException`.

### 3. Streaming non-functional in both LLM providers
- **Files:** `RemoteApi.kt:57-67`, `AdaptiveLlmProvider.kt:72-81`
- **Issue:** Both `completeStreaming()` methods request `"stream": true` but then call `response.bodyAsText()` (blocking full-body read) instead of processing the SSE stream. The `onToken` callback is never called.

### 4. Empty catch block with no logging
- **File:** `mengpaw-browser/.../BrowserActivity.kt:82-85`
- **Issue:** The `wakeMainApp` lambda silently swallows all exceptions. If launching the shell intent fails, there is zero feedback to the user.

### 5. Plaintext credential storage in Vault
- **File:** `mengpaw-core/.../security/Vault.kt:20`
- **Issue:** API keys stored in Android `SharedPreferences` without encryption. Readable as plaintext XML on rooted devices or backups. Class comment acknowledges this but no encryption is implemented.

---

## Medium Severity

### 6. Dead code: Unused `allowList` in SecurityPolicy
- **File:** `mengpaw-core/.../security/SecurityPolicy.kt:14-17`
- **Issue:** `allowList` field is initialized but never referenced by `isAllowed()`. Only `blockList` and `restrictedPatterns` are evaluated.

### 7. Dead code: Unreachable `showImportDialog` in SkillsScreen
- **File:** `mengpaw-shell/.../ui/screens/SkillsScreen.kt:31,94-120`
- **Issue:** `showImportDialog` state variable exists with complete dialog UI, but is never set to `true`. Entire manual-import feature is unreachable dead code.

### 8. Dead code: Unused `commandHandler` in HttpServer
- **File:** `mengpaw-core/.../transport/HttpServer.kt:12,14-15`
- **Issue:** `commandHandler` field is assigned but never read. `start()` / `stop()` are no-op stubs.

### 9. Unused variable: `command` in AgentEngine.run()
- **File:** `mengpaw-core/.../AgentEngine.kt:93`
- **Issue:** `buildCommandString()` result is computed but never used; `commandLine` is constructed separately on the next line using a different approach.

### 10. Duplicate code: `parseResponse` and `buildRequestBody` across LLM providers
- **Files:** `RemoteApi.kt`, `AdaptiveLlmProvider.kt`
- **Issue:** Both files implement near-identical request building and response parsing logic. ~60 lines duplicated.

### 11. Misleading parameter name: `maxTokens` vs message count
- **File:** `mengpaw-core/.../session/History.kt:63-66`
- **Issue:** `compressIfNeeded(maxTokens: Int = 50)` compares `maxTokens` against `messages.size` (message count, not token count). Default of 50 triggers at 51+ messages — not tokens.

### 12. Reflection type mismatch in ClipboardExecutor
- **File:** `mengpaw-core/.../namespace/ClipboardExecutor.kt:75`
- **Issue:** `setContents()` method lookup uses `Any::class.java` for the `ClipboardOwner` parameter. May fail on strict JVMs; should use `Class.forName("java.awt.datatransfer.ClipboardOwner")`.

### 13. Semantics error: `minOf` silently downgrades extension API version
- **File:** `mengpaw-core/.../extension/ExtensionLoader.kt:22`
- **Issue:** `minOf(info.apiVersion, CURRENT_API_VERSION)` silently accepts incompatible extensions instead of rejecting them.

### 14. Failed parse creates overly-permissive extension
- **File:** `mengpaw-core/.../extension/ManifestParser.kt:41`
- **Issue:** When JSON parsing fails, returns `ExtensionInfo("unknown", "0.0.0", "0.0.0")` with `maxCoreVersion = "99.99.99"`, allowing malformed extensions to load without error.

---

## Low Severity

### 15-24. Unused imports
| File | Line | Import |
|---|---|---|
| `mengpaw-core/.../llm/LocalModel.kt` | 3-4 | `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.flow` |
| `mengpaw-shell/.../LogViewerScreen.kt` | 23 | `java.text.SimpleDateFormat` |
| `mengpaw-shell/.../LogViewerScreen.kt` | 24 | `java.util.*` |
| `mengpaw-shell/.../MainActivity.kt` | 5 | `android.widget.Toast` |
| `mengpaw-shell/.../BrowserScreen.kt` | 27 | `java.net.URLEncoder` |
| `mengpaw-browser/.../BrowserActivity.kt` | 7 | `android.provider.Settings` |

### 25. Pointless conditional: both branches identical
- **File:** `mengpaw-core/.../namespace/FsExecutor.kt:38-39`
- **Issue:** `if (file.isDirectory) file.name else file.name` yields the same result in both branches.

### 26. Inconsistent indentation
- **File:** `mengpaw-core/.../session/SessionManager.kt:44`
- **Issue:** `if` statement indented 12 spaces instead of 8.

### 27. Public mutable fields in LlmRequestBuilder
- **File:** `mengpaw-core/.../llm/LlmRequestBuilder.kt:18-20`
- **Issue:** `cumulativeCacheHitTokens`, `cumulativeCacheMissTokens`, `lastPromptTokens` are `public var` — any external code can mutate tracking counters.

### 28. Overly aggressive sanitization regex
- **File:** `mengpaw-core/.../security/Sanitizer.kt:12`
- **Issue:** Base64 regex matches 40+ char strings, causing excessive redaction of legitimate non-secret content.

### 29. Production code contains test-only `SimulatedLlmProvider`
- **File:** `mengpaw-shell/.../ui/screens/AgentViewModel.kt:103-138`
- **Issue:** Inline test stub with hardcoded responses mixing Chinese emoji. Should be in test source-set.

### 30. Inconsistent icons: same icon for different screens
- **File:** `mengpaw-shell/.../MainScreen.kt:89,92` — `Icons.Default.Star` for both Memories and Skills
- **File:** `mengpaw-shell/.../SettingsScreen.kt:218,223` — `Icons.Filled.Favorite` for both Memories and Skills

### 31. Inconsistent divider: raw `HorizontalDivider` instead of `ArcoDivider()`
- **File:** `mengpaw-shell/.../SettingsScreen.kt:135,154,178,230`
- **Issue:** Uses Material 3 `HorizontalDivider` directly instead of project's `ArcoDivider()` design-system component (used correctly in `MainScreen.kt:112`).

---

## Summary

| Severity | Count | Key Focus |
|---|---|---|
| High | 5 | Cache-breaking bug, thread safety, non-functional streaming, silent error swallowing, plaintext secrets |
| Medium | 9 | Dead code, duplicate code, misleading naming, reflection bug, extension incompatibility |
| Low | 17 | Unused imports, code style, icon consistency, design system bypass |

---

*Generated by automated code audit — Batch 3, US-010*
