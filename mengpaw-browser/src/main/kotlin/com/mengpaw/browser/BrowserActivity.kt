package com.mengpaw.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.design.tokens.ArcoColors

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Determine initial URL from intent
        val initialUrl = when {
            intent?.action == "com.mengpaw.action.OPEN_URL" -> intent.getStringExtra("url") ?: "https://www.baidu.com"
            intent?.dataString != null -> intent.dataString!!
            else -> "https://www.baidu.com"
        }
        setContent {
            ArcoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserApp(initialUrl = initialUrl)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserApp(initialUrl: String = "https://www.baidu.com") {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var inputUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    val defaultUrl = "https://www.baidu.com"
    val mainAppPackage = "com.mengpaw.shell"

    // Check if main app is installed
    val isMainAppInstalled = remember {
        try {
            context.packageManager.getPackageInfo(mainAppPackage, 0)
            true
        } catch (e: Exception) { false }
    }

    // Wake up main app
    val wakeMainApp = {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(mainAppPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("BrowserActivity", "Failed to wake main app: ${e.message}")
        }
    }

    Column(Modifier.fillMaxSize().background(ArcoColors.BgPrimary)) {
        // ─── Top bar ───
        Surface(shadowElevation = 2.dp, color = ArcoColors.BgPrimary) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowBack, "后退", tint = if (canGoBack) ArcoColors.TextPrimary else ArcoColors.Gray4)
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowForward, "前进", tint = if (canGoForward) ArcoColors.TextPrimary else ArcoColors.Gray4)
                    }
                    IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, "刷新", tint = ArcoColors.TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.weight(1f).height(40.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArcoColors.Blue6,
                            unfocusedBorderColor = ArcoColors.BorderDefault
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = {
                            val url = inputUrl.let { if (!it.startsWith("http")) "https://$it" else it }
                            currentUrl = url; inputUrl = url; webView?.loadUrl(url)
                        },
                        modifier = Modifier.size(36.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ArcoColors.Blue6)
                    ) { Icon(Icons.Default.Send, "前往", tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
        }

        // Page title
        if (pageTitle.isNotBlank()) {
            Text(pageTitle, style = MaterialTheme.typography.bodySmall,
                color = ArcoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }

        // Progress bar
        if (isLoading) {
            LinearProgressIndicator(progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = ArcoColors.Blue6, trackColor = ArcoColors.Gray2)
        }

        // WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true; url?.let { inputUrl = it; currentUrl = it }
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false; pageTitle = view?.title ?: ""
                            canGoBack = view?.canGoBack() ?: false; canGoForward = view?.canGoForward() ?: false
                        }
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canGoBack = view?.canGoBack() ?: false; canGoForward = view?.canGoForward() ?: false
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { isLoading = false }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView?, title: String?) { pageTitle = title ?: "" }
                    }
                    loadUrl(currentUrl)
                    webView = this
                }
            },
            update = { webView = it },
            modifier = Modifier.weight(1f)
        )

        // Bottom toolbar
        Surface(shadowElevation = 4.dp, color = ArcoColors.BgPrimary) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomNavItem(Icons.Default.Home, "主页") { currentUrl = defaultUrl; inputUrl = defaultUrl; webView?.loadUrl(defaultUrl) }
                BottomNavItem(Icons.Default.Refresh, "刷新") { webView?.reload() }
                BottomNavItem(Icons.Default.Star, "书签") { /* future */ }
                BottomNavItem(Icons.Default.Info, "关于") { /* future */ }
                // Wake main app button
                if (isMainAppInstalled) {
                    BottomNavItem(Icons.Default.Favorite, "MengPaw") { wakeMainApp() }
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = ArcoColors.Gray7, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = ArcoColors.Gray7, fontSize = 10.sp)
        }
    }
}
