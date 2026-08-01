// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/** Camera info, volume control, and ringtone playback. */
internal object MediaExecutor {

    suspend fun camera(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.CAMERA)) {
            return ExecutionResult.fail("需要 CAMERA 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val info = ids.joinToString("\n") { id ->
                val chars = cm.getCameraCharacteristics(id)
                val facing = when (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    else -> "External"
                }
                val flash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                "  $id: $facing${if (flash) " + Flash" else ""}"
            }
            ExecutionResult.ok("Cameras (${ids.size}):\n$info")
        } catch (e: Exception) {
            ExecutionResult.fail("Camera error: ${e.message}")
        }
    }

    suspend fun volume(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ExecutionResult.fail("AudioManager unavailable")
        return ExecutionResult.ok(buildString {
            appendLine("Media: ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            appendLine("Ring: ${am.getStreamVolume(AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(AudioManager.STREAM_RING)}")
            appendLine("Alarm: ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
            appendLine("Voice: ${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
            appendLine("Ringer Mode: ${when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "Normal"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                else -> "Unknown"
            }}")
        })
    }

    suspend fun volumeSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (args.size < 2) return ExecutionResult.fail("Usage: sys.volume.set <media|ring|alarm|voice> <0-15>")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ExecutionResult.fail("AudioManager unavailable")
        val stream = when (args[0].lowercase()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "voice", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> return ExecutionResult.fail("Unknown stream: ${args[0]}. Valid: media, ring, alarm, voice")
        }
        val level = args[1].toIntOrNull() ?: return ExecutionResult.fail("Volume must be a number")
        am.setStreamVolume(stream, level.coerceIn(0, am.getStreamMaxVolume(stream)), 0)
        return ExecutionResult.ok("${args[0]} volume set to $level")
    }

    suspend fun ringtonePlay(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        return try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(app, uri)
            ringtone.play()
            ExecutionResult.ok("Ringtone played")
        } catch (e: Exception) {
            ExecutionResult.fail("Ringtone failed: ${e.message}")
        }
    }

    suspend fun vibrate(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val ms = args.firstOrNull()?.toLongOrNull() ?: 200L
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
            ExecutionResult.ok("Vibrated ${ms}ms")
        } catch (e: Exception) {
            ExecutionResult.fail("Vibrate failed: ${e.message}")
        }
    }
}

