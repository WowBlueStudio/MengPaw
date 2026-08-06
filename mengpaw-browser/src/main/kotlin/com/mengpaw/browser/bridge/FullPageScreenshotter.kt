// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.WebView
import com.mengpaw.kernel.DataPaths
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Quick Click 全页缝合截图 + 坐标交互（自 BrowserBridge 拆出 — 400 行文件拆分批次 2）。
 *
 * - [capture]: 视口逐段滚动截图拼接为一张高图 (View.draw, API 26+ 兼容; capturePicture
 *   已在 API 33+ 移除)。超 MAX_SCREENSHOT_PIXELS 等比缩小绘制防 OOM。
 * - [tap]/[scrollToY]: 按 [lastScreenshotScale] 把 Agent 的截图坐标还原为页面坐标
 *   (截图超限缩小后坐标空间同步缩放 — 缩放状态归本类持有, coord 命令与 capture 一致)。
 */
internal class FullPageScreenshotter(
    private val webView: WebView,
    private val onScreenshot: ((Bitmap) -> String)?,
    private val unquoteJs: (String) -> String,
    private val viewportFallback: () -> String
) {
    /** Max segments for full-page screenshot (prevent OOM). ~20 viewports. */
    private val MAX_SEGMENTS = 30

    /** P2 fix: 最近一次全页截图的缩放比 — tap/scrollToY 按此还原为页面坐标。 */
    private var lastScreenshotScale = 1f

    /**
     * Capture a stitched full-page screenshot.
     * Scrolls the page viewport-by-viewport, captures each segment via [WebView.draw],
     * and stitches them into ONE tall bitmap saved to DataPaths.SCREENSHOTS.
     *
     * Returns JSON with the file path, total width, total height, and segment count.
     * The Agent can then use [tap] to tap at absolute coordinates within this image.
     *
     * EXPERIMENTAL: enabled by default (BrowserPrefs.quickClickEnabled).
     */
    fun capture(maxHeight: Int = 15000): String {
        return try {
            // Get page dimensions via JS with a render-complete latch
            val dimsLatch = CountDownLatch(1)
            var dimsJson = ""
            webView.post {
                webView.evaluateJavascript(
                    "(function(){return JSON.stringify({w:document.documentElement.scrollWidth||document.body.scrollWidth||${webView.width},h:Math.min(document.documentElement.scrollHeight||document.body.scrollHeight||${webView.height},$maxHeight)})})()"
                ) { r -> dimsJson = unquoteJs(r); dimsLatch.countDown() }
            }
            dimsLatch.await(3, TimeUnit.SECONDS)

            val dims = org.json.JSONObject(dimsJson.ifBlank { """{"w":${webView.width},"h":${webView.height}}""" })
            val pageH = dims.optInt("h", webView.height).coerceAtMost(maxHeight).coerceAtLeast(1)
            // P0 fix: 捕获宽度以 WebView 实际宽度为准 — capturePicture 移除后 draw 输出即视口
            val rawW = webView.width.coerceAtLeast(1)
            val rawH = webView.height.coerceAtLeast(1)
            // P2 fix: 位图内存上限防 OOM — 缝合图总像素超 MAX_SCREENSHOT_PIXELS (32MB) 时
            // 整体等比缩小绘制 (canvas.scale), 返回 JSON 尺寸即实际位图尺寸, 坐标空间同步缩放;
            // tap/scrollToY 经 lastScreenshotScale 还原为页面坐标。
            val rawSegCount = minOf((pageH + rawH - 1) / rawH, MAX_SEGMENTS)
            val rawStitchedH = minOf(pageH.toLong(), rawSegCount.toLong() * rawH)
            val scale = if (rawW.toLong() * rawStitchedH > MAX_SCREENSHOT_PIXELS)
                kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (rawW.toLong() * rawStitchedH)).toFloat().coerceIn(0.2f, 1f)
            else 1f
            lastScreenshotScale = scale
            val drawW = (rawW * scale).toInt().coerceAtLeast(1)
            val drawH = (rawH * scale).toInt().coerceAtLeast(1)
            val vpHeight = drawH
            val scaledPageH = (pageH * scale).toInt().coerceAtLeast(1)
            val segmentCount = minOf((scaledPageH + vpHeight - 1) / vpHeight, MAX_SEGMENTS)
            val stitchedH = minOf(scaledPageH, segmentCount * vpHeight)
            // 极限兜底: 缩放下限 0.2 仍超内存上限的极端宽视口 — 直接报错引导用视口截图
            if (drawW.toLong() * stitchedH > MAX_SCREENSHOT_PIXELS) {
                throw IllegalStateException("页面尺寸过大 ($drawW×$stitchedH px) 超位图 32MB 上限，请改用 browser.screenshot (视口截图)")
            }

            val segments = mutableListOf<Bitmap>()
            var pageY = 0  // 页面坐标 (未缩放) — scrollTo 用页面像素

            for (i in 0 until segmentCount) {
                // Scroll + wait for render via post queue
                val segLatch = CountDownLatch(1)
                webView.post {
                    webView.scrollTo(0, pageY)
                    // Double-post ensures scroll happened before capture
                    webView.post {
                        segLatch.countDown()
                    }
                }
                segLatch.await(500, TimeUnit.MILLISECONDS)

                // P0 fix: capturePicture() 已在 API 33+ 移除 — 抛 NoSuchMethodError (Error 而非
                // Exception, 逃过下方 catch 必崩)。改为主线程 View.draw — 滚动对齐后画当前视口段
                // (语义等价: 每段 = 视口截图, 拼接为全页)。
                val segBitmap = Bitmap.createBitmap(drawW, minOf(drawH, scaledPageH - i * vpHeight), Bitmap.Config.ARGB_8888)
                val drawLatch = CountDownLatch(1)
                webView.post {
                    val c = android.graphics.Canvas(segBitmap)
                    if (scale < 1f) c.scale(scale, scale)  // P2 fix: 超限时等比缩小绘制
                    webView.draw(c)
                    drawLatch.countDown()
                }
                drawLatch.await(500, TimeUnit.MILLISECONDS)
                segments.add(segBitmap)
                pageY += rawH
                if (pageY >= pageH) break
            }

            // Stitch vertically
            val stitched = Bitmap.createBitmap(drawW, stitchedH, Bitmap.Config.ARGB_8888)
            val stitchCanvas = android.graphics.Canvas(stitched)
            var offsetY = 0
            for (seg in segments) { stitchCanvas.drawBitmap(seg, 0f, offsetY.toFloat(), null); offsetY += seg.height; seg.recycle() }

            // Atomic write: tmp → rename
            val dir = File(DataPaths.SCREENSHOTS)
            dir.mkdirs()
            val tmpFile = File(dir, "full_${System.currentTimeMillis()}.tmp")
            val finalFile = File(dir, "full_${System.currentTimeMillis()}.png")
            FileOutputStream(tmpFile).use { stitched.compress(Bitmap.CompressFormat.PNG, 85, it) }
            val fileSize = tmpFile.length()
            tmpFile.renameTo(finalFile)
            stitched.recycle()

            // Scroll back to top
            webView.post { webView.scrollTo(0, 0) }

            """{"ok":true,"path":"${finalFile.absolutePath}","width":$drawW,"totalHeight":$stitchedH,"segments":$segmentCount,"fileSize":$fileSize,"scale":$scale}"""
        } catch (e: Exception) {
            // Auto-fallback: try viewport screenshot
            return try {
                val fallback = viewportFallback()
                """{"ok":true,"fallback":true,"note":"Full-page failed (${e.message?.take(80)}), captured viewport instead","viewport":$fallback}"""
            } catch (_: Exception) {
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}","hint":"Try browser.screenshot for viewport capture"}"""
            }
        }
    }

    /**
     * Tap at absolute coordinates relative to the FULL page (not viewport).
     * Uses [android.view.MotionEvent] dispatch for real touch simulation.
     *
     * Workflow: (1) browser.screenshot.full → { path, w, totalHeight }
     *           (2) Agent/Vision sees the image, picks coordinates
     *           (3) browser.coord.click x y → scrolls to y, taps at x
     */
    fun tap(x: Int, y: Int): String {
        return try {
            // P2 fix: 截图超限等比缩小后, Agent 坐标在缩放空间 — 按 lastScreenshotScale 还原为页面坐标
            val s = lastScreenshotScale.coerceAtLeast(0.1f)
            val maxY = (webView.contentHeight - webView.height).coerceAtLeast(0)
            val targetY = (y.toFloat() / s).toInt().coerceAtLeast(0).coerceAtMost(webView.contentHeight)
            val vpX = (x.toFloat() / s).toInt().coerceAtLeast(0).coerceAtMost(webView.width)

            // Scroll to position and wait for render
            val scrollLatch = CountDownLatch(1)
            webView.post {
                webView.scrollTo(0, minOf(targetY, maxY))
                webView.post { scrollLatch.countDown() }
            }
            scrollLatch.await(300, TimeUnit.MILLISECONDS)

            val localY = (targetY - webView.scrollY).coerceIn(0, webView.height)
            webView.post {
                val downTime = android.os.SystemClock.uptimeMillis()
                val downEvent = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, vpX.toFloat(), localY.toFloat(), 0)
                val upEvent = android.view.MotionEvent.obtain(downTime, downTime + 80, android.view.MotionEvent.ACTION_UP, vpX.toFloat(), localY.toFloat(), 0)
                webView.dispatchTouchEvent(downEvent)
                webView.dispatchTouchEvent(upEvent)
                downEvent.recycle()
                upEvent.recycle()
            }
            """{"ok":true,"x":$vpX,"pageY":$targetY,"localY":$localY,"scrollY":${webView.scrollY}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}","hint":"Use browser.coord.scroll <y> to verify position first"}"""
        }
    }

    /**
     * Scroll to a specific y-coordinate in the full page.
     * Useful for verifying position before clicking.
     */
    fun scrollToY(y: Int): String {
        return try {
            // P2 fix: 与 tap 一致 — 缩放坐标还原为页面坐标
            val s = lastScreenshotScale.coerceAtLeast(0.1f)
            val maxY = (webView.contentHeight - webView.height).coerceAtLeast(0)
            val targetY = (y.toFloat() / s).toInt().coerceIn(0, maxY)
            val latch = CountDownLatch(1)
            webView.post { webView.scrollTo(0, targetY); webView.post { latch.countDown() } }
            latch.await(200, TimeUnit.MILLISECONDS)
            """{"ok":true,"scrollY":${webView.scrollY},"contentHeight":${webView.contentHeight},"maxScrollY":$maxY}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }
}
