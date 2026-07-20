// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.tv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.core.AgentEngine
import com.mengpaw.core.agent.AgentMiddleware
import com.mengpaw.core.agent.PostCallMiddleware
import com.mengpaw.core.llm.AdaptiveLlmProvider
import com.mengpaw.core.llm.LlmProvider
import kotlinx.coroutines.*

/**
 * MengPaw TV — voice-first Agent + launcher replacement for Android TV.
 *
 * ## Built-in Free API
 * Pre-configured with a free model endpoint. No setup needed.
 * Users can change to paid APIs via Settings.
 *
 * ## Key Bindings
 * - Voice key: start/stop voice input
 * - D-pad: navigate chat + app grid
 * - Back: exit voice mode
 */
class TvMainActivity : ComponentActivity() {

    private val voiceInput = TvVoiceInput(this)
    private val ttsOutput = TvTtsOutput(this)
    private val messages = mutableStateListOf<TvChatMessage>()
    private var voiceJob: Job? = null
    private var agentEngine: AgentEngine? = null
    private var isAgentReady = mutableStateOf(false)

    // ── Default free API (user can change in settings) ──
    private var apiEndpoint = DEFAULT_API_ENDPOINT
    private var apiKey = DEFAULT_API_KEY
    private var modelName = DEFAULT_MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mengpaw.core.DataPaths.initialize(this)
        loadApiConfig()
        initAgent()

        ttsOutput.init {
            ttsOutput.speak("孟爪电视已就绪。按住遥控器语音键开始对话。")
        }

