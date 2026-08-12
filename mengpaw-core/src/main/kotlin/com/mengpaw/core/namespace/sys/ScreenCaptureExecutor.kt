// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screenshot, screen recording, and camera photo capture.
 *
 * 截图/录屏 (P1 修复, 9d 审查): 原实现依赖 `screencap`/`screenrecord` shell 二进制,
 * 普通 App 进程 (非 root/ADB) 必失败。现改为双路径:
 * - 截图: 先尝试 screencap 快路径 (root 设备秒出, 无需弹窗); 失败 → MediaProjection
 *   (系统授权弹窗 → VirtualDisplay + ImageReader → PNG), Android 14+ 经
 *   [MediaProjectionService] 前台服务令牌创建 VirtualDisplay。
 * - 录屏: MediaProjection + MediaRecorder (H264/MP4), 无音频 (可后续加 --audio)。
 */
internal object ScreenCaptureExecutor {

    private const val PROJECTION_TIMEOUT_MS = 30_000L
    private const val CAPTURE_FRAME_TIMEOUT_MS = 5_000L

    // ── 录屏状态 (MediaProjection 版本, 无 shell 进程) ──
    private val recordLock = Any()
    private var recordRecorder: MediaRecorder? = null
    private var recordProjection: MediaProjection? = null
    private var recordVirtualDisplay: VirtualDisplay? = null
    private var recordOutputPath: String? = null

    private fun currentRecorder(): MediaRecorder? = synchronized(recordLock) { recordRecorder }

    private fun setRecording(
        recorder: MediaRecorder?, projection: MediaProjection?, display: VirtualDisplay?, path: String?
    ) {
        synchronized(recordLock) {
            recordRecorder = recorder
            recordProjection = projection
            recordVirtualDisplay = display
            recordOutputPath = path
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 截图
    // ═══════════════════════════════════════════════════════════════

    suspend fun screenshot(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pathArg = args.filter { !it.startsWith("--") }.firstOrNull()
        val outputPath = if (pathArg != null) {
            File(pathArg).absolutePath
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/screenshot_${timestamp}.png"
        }
        File(outputPath).parentFile?.mkdirs()

        // 快路径: root/ADB 设备 screencap 秒出, 免授权弹窗
        val shellResult = try {
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outputPath))
            proc.waitFor()
            if (proc.exitValue() == 0 && File(outputPath).length() > 0) {
                ExecutionResult.ok("截图已保存: $outputPath (${File(outputPath).length()} bytes)")
            } else null
        } catch (_: Exception) {
            null
        }
        if (shellResult != null) return shellResult

        // MediaProjection 路径: 免 root, 系统授权弹窗
        return captureWithProjection(outputPath)
    }

    /** MediaProjection 截图 — 授权 → VirtualDisplay → ImageReader 取帧 → PNG. */
    private suspend fun captureWithProjection(outputPath: String): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return ExecutionResult.fail("MediaProjection 服务不可用")
        val auth = SysExecutor.requestProjection(mpm.createScreenCaptureIntent(), PROJECTION_TIMEOUT_MS)
            ?: return ExecutionResult.fail("屏幕捕获授权超时或已取消。重试: sys.screenshot")
        val resultCode = auth.first
        val projectionData = auth.second
        if (resultCode != android.app.Activity.RESULT_OK || projectionData == null) {
            return ExecutionResult.fail("用户拒绝了屏幕捕获授权。重试: sys.screenshot")
        }

