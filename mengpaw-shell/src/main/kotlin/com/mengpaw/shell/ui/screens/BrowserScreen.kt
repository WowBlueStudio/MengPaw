package com.mengpaw.shell.ui.screens
import androidx.compose.material.icons.outlined.*

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import java.io.File

/**
 * Preview a file: Markdown (.md) or Image (.png/.jpg/.jpeg/.gif/.bmp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    filePath: String,
    fileName: String,
    onNavigateBack: () -> Unit
) {
    val ext = fileName.substringAfterLast('.', "").lowercase()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(fileName, fontWeight = FontWeight.SemiBold, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Outlined.ArrowBack, "返回") }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ext) {
                "png", "jpg", "jpeg", "gif", "bmp", "webp" -> ImagePreview(filePath)
                "md", "markdown" -> MarkdownPreview(filePath)
                else -> Text("不支持的文件格式: .$ext", modifier = Modifier.padding(ArcoSpacing.lg), color = com.mengpaw.design.theme.ThemeColors.textSecondary)
            }
        }
    }
}

@Composable
private fun ImagePreview(path: String) {
    val bitmap = remember { BitmapFactory.decodeFile(path) }
    if (bitmap == null) {
        Text("无法加载图片", color = com.mengpaw.design.theme.ThemeColors.textSecondary, modifier = Modifier.padding(ArcoSpacing.lg))
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ArcoSpacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.md))
            Spacer(Modifier.height(ArcoSpacing.sm))
            Text("${bitmap.width} × ${bitmap.height}px", style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
        }
    }
}

@Composable
private fun MarkdownPreview(path: String) {
    val content = remember { try { File(path).readText() } catch (e: Exception) { "无法读取文件" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ArcoSpacing.lg)) {
        // Simple Markdown rendering
        content.lines().forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.removePrefix("### ").trim(),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = ArcoSpacing.sm, bottom = 2.dp))
                line.startsWith("## ") -> Text(line.removePrefix("## ").trim(),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = ArcoSpacing.md, bottom = 4.dp))
                line.startsWith("# ") -> Text(line.removePrefix("# ").trim(),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = ArcoSpacing.md, bottom = 4.dp))
                line.startsWith("> ") -> Text(line.removePrefix("> ").trim(),
                    style = MaterialTheme.typography.bodyMedium, color = com.mengpaw.design.theme.ThemeColors.textSecondary,
                    modifier = Modifier.padding(start = ArcoSpacing.md, top = 2.dp, bottom = 2.dp))
                line.startsWith("- ") || line.startsWith("* ") -> Text("• ${line.removePrefix("- ").removePrefix("* ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = ArcoSpacing.md))
                line.startsWith("---") -> HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border, modifier = Modifier.padding(vertical = ArcoSpacing.sm))
                line.isBlank() -> Spacer(Modifier.height(ArcoSpacing.xs))
                else -> Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Simple WebView browser screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    url: String = "https://www.baidu.com",
    onNavigateBack: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(url) }
    var inputUrl by remember { mutableStateOf(url) }
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("浏览器 Browser", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Outlined.ArrowBack, "返回") }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // URL bar
            Row(Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(ArcoRadius.md),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(ArcoSpacing.xs))
                IconButton(onClick = {
                    val finalUrl = inputUrl.let {
                        if (!it.startsWith("http")) "https://$it" else it
                    }
                    currentUrl = finalUrl
                    inputUrl = finalUrl
                }) {
                    Icon(Icons.Outlined.Send, "前往", tint = com.mengpaw.design.theme.ThemeColors.brand)
                }
            }

            // Loading indicator
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = com.mengpaw.design.theme.ThemeColors.brand)

            // WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.let { inputUrl = it; currentUrl = it }
                            }
                        }
                        loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.loadUrl(currentUrl) }
            )
        }
    }
}
