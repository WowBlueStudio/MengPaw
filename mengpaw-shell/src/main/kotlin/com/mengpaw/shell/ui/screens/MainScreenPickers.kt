// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mengpaw.kernel.session.AttachmentData

// ── 文件选择器 launcher 集合 — 拆自 MainScreen.kt (2026-08-06, >400 行文件拆分批次4) ──

/** 图片/文档/文件/相机四类选择器 + 相机输出 Uri。 */
data class FilePickers(
    val imagePicker: ManagedActivityResultLauncher<String, Uri?>,
    val docPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val filePicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val cameraUri: Uri,
    val cameraLauncher: ManagedActivityResultLauncher<Uri, Boolean>
)

/** v0.33.0+: 产出结构化附件, 不再插文本进输入框。 */
@Composable
internal fun rememberFilePickers(
    context: android.content.Context,
    pendingUploadDir: String,
    onAttachment: (AttachmentData) -> Unit,
    onError: (String) -> Unit
): FilePickers {
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = onAttachment, onError = onError)
    } }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = onAttachment, onError = onError)
    } }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = onAttachment, onError = onError)
    } }

    val cameraUri = remember {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "camera_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            handleFilePicked(cameraUri, context, pendingUploadDir,
                onAttachment = onAttachment, onError = onError)
        }
    }

    return FilePickers(
        imagePicker = imagePicker,
        docPicker = docPicker,
        filePicker = filePicker,
        cameraUri = cameraUri,
        cameraLauncher = cameraLauncher
    )
}
