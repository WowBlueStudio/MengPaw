"""Fix MainScreen.kt: SidebarOverlay + padding + InputBar extraction"""
lines = open('mengpaw-shell/src/main/kotlin/com/mengpaw/shell/ui/screens/MainScreen.kt', encoding='utf-8').readlines()

# 1. Find SidebarOverlay boundaries
so_start = None
for i, line in enumerate(lines):
    if 'Standalone composable to escape RowScope/ColumnScope' in line:
        so_start = i; break
so_end = None
for i in range(so_start+5, len(lines)):
    if lines[i].strip() == '}' and '@Composable' not in lines[i+1] if i+1 < len(lines) else True:
        so_end = i+1; break

new_so = '''/** 手机模式侧边栏: 遮罩固定淡入淡出, 面板独立滑动 */
@Composable
private fun SidebarOverlay(visible: Boolean, fromLeft: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val dimAlpha by animateFloatAsState(targetValue = if (visible) 1f else 0f, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "sd")
    if (visible || dimAlpha > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f * dimAlpha)).clickable { onDismiss() })
    AnimatedVisibility(visible = visible,
        enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (fromLeft) -it else it },
        exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { if (fromLeft) -it else it }
    ) {
        Box(Modifier.fillMaxSize()) {
            if (fromLeft) Surface(color = ThemeColors.bgPrimary, shadowElevation = 16.dp) { content() }
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { Surface(color = ThemeColors.bgPrimary, shadowElevation = 16.dp) { content() } }
        }
    }
}
'''

# 2. Fix padding
for i, line in enumerate(lines):
    if 'start = ArcoSpacing.lg, end = 8.dp' in line:
        lines[i] = line.replace('start = ArcoSpacing.lg, end = 8.dp, bottom = ArcoSpacing.sm, top = ArcoSpacing.sm',
                               'horizontal = 12.dp, vertical = ArcoSpacing.sm')
        break

# 3. Old section boundaries
old_start = None; old_end = None
for i, line in enumerate(lines):
    if 'Active tags row (above input)' in line: old_start = i
    if old_start and 'close mention anchor Box' in line: old_end = i+1; break
print(f'Old section: {old_start}-{old_end}')

# 4. InputBar call replacement
inputbar_call = '''            // Input bar (extracted)
            InputBar(
                inputText = inputText, onInputChange = { inputText = it },
                inputEnabled = inputEnabled, inputFocus = inputFocus,
                activeTags = activeTags, onRemoveTag = { viewModel.removeTag(it) },
                showMentionDropdown = showMentionDropdown, mentionQuery = mentionQuery,
                onMentionStateChange = { q, s -> mentionQuery = q; showMentionDropdown = s },
                showExpandSheet = showExpandSheet, onExpandSheetChange = { showExpandSheet = it },
                settingsViewModel = settingsViewModel, viewModel = viewModel,
                pluginViewModel = pluginViewModel, strings = strings
            )
'''

# 5. InputBar function to append
with open('inputbar_template.txt', 'r', encoding='utf-8') as f:
    inputbar_func = f.read()

# Build
new_lines = lines[:so_start] + [new_so + '\n'] + lines[so_end:old_start] + [inputbar_call + '\n'] + lines[old_end:]
# Find last closing brace and insert function
for i in range(len(new_lines)-1, len(new_lines)-30, -1):
    if new_lines[i].strip() == '}':
        new_lines = new_lines[:i] + [inputbar_func + '\n'] + new_lines[i:]
        break

with open('mengpaw-shell/src/main/kotlin/com/mengpaw/shell/ui/screens/MainScreen.kt', 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
print(f'Done: {len(new_lines)} lines')
