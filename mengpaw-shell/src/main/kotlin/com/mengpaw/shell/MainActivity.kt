package com.mengpaw.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

class MainActivity : ComponentActivity() {

    private val settingsViewModel = SettingsViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            val strings: AppStrings =
                if (settingsState.useChinese) ChineseStrings else EnglishStrings

            ArcoTheme(darkTheme = settingsState.darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        settingsViewModel = settingsViewModel,
                        strings = strings
                    )
                }
            }
        }
    }
}

enum class Screen {
    Main, Settings, Memories, AgentSettings, Skills, Browser, Preview, LogViewer, ExtensionMarket
}

@Composable
fun AppNavigation(
    settingsViewModel: SettingsViewModel,
    strings: AppStrings
) {
    var currentScreen by remember { mutableStateOf(Screen.Main) }
    var browserUrl by remember { mutableStateOf("https://www.baidu.com") }
    var previewPath by remember { mutableStateOf("") }
    var previewName by remember { mutableStateOf("") }
    val context = LocalContext.current

    val browserPackage = "com.mengpaw.browser"
    val isBrowserInstalled = remember {
        try {
            context.packageManager.getPackageInfo(browserPackage, 0)
            true
        } catch (e: Exception) { false }
    }

    // Launch browser: prefer external APK, fallback to built-in
    val launchBrowser = { url: String ->
        if (isBrowserInstalled) {
            try {
                val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
                    putExtra("url", url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to built-in
                browserUrl = url; currentScreen = Screen.Browser
            }
        } else {
            browserUrl = url; currentScreen = Screen.Browser
        }
    }

    when (currentScreen) {
        Screen.Main -> MainScreen(
            onNavigateToSettings = { currentScreen = Screen.Settings },
            onNavigateToMemories = { currentScreen = Screen.Memories },
            onNavigateToBrowser = { launchBrowser("https://www.baidu.com") },
            onNavigateToSkills = { currentScreen = Screen.Skills },
            strings = strings
        )
        Screen.Settings -> SettingsScreen(
            onNavigateBack = { currentScreen = Screen.Main },
            onNavigateToAgentSettings = { currentScreen = Screen.AgentSettings },
            onNavigateToMemories = { currentScreen = Screen.Memories },
            onNavigateToSkills = { currentScreen = Screen.Skills },
            viewModel = settingsViewModel
        )
        Screen.Memories -> MemoriesScreen(
            onNavigateBack = { currentScreen = Screen.Main },
            onPreviewFile = { path, name ->
                previewPath = path; previewName = name; currentScreen = Screen.Preview
            }
        )
        Screen.AgentSettings -> AgentSettingsScreen(
            onNavigateBack = { currentScreen = Screen.Settings }
        )
        Screen.Skills -> SkillsScreen(
            onNavigateBack = { currentScreen = Screen.Main }
        )
        Screen.Browser -> BrowserScreen(
            url = browserUrl,
            onNavigateBack = { currentScreen = Screen.Main }
        )
        Screen.Preview -> PreviewScreen(
            filePath = previewPath,
            fileName = previewName,
            onNavigateBack = { currentScreen = Screen.Memories }
        )
        Screen.LogViewer -> LogViewerScreen(
            onNavigateBack = { currentScreen = Screen.Settings }
        )
        Screen.ExtensionMarket -> ExtensionMarketScreen(
            onNavigateBack = { currentScreen = Screen.Settings }
        )
    }
}