        MediaProjectionService.start(app)
        var projection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        return try {
            // Android 14+: getMediaProjection 必须在 mediaProjection 前台服务上下文
            projection = mpm.getMediaProjection(resultCode, projectionData)
            val metrics = app.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var captureError: String? = null
            val handler = Handler(Looper.getMainLooper())

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        bitmap = if (rowPadding == 0) bmp
                        else Bitmap.createBitmap(bmp, 0, 0, width, height)
                    } catch (e: Exception) {
                        captureError = e.message
                    } finally {
                        image.close()
                    }
                }
                latch.countDown()
            }, handler)

            virtualDisplay = projection!!.createVirtualDisplay(
                "mengpaw_capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
            )
            latch.await(CAPTURE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            if (bitmap != null) {
                File(outputPath).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ExecutionResult.ok("截图已保存: $outputPath (${File(outputPath).length()} bytes)")
            } else {
                ExecutionResult.fail("截图失败: ${captureError ?: "未捕获到画面 (超时)"}")
            }
        } catch (e: SecurityException) {
            ExecutionResult.fail("屏幕捕获被系统拒绝: ${e.message} (Android 14+ 需 mediaProjection 前台服务)")
        } catch (e: Exception) {
            ExecutionResult.fail("截图失败: ${e.message}")
        } finally {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            MediaProjectionService.stop(app)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 录屏
    // ═══════════════════════════════════════════════════════════════

    suspend fun screenRecordStart(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (currentRecorder() != null) {
            return ExecutionResult.fail("录屏已在运行。先执行 sys.screenrecord.stop 停止。")
        }
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pathArg = args.firstOrNull()
        val outputPath = pathArg ?: run {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/record_${timestamp}.mp4"
        }
        File(outputPath).parentFile?.mkdirs()

        val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return ExecutionResult.fail("MediaProjection 服务不可用")
        val auth = SysExecutor.requestProjection(mpm.createScreenCaptureIntent(), PROJECTION_TIMEOUT_MS)
            ?: return ExecutionResult.fail("屏幕捕获授权超时或已取消。重试: sys.screenrecord.start")
        val resultCode = auth.first
        val projectionData = auth.second
        if (resultCode != android.app.Activity.RESULT_OK || projectionData == null) {
            return ExecutionResult.fail("用户拒绝了屏幕捕获授权。重试: sys.screenrecord.start")
        }

        MediaProjectionService.start(app)
        var projection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var recorder: MediaRecorder? = null
        return try {
            projection = mpm.getMediaProjection(resultCode, projectionData)
            val metrics = app.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                @Suppress("DEPRECATION")
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000)
                setOutputFile(outputPath)
                prepare()
            }
            virtualDisplay = projection!!.createVirtualDisplay(
                "mengpaw_record", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder!!.surface, null, null
            )
            recorder!!.start()
            setRecording(recorder, projection, virtualDisplay, outputPath)
            ExecutionResult.ok(buildString {
                appendLine("录屏已开始 (MediaProjection)")
                appendLine("输出: $outputPath")
                appendLine("停止: sys.screenrecord.stop")
            })
        } catch (e: SecurityException) {
            try { recorder?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            MediaProjectionService.stop(app)
            ExecutionResult.fail("屏幕捕获被系统拒绝: ${e.message} (Android 14+ 需 mediaProjection 前台服务)")
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            MediaProjectionService.stop(app)
            ExecutionResult.fail("录屏启动失败: ${e.message}")
        }
    }

    suspend fun screenRecordStop(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
        val recorder = currentRecorder()
        if (recorder == null) {
            return ExecutionResult.fail("没有正在运行的录屏")
        }
        return try {
            recorder.stop()
            recorder.release()
            synchronized(recordLock) {
                try { recordVirtualDisplay?.release() } catch (_: Exception) {}
                try { recordProjection?.stop() } catch (_: Exception) {}
                val path = recordOutputPath
                setRecording(null, null, null, null)
                ExecutionResult.ok("录屏已停止。文件: $path")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消契约: 必须先 rethrow
            try { recorder.release() } catch (_: Exception) {}
            synchronized(recordLock) {
                try { recordVirtualDisplay?.release() } catch (_: Exception) {}
                try { recordProjection?.stop() } catch (_: Exception) {}
                setRecording(null, null, null, null)
            }
            throw e
        } catch (e: Exception) {
            try { recorder.release() } catch (_: Exception) {}
            synchronized(recordLock) {
                try { recordVirtualDisplay?.release() } catch (_: Exception) {}
                try { recordProjection?.stop() } catch (_: Exception) {}
                setRecording(null, null, null, null)
            }
            ExecutionResult.fail("停止录屏异常: ${e.message}")
        } finally {
            app?.let { MediaProjectionService.stop(it) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 拍照 (Camera2, 原有实现不变)
    // ═══════════════════════════════════════════════════════════════

    suspend fun cameraPhoto(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.CAMERA)) {
            return ExecutionResult.fail("需要 CAMERA 权限。使用 sys.permission.request CAMERA 申请。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        if (!args.contains("--confirm")) {
            return ExecutionResult.fail(buildString {
                appendLine("📸 即将使用摄像头拍照")
                appendLine()
                appendLine("⚠️ 摄像头涉及隐私。请先告知用户并获取确认，然后执行:")
                appendLine("  sys.camera.photo --confirm")
                appendLine()
                appendLine("可选参数: sys.camera.photo --confirm --front (使用前置摄像头)")
                appendLine("          sys.camera.photo --confirm <输出路径>")
            })
        }
        return try {
            val pathArg = args.filter { !it.startsWith("--") }.firstOrNull()
            val outputPath = pathArg ?: run {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/photo_${timestamp}.jpg"
            }
            val useFront = args.contains("--front")
            File(outputPath).parentFile?.mkdirs()

            val cm = app.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = if (useFront) {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }
            } ?: cm.cameraIdList.firstOrNull()
                ?: return ExecutionResult.fail("未找到可用摄像头")

            val photoSize = try {
                cm.getCameraCharacteristics(cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height }
            } catch (e: Exception) { null }
                ?: android.util.Size(1920, 1080)

            val latch = CountDownLatch(1)
            var captureError: String? = null
            var capturedPath: String? = null
            var openedDevice: android.hardware.camera2.CameraDevice? = null
            var imageReader: ImageReader? = null
            var captureSession: android.hardware.camera2.CameraCaptureSession? = null

            fun releaseCamera() {
                try { captureSession?.close() } catch (_: Exception) { }
                captureSession = null
                try { imageReader?.close() } catch (_: Exception) { }
                imageReader = null
                try { openedDevice?.close() } catch (_: Exception) { }
                openedDevice = null
            }

            val handler = Handler(Looper.getMainLooper())
            cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    openedDevice = device
                    try {
                        val reader = ImageReader.newInstance(
                            photoSize.width, photoSize.height, android.graphics.ImageFormat.JPEG, 1)
                        imageReader = reader
                        reader.setOnImageAvailableListener({ r ->
                            val image = r.acquireLatestImage()
                            if (image != null) {
                                try {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    File(outputPath).writeBytes(bytes)
                                    capturedPath = outputPath
                                } finally {
                                    image.close()
                                }
                            }
                            latch.countDown()
                        }, handler)

                        val surfaces = listOf(reader.surface)
                        device.createCaptureSession(surfaces,
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    captureSession = session
                                    val req = device.createCaptureRequest(
                                        android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    session.capture(req.build(), null, null)
                                }
                                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                                    captureError = "Camera session config failed"
                                    latch.countDown()
                                }
                            }, handler)
                    } catch (e: Exception) {
                        captureError = e.message
                        latch.countDown()
                    }
                }
                override fun onDisconnected(d: android.hardware.camera2.CameraDevice) {
                    openedDevice = null
                    try { d.close() } catch (_: Exception) { }
                    latch.countDown()
                }
                override fun onError(d: android.hardware.camera2.CameraDevice, e: Int) {
                    openedDevice = null
                    try { d.close() } catch (_: Exception) { }
                    captureError = "Camera error: $e"
                    latch.countDown()
                }
            }, handler)

            latch.await(10, TimeUnit.SECONDS)
            releaseCamera()

            if (capturedPath != null) {
                val size = File(outputPath).length()
                ExecutionResult.ok("照片已保存: $outputPath (${size} bytes)")
            } else {
                ExecutionResult.fail("拍照失败: ${captureError ?: "超时"}")
            }
        } catch (e: Exception) {
            ExecutionResult.fail("拍照失败: ${e.message}")
        }
    }
}
