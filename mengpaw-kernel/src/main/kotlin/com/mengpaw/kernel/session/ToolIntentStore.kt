// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 意图日志的 **JSONL 存储引擎** — 从 [ToolIntentLog] 剥出的纯字节进出与目录细节。
 *
 * 拆分动机 (职责单一): [ToolIntentLog] 负责 WAL 语义 (幂等索引 / 恢复判重 / 周期压实策略),
 * 本类只负责"一行记录怎么落、半行怎么修、整文件怎么原子换掉"。二者变更理由完全不同 —
 * 换存储格式 (JSONL → SQLite) 不该动 WAL 策略, 调压实周期不该动文件操作。
 *
 * **落盘格式**: 每会话一个 JSONL (`{dir}/{安全化 sessionId}.jsonl`), 追加写单行 JSON; 终态不改写
 * 历史行, 而是再追加一条同 toolCallId 的记录, 读侧按 toolCallId 折叠 (后写覆盖先写)。
 *
 * **原子性与"新记录先可见、不产生半行"**: ① [append] 支持 `durable` — 恢复所需的 PENDING 记录在
 * 副作用发生前 `fsync`, 结论记录只 flush (丢失方向安全, 见 [ToolIntentLog.finish]); ② 追加前若文件
 * 末尾不是换行 (上次崩溃留下的半行) 先补一个换行, 保证新记录独占一行 — 半行只会被读侧跳过,
 * 绝不与下条记录粘连; ③ 记录内不可能出现裸换行 (JSON 转义保证, 另有防御性替换);
 * ④ [rewrite] 整文件重写走 tmp + `Files.move` 原子替换。
 *
 * **容错**: 全部文件 IO 一律 try/catch — 读侧遇损坏行跳过不抛, 写侧失败只记错误、不中断工具执行
 * (本存储是旁路观测设施, 其故障不得改变 Agent 行为)。
 *
 * **并发**: 单锁 ([ioLock]) 保护全部文件操作; `executeActions` 并行执行一批工具, 故 [append] 会被
 * 并发调用 — 保证线程安全, 且**不串行化工具执行本身** (锁只覆盖"一次小追加")。
 *
 * @param dir 落盘目录, 默认 `{BASE}/会话检查点/intents` (与检查点同目录树, 由上层决定生命周期)。
 */
internal class ToolIntentStore(private val dir: String = "${DataPaths.CHECKPOINTS}/intents") {

    /** 宽松解析 — 新增字段的旧记录 (或旧代码写的多余字段) 一律不致命。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 全局锁 — 保护全部文件操作 ([ToolIntentLog] 的内存索引也复用它, 保证读写与索引同锁)。 */
    val ioLock = Any()

    /**
     * 追加一行。`durable=false` 时只 flush 不 fsync — 调用方据此把"结论记录"排除在持久化开销外。
     * 失败返回 false, 绝不抛。
     */
    fun append(file: File, intent: ToolIntent, durable: Boolean = true): Boolean = try {
        file.parentFile?.mkdirs()
        val encoded = json.encodeToString(intent).replace('\n', ' ')
        FileOutputStream(file, true).use { out ->
            if (needsHealingNewline(file)) out.write(NEWLINE)
            out.write(encoded.toByteArray(Charsets.UTF_8))
            out.write(NEWLINE)
            out.flush()
            // durable=false (终态) 只 flush 不 fsync — 丢的只是"结论", 理由见 [ToolIntentLog.finish] KDoc
            if (!durable) return@use
            try {
                out.fd.sync()
            } catch (e: Exception) {
                // 已 flush 到操作系统, 仅物理落盘未确认 — 记录失败但不影响工具执行
                ErrorCollector.report(e, "ToolIntentStore.fsync")
            }
        }
        true
    } catch (e: Exception) {
        ErrorCollector.report(e, "ToolIntentStore.append")
        false
    }

    /**
     * 容错读取**并折叠** (整文件读失败返回 null = 未知, 单行解析失败跳过并计数)。绝不抛。
     * 折叠 = 同一 toolCallId 只留最后一条 (后写覆盖先写) 并保持首次出现位置: JSONL 是追加写, PENDING
     * 与终态会各占一行, 折叠后读侧只面对"每个 toolCallId 的最终态", 与旧格式的读侧语义逐字一致。
     */
    fun read(file: File): List<ToolIntent>? {
        val lines = try {
            if (!file.isFile) return emptyList()
            file.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentStore.read")
            return null
        }
        val folded = LinkedHashMap<String, ToolIntent>(lines.size)
        var skipped = 0
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            try {
                val item = json.decodeFromString<ToolIntent>(t)
                folded[item.toolCallId] = item
            } catch (_: Exception) {
                skipped++
            }
        }
        if (skipped > 0) {
            KernelLog.w(TAG, "跳过 $skipped 条损坏记录 (文件 ${file.name})")
        }
        return folded.values.toList()
    }

    /** 原子整文件重写 (tmp + REPLACE_EXISTING), 供 [ToolIntentLog.pruneOlderThan] 压实使用。 */
    fun rewrite(file: File, records: List<ToolIntent>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            val body = records.joinToString("") { json.encodeToString(it).replace('\n', ' ') + "\n" }
            FileOutputStream(tmp).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (tmp.exists()) {
                try { tmp.delete() } catch (_: Exception) { /* 残留 tmp 不影响读取 */ }
            }
        }
    }

    /** 删除某会话的日志与其残留 tmp (成功收尾后调用)。失败只记错误。 */
    fun delete(file: File) {
        try {
            if (file.exists()) file.delete()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentStore.delete")
        }
    }

    /** 目录内全部会话日志 (按后缀过滤; 目录不可读时返回空 — 压实是尽力而为, 不该让工具执行失败)。 */
    fun listFiles(): List<File> = try {
        File(dir).listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()
    } catch (e: Exception) {
        ErrorCollector.report(e, "ToolIntentStore.list")
        emptyList()
    }

    /** 会话/文件 → 落盘路径 (同一会话恒定, 与 [ToolIntentLog.fileNameFor] 同源)。 */
    fun fileFor(sessionId: String): File = File(dir, ToolIntentLog.fileNameFor(sessionId))

    /** 文件末尾不是换行 (上次崩溃留半行) → 需要先补一个换行, 让新记录独占一行。 */
    private fun needsHealingNewline(f: File): Boolean = try {
        val len = f.length()
        if (len == 0L) false
        else RandomAccessFile(f, "r").use { raf ->
            raf.seek(len - 1)
            raf.read() != NEWLINE
        }
    } catch (_: Exception) {
        // 读不出结尾 → 保守补一个换行 (多余空行会被读侧跳过, 代价远小于两行粘连)
        true
    }

    internal companion object {
        const val TAG = "ToolIntentStore"

        /** 文件后缀 — 每会话一个 JSONL (与公开契约 [ToolIntentLog.SUFFIX] 同值, 有测试锁死一致性)。 */
        const val SUFFIX = ".jsonl"
    }
}

/** 换行符字节值 (`'\n'.code`) — JSONL 的行分隔符。 */
private const val NEWLINE = 10
