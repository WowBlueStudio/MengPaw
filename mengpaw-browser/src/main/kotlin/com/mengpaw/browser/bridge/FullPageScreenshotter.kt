// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.WebView
import com.mengpaw.browser.util.BrowserStorage
import com.mengpaw.kernel.DataPaths
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 分段全页截图 + 坐标交互（半自动武器方案 Phase 1 重构，自 BrowserBridge 拆出）。
 *
 * - [captureSegments]: 超长页截断分多段发送（决策 #5），每段 ≈ 视口高独立落盘，
 *   段数上限 [MAX_SEGMENTS]，超出截断并标注 `partial:true`。
 * - [tapSegment]/[scrollToSegmentY]: 按段号 + 段内截图坐标还原页面坐标
 *   (截图超限缩小后坐标空间同步缩放 — 缩放状态归本类持有, page.click 与截图一致)。
 * - 截图落盘公共目录 (BrowserStorage.screenshotsDir, 决策 #2)，拒绝授权回退私有并提示。
 * - 经验继承 (原缝合 capture 已删): capturePicture() 在 API 33+ 移除 (NoSuchMethodError
 *   逃过 catch 必崩) → 主线程 View.draw；位图超 MAX_SCREENSHOT_PIXELS 等比缩小防 OOM。
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

    /** 最近一次分段截图的未缩放视口宽/高 — tapSegment 还原页面坐标用（决策 #5）。 */
    private var lastRawViewportW = 0
    private var lastRawViewportH = 0
    private var lastSegmentCount = 1

    /**
     * 分段全页截图（半自动武器方案决策 #5）— 超长页截断分多段发送，坐标按段拆分。
     *
     * 与 [capture] 缝合单张不同：每段 ≈ 视口高独立落盘，段数上限 [MAX_SEGMENTS]，
     * 超出截断并标注 `partial:true`。每段返回独立坐标系统，Agent 用
     * `page.click <seg> <x> <y>` 按段内坐标操作（经 [tapSegment] 还原页面坐标）。
     *
     * 返回 JSON:
     * {"ok":true,"segmentCount":N,"partial":false,"maxHeight":H,
     *  "segments":[{"seg":1,"path":"...","width":W,"height":H,"pageYStart":0,"scale":1.0},...]}
     */
    fun captureSegments(maxHeight: Int = 15000): String {
        return try {
            val dimsLatch = CountDownLatch(1)
            var dimsJson = ""
            webView.post {
                webView.evaluateJavascript(
                    "(function(){return JSON.stringify({w:document.documentElement.scrollWidth||document.body.scrollWidth||${webView.width},h:document.documentElement.scrollHeight||document.body.scrollHeight||${webView.height}})})()"
                ) { r -> dimsJson = unquoteJs(r); dimsLatch.countDown() }
            }
            dimsLatch.await(3, TimeUnit.SECONDS)

            val dims = org.json.JSONObject(dimsJson.ifBlank { """{"w":${webView.width},"h":${webView.height}}""" })
            val pageH = dims.optInt("h", webView.height).coerceAtLeast(1)
            val rawW = webView.width.coerceAtLeast(1)
            val rawH = webView.height.coerceAtLeast(1)
            // 超长页截断: max-height 上限 + 30 段上限, 超出标注 partial (决策 #5)
            val cappedH = minOf(pageH, maxHeight.coerceAtLeast(rawH))
            val totalSegments = minOf((cappedH + rawH - 1) / rawH, MAX_SEGMENTS)
            val capturedH = minOf(cappedH.toLong(), totalSegments.toLong() * rawH).toInt()
            val partial = pageH > capturedH

            // 段内缩放: 超宽视口 (单段像素超限) 时等比缩小绘制
            val segScale = if (rawW.toLong() * rawH > MAX_SCREENSHOT_PIXELS)
                kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (rawW.toLong() * rawH)).toFloat().coerceIn(0.2f, 1f)
            else 1f
            val drawW = (rawW * segScale).toInt().coerceAtLeast(1)
            val drawH = (rawH * segScale).toInt().coerceAtLeast(1)

            lastScreenshotScale = segScale
            lastRawViewportW = rawW
            lastRawViewportH = rawH
            lastSegmentCount = totalSegments.coerceAtLeast(1)

            val dir = File(BrowserStorage.screenshotsDir())
            dir.mkdirs()
            val ts = System.currentTimeMillis()
            val segments = mutableListOf<String>()
            for (i in 0 until totalSegments) {
                val pageYStart = i * rawH
                val segPageH = minOf(rawH, capturedH - pageYStart)
                val segDrawH = (segPageH * segScale).toInt().coerceAtLeast(1)
                val segLatch = CountDownLatch(1)
                webView.post {
                    webView.scrollTo(0, pageYStart)
                    webView.post { segLatch.countDown() }
                }
                segLatch.await(500, TimeUnit.MILLISECONDS)

                val segBitmap = Bitmap.createBitmap(drawW, segDrawH, Bitmap.Config.ARGB_8888)
                val drawLatch = CountDownLatch(1)
                webView.post {
                    val c = android.graphics.Canvas(segBitmap)
                    if (segScale < 1f) c.scale(segScale, segScale)
                    webView.draw(c)
                    drawLatch.countDown()
                }
                drawLatch.await(500, TimeUnit.MILLISECONDS)
                val segFile = File(dir, "page_${ts}_seg${i + 1}.png")
                FileOutputStream(segFile).use { segBitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                segBitmap.recycle()
                segments.add(
                    """{"seg":${i + 1},"path":"${segFile.absolutePath}","width":$drawW,"height":$segDrawH,"pageYStart":$pageYStart,"scale":$segScale}"""
                )
            }
            webView.post { webView.scrollTo(0, 0) }

            """{"ok":true,"segmentCount":$totalSegments,"partial":$partial,"maxHeight":${maxHeight.coerceAtLeast(rawH)},"segments":[${segments.joinToString(",")}]}"""
        } catch (e: Exception) {
            // 自动降级: 分段失败 → 视口截图兜底 (单段)
            return try {
                val fb = org.json.JSONObject(viewportFallback())
                val path = fb.optString("path")
                val w = fb.optInt("width")
                val h = fb.optInt("height")
                """{"ok":true,"segmentCount":1,"partial":true,"fallback":true,"note":"Segment capture failed (${e.message?.take(80)}), captured viewport instead","segments":[{"seg":1,"path":"$path","width":$w,"height":$h,"pageYStart":0,"scale":1.0}]}"""
            } catch (_: Exception) {
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}","hint":"Try page.screenshot --view for viewport capture"}"""
            }
        }
    }

    /**
     * 按段坐标点击（决策 #5）— 段号 + 段内截图坐标还原为页面坐标后派发触摸事件。
     * 页面坐标 = (seg-1)*未缩放视口高 + 段内 y/缩放比；x 按缩放比还原。
     */
    fun tapSegment(seg: Int, x: Int, y: Int): String {
        return try {
            val s = lastScreenshotScale.coerceAtLeast(0.1f)
            val rawH = lastRawViewportH.coerceAtLeast(webView.height)
            val segIndex = seg.coerceIn(1, lastSegmentCount.coerceAtLeast(1)) - 1
            val maxY = (webView.contentHeight - webView.height).coerceAtLeast(0)
            val targetY = (segIndex * rawH + (y.toFloat() / s).toInt())
                .coerceIn(0, webView.contentHeight)
            val vpX = (x.toFloat() / s).toInt().coerceIn(0, webView.width)

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
            """{"ok":true,"seg":${segIndex + 1},"x":$vpX,"pageY":$targetY,"localY":$localY,"scrollY":${webView.scrollY}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}","hint":"Use page.screenshot --full first to refresh coordinates"}"""
        }
    }

    /** 按段坐标滚动（决策 #5）— 用于点击前核对位置。 */
    fun scrollToSegmentY(seg: Int, y: Int): String {
        return try {
            val s = lastScreenshotScale.coerceAtLeast(0.1f)
            val rawH = lastRawViewportH.coerceAtLeast(webView.height)
            val segIndex = seg.coerceIn(1, lastSegmentCount.coerceAtLeast(1)) - 1
            val maxY = (webView.contentHeight - webView.height).coerceAtLeast(0)
            val targetY = (segIndex * rawH + (y.toFloat() / s).toInt()).coerceIn(0, maxY)
            val latch = CountDownLatch(1)
            webView.post { webView.scrollTo(0, targetY); webView.post { latch.countDown() } }
            latch.await(200, TimeUnit.MILLISECONDS)
            """{"ok":true,"seg":${segIndex + 1},"scrollY":${webView.scrollY},"contentHeight":${webView.contentHeight},"maxScrollY":$maxY}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

}
