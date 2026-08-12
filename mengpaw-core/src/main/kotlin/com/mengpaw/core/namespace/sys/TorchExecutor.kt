// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * sys.torch.* — 手电筒开关 (对齐 Termux:API termux-torch)。
 *
 * 需要 CAMERA 权限 (已有声明) 与闪光灯硬件; Android 6+ CameraManager.setTorchMode。
 */
internal object TorchExecutor {

    /** 返回第一个带闪光灯的相机 id, 无则 null。 */
    private fun flashCameraId(cm: CameraManager): String? {
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun torchOn(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return setTorch(true)
    }

    suspend fun torchOff(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return setTorch(false)
    }

    private fun setTorch(on: Boolean): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.CAMERA)) {
            return ExecutionResult.fail(
                "需要 CAMERA 权限。请先执行 sys.permission.request CAMERA",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ExecutionResult.fail("CameraManager 不可用", errorCode = ErrorCodes.ERR_INTERNAL)
        val id = flashCameraId(cm)
            ?: return ExecutionResult.fail("设备无可用闪光灯", errorCode = ErrorCodes.ERR_INTERNAL)
        return try {
            cm.setTorchMode(id, on)
            ExecutionResult.ok("手电筒已${if (on) "打开" else "关闭"}")
        } catch (e: Exception) {
            ExecutionResult.fail("手电筒操作失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