        setContent {
            var isListening by remember { mutableStateOf(false) }
            var partialText by remember { mutableStateOf("") }
            var showSettings by remember { mutableStateOf(false) }
            val agentReady by isAgentReady

            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
                Column(Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MengPaw TV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!agentReady) {
                                Text("⏳ 连接模型...", fontSize = 16.sp, color = Color(0xFFFFB400))
                                Spacer(Modifier.width(24.dp))
                            }
                            Text(
                                if (isListening) "🎤 正在听" else "语音键说话",
                                fontSize = 18.sp,
                                color = Color(0xFF86909C)
                            )
                            Spacer(Modifier.width(24.dp))
                            Text(
                                "⚙️",
                                fontSize = 24.sp,
                                modifier = Modifier.clickable { showSettings = !showSettings }
                            )
                        }
                    }

                    // Settings panel
                    AnimatedVisibility(visible = showSettings,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()) {
                        TvSettingsPanel(
                            endpoint = apiEndpoint,
                            model = modelName,
                            onEndpointChange = { apiEndpoint = it; saveApiConfig(); initAgent() },
                            onModelChange = { modelName = it; saveApiConfig(); initAgent() },
                            onDismiss = { showSettings = false }
                        )
                    }

                    // Chat area
                    TvChatScreen(
                        messages = messages.toList(),
                        isListening = isListening,
                        partialText = partialText,
                        modifier = Modifier.weight(1f)
                    )

                    // App grid
                    TvAppGrid()
                    Spacer(Modifier.height(24.dp))
                }

                // Voice overlay
                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xE0165DFF), RoundedCornerShape(24.dp))
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎤", fontSize = 48.sp)
                            Text(
                                if (partialText.startsWith("...")) partialText.removePrefix("...") else "正在听...",
                                fontSize = 24.sp,
                                color = Color.White
                            )
                            Text("松开语音键结束", fontSize = 16.sp, color = Color(0xAAFFFFFF), modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }

    // ── Agent ───────────────────────────────────────────────────────────

    private fun initAgent() {
        try {
            val provider = AdaptiveLlmProvider(apiEndpoint, apiKey, modelName)
            agentEngine = AgentEngine(
                llmProvider = provider,
                middleware = AgentMiddleware.NoOp,
                postCallMiddleware = PostCallMiddleware.NoOp
            ).also {
                it.agentName = "MengPaw TV"
                it.configureCacheStrategy(apiEndpoint)
            }
            isAgentReady.value = true
            messages.add(TvChatMessage("system", "✅ 模型已连接: $modelName\n免费节点，即开即用。"))
        } catch (e: Exception) {
            isAgentReady.value = false
            messages.add(TvChatMessage("system", "⚠️ 模型连接失败，使用离线模式。\n按 ⚙️ 配置 API。\n${e.message?.take(80)}"))
        }
    }

    private fun processAgentMessage(userText: String) {
        val engine = agentEngine
        if (engine == null || !isAgentReady.value) {
            // Fallback offline agent
            val response = fallbackResponse(userText)
            messages.add(TvChatMessage("agent", response))
            ttsOutput.speak(response)
            return
        }

        MainScope().launch {
            try {
                val result = engine.run(userText, maxSteps = 10)
                messages.add(TvChatMessage("agent", result))
                ttsOutput.speak(result)
            } catch (e: Exception) {
                val msg = "处理出错: ${e.message?.take(60)}"
                messages.add(TvChatMessage("agent", msg))
                ttsOutput.speak("抱歉，处理出错了")
            }
        }
    }

    private fun fallbackResponse(text: String): String = when {
        text.contains("天气") -> "需要联网搜索天气信息。请按 ⚙️ 配置 API Key 以启用完整 Agent 能力。"
        text.contains("打开") || text.contains("启动") -> {
            val app = text.removePrefix("打开").removePrefix("启动").trim()
            openAppByName(app)
            "正在打开 $app"
        }
        text.contains("再见") || text.contains("退出") -> {
            moveTaskToBack(true); "再见！"
        }
        text.contains("帮助") || text.contains("能做什么") ->
            "我是 MengPaw TV Agent。\n" +
            "• 按住遥控器语音键和我对话\n" +
            "• 说"打开 YouTube"启动应用\n" +
            "• 联网后可搜索、翻译、控制智能家居\n" +
            "• 按 ⚙️ 配置付费 API 解锁更多能力"
        else -> "收到: \"$text\"\n\n⚠️ 当前为离线模式。按 ⚙️ 配置 API Key 解锁联网 Agent。"
    }

    // ── Voice ───────────────────────────────────────────────────────────

    private fun startVoiceInput() {
        voiceJob?.cancel()
        voiceJob = MainScope().launch {
            voiceInput.listen().collect { text ->
                when {
                    text == "🎤 正在听..." -> { }
                    text.startsWith("...") -> { }
                    text.startsWith("语音") || text.startsWith("未检测") -> {
                        messages.add(TvChatMessage("system", text))
                    }
                    text.isNotBlank() -> {
                        messages.add(TvChatMessage("user", text))
                        processAgentMessage(text)
                    }
                }
            }
        }
    }

    private fun stopVoiceInput() {
        voiceInput.stop()
        voiceJob?.cancel()
    }

    // ── Key Events ──────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (event.repeatCount == 0) startVoiceInput(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1 -> {
                stopVoiceInput(); true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun openAppByName(name: String) {
        try {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val match = pm.queryIntentActivities(intent, 0).find {
                it.loadLabel(pm).toString().contains(name, ignoreCase = true)
            }
            match?.let {
                val launch = pm.getLaunchIntentForPackage(it.activityInfo.packageName)
                launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (_: Exception) { }
    }

    private fun loadApiConfig() {
        val prefs = getSharedPreferences("mengpaw_tv", Context.MODE_PRIVATE)
        apiEndpoint = prefs.getString("api_endpoint", DEFAULT_API_ENDPOINT) ?: DEFAULT_API_ENDPOINT
        apiKey = prefs.getString("api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        modelName = prefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    private fun saveApiConfig() {
        getSharedPreferences("mengpaw_tv", Context.MODE_PRIVATE).edit().apply {
            putString("api_endpoint", apiEndpoint)
            putString("api_key", apiKey)
            putString("model_name", modelName)
            apply()
        }
    }

    override fun onDestroy() {
        voiceInput.destroy()
        ttsOutput.destroy()
        super.onDestroy()
    }

    companion object {
        // ═══════════════════════════════════════════════════════════
        // OpenModel 免费节点 — 开机即用，零配置
        // 去 openmodel.ai 注册(免费), 获取 API Key 替换下面的值
        // 免费额度足够日常使用, 无需绑卡
        // ═══════════════════════════════════════════════════════════
        private const val DEFAULT_API_ENDPOINT = "https://api.openmodel.ai"
        private const val DEFAULT_API_KEY = "" // ← 替换为你的 OpenModel Key (sk-...)
        private const val DEFAULT_MODEL = "deepseek-v4-flash"
    }
}

// ── Settings Panel ─────────────────────────────────────────────────────

@Composable
private fun TvSettingsPanel(
    endpoint: String,
    model: String,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp)
            .background(Color(0x20FFFFFF), RoundedCornerShape(12.dp))
            .padding(24.dp)
    ) {
        Column {
            Text("API 设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前: $model @ ${endpoint.take(35)}...",
                fontSize = 14.sp, color = Color(0xFF86909C)
            )
            Text(
                "内置 DeepSeek V4 Flash 免费节点。\n注册 deepseek.com 获取 API Key 即可使用。\nChat 通道已废弃，V4 Flash 更快更省。",
                fontSize = 14.sp, color = Color(0xFF86909C),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "按 ⚙️ 关闭此面板。API 配置通过手机端 MengPaw Shell 的 ACP 远程同步更方便。",
                fontSize = 14.sp, color = Color(0xFF5E5E5E)
            )
        }
    }
}
