package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Agent Settings — system prompt, personality, and behavior configuration.
 * The system prompt is stored as an editable markdown memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val manager = remember { com.mengpaw.core.memory.MemoryManager() }

    // Load or create default system prompt memory
    var systemPrompt by remember {
        mutableStateOf(manager.get("system-prompt")?.content ?: DEFAULT_SYSTEM_PROMPT)
    }
    var personality by remember {
        mutableStateOf(manager.get("personality")?.content ?: DEFAULT_PERSONALITY)
    }
    var constraints by remember {
        mutableStateOf(manager.get("constraints")?.content ?: DEFAULT_CONSTRAINTS)
    }
    var saved by remember { mutableStateOf(false) }

    fun saveAll() {
        manager.save("system-prompt", "系统提示词", systemPrompt, listOf("system"))
        manager.save("personality", "个性", personality, listOf("personality"))
        manager.save("constraints", "约束", constraints, listOf("constraints"))
        saved = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SmartToy, null, tint = ArcoColors.Blue6, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text("Agent 设置", fontWeight = FontWeight.SemiBold)
                }},
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }},
                actions = {
                    TextButton(onClick = { saveAll() }) {
                        Text("保存", fontWeight = FontWeight.Bold, color = ArcoColors.Blue6)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = ArcoSpacing.lg)
        ) {
            Spacer(Modifier.height(ArcoSpacing.md))

            if (saved) {
                Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ArcoColors.Green1, modifier = Modifier.fillMaxWidth()) {
                    Text("已保存！", modifier = Modifier.padding(ArcoSpacing.md), color = ArcoColors.Green6, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(ArcoSpacing.md))
            }

            // System Prompt
            SectionLabel("系统提示词", "定义 Agent 核心任务和行为的指令")
            Spacer(Modifier.height(ArcoSpacing.sm))
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                shape = RoundedCornerShape(ArcoRadius.md),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcoColors.Blue6)
            )

            Spacer(Modifier.height(ArcoSpacing.lg))

            // Personality
            SectionLabel("个性", "语气、风格和行为特征")
            Spacer(Modifier.height(ArcoSpacing.sm))
            OutlinedTextField(
                value = personality,
                onValueChange = { personality = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = RoundedCornerShape(ArcoRadius.md),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcoColors.Blue6)
            )

            Spacer(Modifier.height(ArcoSpacing.lg))

            // Constraints
            SectionLabel("约束与规则", "Agent 必须遵守的边界")
            Spacer(Modifier.height(ArcoSpacing.sm))
            OutlinedTextField(
                value = constraints,
                onValueChange = { constraints = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = RoundedCornerShape(ArcoRadius.md),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcoColors.Blue6)
            )

            Spacer(Modifier.height(ArcoSpacing.lg))

            // Saved memories indicator
            Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ArcoColors.Gray1, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(ArcoSpacing.md))
                    Column {
                        Text("以记忆文档形式存储", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text("system-prompt.md · personality.md · constraints.md", style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(ArcoSpacing.xxxl))
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ArcoColors.Blue6)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
}

private val DEFAULT_SYSTEM_PROMPT = """你是 MengPaw，运行在 Android 上的自主 Agent。
你通过 CLI 命令控制设备。
你的能力包括：
- 文件系统操作（读写/列出/删除文件）
- UI 交互（点击、滑动、输入、截图）
- 网络请求（HTTP GET/POST）
- 自我检查（状态、统计、版本）
- 记忆系统（读写/搜索记忆）

你必须以 ReAct 格式响应：
Thought: （你的推理）
Action: （命令名称）
Action Input: （参数）
...或...
Final Answer: （你的结论）"""

private val DEFAULT_PERSONALITY = """- 简洁直接
- 清晰的推理步骤
- 不确定时先检查系统状态
- 重要操作给出解释
- 根据用户偏好使用中文或英文"""

private val DEFAULT_CONSTRAINTS = """- 绝不泄露 API Key 或凭据
- 未经确认绝不执行破坏性命令
- 遵守文件系统边界（应用私有目录）
- 每个任务最多 50 次 ReAct 迭代
- 同一命令重复 3 次以上自动停止"""
