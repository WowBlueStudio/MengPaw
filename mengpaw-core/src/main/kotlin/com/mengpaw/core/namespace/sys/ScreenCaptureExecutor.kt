// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.media.ImageReader
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

/** Screenshot, screen recording, and camera photo capture. */
internal object ScreenCaptureExecutor {

    private var screenRecordProcess: Process? = null

    suspend fun screenshot(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val pathArg = args.filter { !it.startsWith("--") }.firstOrNull()
        val outputPath = if (pathArg != null) {
            File(pathArg).absolutePath
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/screenshot_${timestamp}.png"
        }
        File(outputPath).parentFile?.mkdirs()
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outputPath))
            proc.waitFor()
            if (proc.exitValue() == 0) {
                val size = File(outputPath).length()
                ExecutionResult.ok("截图已保存: $outputPath (${size} bytes)")
            } else {
                val err = proc.errorStream.bufferedReader().readText().trim()
                ExecutionResult.fail("截图失败${if (err.isNotEmpty()) ": $err" else " — screencap 不可用（可能需要 root 或 ADB 权限）"}")
            }
        } catch (e: Exception) {
            ExecutionResult.fail("截图失败: ${e.message}。screencap 需要 root 或 shell 权限。备选: 使用 browser.screenshot 截取浏览器内容。")
        }
    }

    suspend fun screenRecordStart(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (screenRecordProcess != null && screenRecordProcess!!.isAlive) {
            return ExecutionResult.fail("录屏已在运行。先执行 sys.screenrecord.stop 停止。")
        }
        val pathArg = args.firstOrNull()
        val outputPath = pathArg ?: run {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/record_${timestamp}.mp4"
        }
        File(outputPath).parentFile?.mkdirs()
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("screenrecord", "--time-limit", "180", outputPath))
            screenRecordProcess = proc
            ExecutionResult.ok("录屏已开始 (最长 3 分钟)\n输出: $outputPath\n停止: sys.screenrecord.stop")
        } catch (e: Exception) {
            ExecutionResult.fail("录屏启动失败: ${e.message}。screenrecord 需要 root 或 shell 权限。")
        }
    }

    suspend fun screenRecordStop(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val proc = screenRecordProcess
        if (proc == null || !proc.isAlive) {
            screenRecordProcess = null
            return ExecutionResult.fail("没有正在运行的录屏")
        }
        return try {
            proc.destroy()
            Thread.sleep(1500)
            if (proc.isAlive) proc.destroyForcibly()
            screenRecordProcess = null
            ExecutionResult.ok("录屏已停止。文件在 ${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/ 目录下。")
        } catch (e: Exception) {
            try { proc.destroyForcibly() } catch (_: Exception) {}
            screenRecordProcess = null
            ExecutionResult.fail("停止录屏异常: ${e.message}")
        }
    }

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

            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = if (useFront) {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            } ?: cm.cameraIdList.firstOrNull()
                ?: return ExecutionResult.fail("未找到可用摄像头")

            val latch = CountDownLatch(1)
            var captureError: String? = null
            var capturedPath: String? = null

            val handler = Handler(Looper.getMainLooper())
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    try {
                        val reader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
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
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    session.capture(req.build(), null, null)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    captureError = "Camera session config failed"
                                    latch.countDown()
                                }
                            }, handler)
                    } catch (e: Exception) {
                        captureError = e.message
                        latch.countDown()
                    }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); latch.countDown() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); captureError = "Camera error: $e"; latch.countDown() }
            }, handler)

            latch.await(10, TimeUnit.SECONDS)

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
