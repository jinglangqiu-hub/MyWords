package com.example.wordreview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.WordLibrarySummary
import com.example.wordreview.domain.ReviewSummary
import com.example.wordreview.tts.TtsManager
import com.example.wordreview.tts.TtsStatus
import com.example.wordreview.tts.TtsDiagnostics
import com.example.wordreview.tts.ttsErrorMessage
import com.example.wordreview.ui.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WordReviewTheme {
                Surface(Modifier.fillMaxSize()) {
                    WordReviewApp()
                }
            }
        }
    }
}

private object Routes {
    const val Home = "home"
    const val Import = "import/{libraryId}"
    const val Libraries = "libraries"
    const val Library = "library/{libraryId}"
    const val Detail = "detail/{libraryId}/{wordId}"
    const val Setup = "setup"
    const val Choice = "choice"
    const val Spelling = "spelling"
    const val TtsSettings = "tts-settings"
    const val Result = "result"
    fun import(libraryId: Long) = "import/$libraryId"
    fun library(libraryId: Long) = "library/$libraryId"
    fun detail(libraryId: Long, wordId: Long) = "detail/$libraryId/$wordId"
}

private val Mint = Color(0xFF80D1C8)
private val Peach = Color(0xFFFFD4A9)
private val Ink = Color(0xFF202524)
private val AppBackground = Color(0xFFFFFFFF)
private val MutedInk = Color(0xFF6B716F)
private val SquareCornerShape = RoundedCornerShape(0.dp)

private val WordReviewColors = lightColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Mint,
    onPrimaryContainer = Ink,
    secondary = Peach,
    onSecondary = Ink,
    secondaryContainer = Peach,
    onSecondaryContainer = Ink,
    background = AppBackground,
    onBackground = Ink,
    surface = AppBackground,
    onSurface = Ink,
    surfaceVariant = Color(0xFFFFF5EB),
    onSurfaceVariant = MutedInk,
    outline = Mint,
    outlineVariant = Color(0xFFE8E4DF)
)

private val SquareShapes = Shapes(
    extraSmall = SquareCornerShape,
    small = SquareCornerShape,
    medium = SquareCornerShape,
    large = SquareCornerShape,
    extraLarge = SquareCornerShape
)

@Composable
private fun WordReviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WordReviewColors,
        shapes = SquareShapes,
        content = content
    )
}

private fun Modifier.actionButton(): Modifier = fillMaxWidth().heightIn(min = 52.dp)

private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
private const val ACTION_TTS_SETTINGS_ALT = "android.settings.TTS_SETTINGS"

@Composable
private fun WordReviewApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val tts = remember { TtsManager(context) }
    val ttsStatus by tts.status.collectAsStateWithLifecycle()
    val ttsDiagnostics by tts.diagnostics.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val ttsSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tts.reinitialize()
    }
    val ttsInstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tts.reinitialize()
    }
    val openTtsSettings = {
        val enginePackage = ttsDiagnostics.engineName.takeIf { it.isNotBlank() }
        val candidates = buildList {
            add(Intent(ACTION_TTS_SETTINGS) to "系统语音设置")
            add(Intent(ACTION_TTS_SETTINGS_ALT) to "系统语音设置")
            if (enginePackage != null) add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$enginePackage")) to "TTS 引擎应用设置"
            )
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) to "系统辅助功能设置")
        }
        if (launchFirstAvailable(context, candidates, ttsSettingsLauncher::launch) == null) {
            Toast.makeText(context, "无法打开系统语音或引擎设置。", Toast.LENGTH_LONG).show()
        }
    }
    val installTtsData = {
        val enginePackage = ttsDiagnostics.engineName.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (enginePackage != null) add(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage) to "语音数据安装页面"
            )
            add(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA) to "语音数据安装页面")
            add(Intent(ACTION_TTS_SETTINGS) to "系统语音设置")
            add(Intent(ACTION_TTS_SETTINGS_ALT) to "系统语音设置")
            if (enginePackage != null) add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$enginePackage")) to "TTS 引擎应用设置"
            )
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) to "系统辅助功能设置")
        }
        val destination = launchFirstAvailable(context, candidates, ttsInstallLauncher::launch)
        if (destination == null) {
            Toast.makeText(context, "手机没有提供可用的语音安装或设置入口。", Toast.LENGTH_LONG).show()
        } else if (destination != "语音数据安装页面") {
            Toast.makeText(context, "当前引擎没有独立安装页面，已打开$destination。", Toast.LENGTH_LONG).show()
        }
    }
    val installTtsEngine = {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=text%20to%20speech&c=apps")) to "应用商店的 TTS 引擎搜索",
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=text%20to%20speech&c=apps")) to "浏览器中的 TTS 引擎搜索",
            Intent(Settings.ACTION_SETTINGS) to "系统设置"
        )
        val destination = launchFirstAvailable(context, candidates, ttsInstallLauncher::launch)
        if (destination == null) {
            Toast.makeText(context, "手机没有可用的应用商店、浏览器或系统设置入口。", Toast.LENGTH_LONG).show()
        } else if (destination == "系统设置") {
            Toast.makeText(context, "未找到应用商店入口，请在系统应用商店中搜索并安装 TTS 或文字转语音引擎。", Toast.LENGTH_LONG).show()
        }
    }

    NavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                vm,
                onLibrary = { nav.navigate(Routes.Libraries) },
                onReview = { vm.openReview { nav.navigate(Routes.Setup) } },
                onContinueChoice = { nav.navigate(Routes.Choice) },
                onContinueSpelling = { nav.navigate(Routes.Spelling) },
                onRestartChoice = { nav.navigate(Routes.Choice) },
                onTtsSettings = { nav.navigate(Routes.TtsSettings) }
            )
        }
        composable(Routes.Libraries) {
            LibraryListScreen(
                vm,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.library(it)) },
                onImport = { nav.navigate(Routes.import(it)) }
            )
        }
        composable(Routes.Import, arguments = listOf(navArgument("libraryId") { type = NavType.LongType })) { entry ->
            val libraryId = entry.arguments?.getLong("libraryId") ?: 0L
            LaunchedEffect(libraryId) { vm.openImport(libraryId) }
            ImportScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.Library, arguments = listOf(navArgument("libraryId") { type = NavType.LongType })) { entry ->
            val libraryId = entry.arguments?.getLong("libraryId") ?: 0L
            LaunchedEffect(libraryId) { vm.loadLibrary(libraryId) }
            LibraryScreen(
                vm,
                onBack = { nav.popBackStack() },
                onImport = { nav.navigate(Routes.import(libraryId)) },
                onDetail = { nav.navigate(Routes.detail(libraryId, it)) }
            )
        }
        composable(
            Routes.Detail,
            arguments = listOf(
                navArgument("libraryId") { type = NavType.LongType },
                navArgument("wordId") { type = NavType.LongType }
            )
        ) { entry ->
            val libraryId = entry.arguments?.getLong("libraryId") ?: 0L
            val wordId = entry.arguments?.getLong("wordId") ?: 0L
            LaunchedEffect(libraryId, wordId) { vm.loadDetail(libraryId, wordId) }
            DetailScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.Setup) {
            ReviewSetupScreen(
                vm,
                onBack = { nav.popBackStack() },
                onChoice = {
                    nav.navigate(Routes.Choice) {
                        popUpTo(Routes.Setup) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Choice) {
            ChoiceScreen(
                vm,
                tts,
                onSpelling = {
                    nav.navigate(Routes.Spelling) {
                        popUpTo(Routes.Home) { inclusive = false }
                    }
                },
                onTtsSettings = { nav.navigate(Routes.TtsSettings) }
            )
        }
        composable(Routes.Spelling) {
            SpellingScreen(
                vm,
                tts,
                onDone = {
                    nav.navigate(Routes.Result) {
                        popUpTo(Routes.Home) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onTtsSettings = { nav.navigate(Routes.TtsSettings) }
            )
        }
        composable(Routes.TtsSettings) {
            TtsSettingsScreen(
                status = ttsStatus,
                diagnostics = ttsDiagnostics,
                onBack = { nav.popBackStack() },
                onRetry = tts::reinitialize,
                onOpenSettings = openTtsSettings,
                onInstallData = installTtsData,
                onInstallEngine = installTtsEngine
            )
        }
        composable(Routes.Result) {
            ResultScreen(vm, onDone = {
                nav.popBackStack(Routes.Home, inclusive = false)
                vm.refreshHome()
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (onBack != null) {
                            TextButton(
                                onClick = onBack,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                            ) { Text("返回") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding -> content(Modifier.padding(padding).padding(horizontal = 22.dp, vertical = 20.dp)) }
}

@Composable
private fun HomeScreen(
    vm: AppViewModel,
    onLibrary: () -> Unit,
    onReview: () -> Unit,
    onContinueChoice: () -> Unit,
    onContinueSpelling: () -> Unit,
    onRestartChoice: () -> Unit,
    onTtsSettings: () -> Unit
) {
    val state by vm.home.collectAsStateWithLifecycle()
    var showResetMemoryDialog by remember { mutableStateOf(false) }
    if (showResetMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showResetMemoryDialog = false },
            title = { Text("重置我的复习记忆") },
            text = {
                Text(
                    "这会清除所有单词的复习间隔、遗忘次数和连续正确次数，并放弃未完成的复习。" +
                        "词库、单词、释义和“不再拼写”设置会保留。此操作无法撤销。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetMemoryDialog = false
                        vm.resetReviewMemory()
                    }
                ) { Text("确认重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetMemoryDialog = false }) { Text("取消") }
            }
        )
    }
    if (state.showUnfinishedReviewDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissUnfinishedReviewDialog,
            title = { Text("有未完成的复习") },
            text = { Text("请选择如何处理上次未完成的复习。") },
            confirmButton = {
                TextButton(onClick = { vm.continueUnfinishedReview(onContinueChoice, onContinueSpelling) }) {
                    Text("继续进度")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.restartUnfinishedReview(onRestartChoice) }) {
                        Text("重新开始")
                    }
                    TextButton(onClick = vm::skipUnfinishedReview) {
                        Text("跳过")
                    }
                }
            }
        )
    }
    Page("个人生词复习") { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            HomeOverview(
                wordCount = state.wordCount.toString(),
                libraryCount = state.libraryCount.toString(),
                latestImport = state.latestImport?.let { "${it.newCount} 新增 / ${it.existingCount} 已有" } ?: "暂无"
            )
            Button(onClick = onReview, modifier = Modifier.actionButton()) { Text("开始复习") }
            state.reviewErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onLibrary,
                modifier = Modifier.actionButton(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("管理单词库 / 导入") }
            Button(
                onClick = onTtsSettings,
                modifier = Modifier.actionButton(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("语音设置") }
            OutlinedButton(
                onClick = { showResetMemoryDialog = true },
                enabled = !state.isResettingMemory,
                modifier = Modifier.actionButton()
            ) { Text(if (state.isResettingMemory) "正在重置…" else "重置我的复习记忆") }
            state.memoryResetMessage?.let { message ->
                Text(
                    message,
                    color = if (message.startsWith("重置失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeOverview(wordCount: String, libraryCount: String, latestImport: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val metricWidth = (maxWidth - 25.dp) / 2
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBlock("总单词数", wordCount, Modifier.width(metricWidth))
                VerticalDivider(
                    modifier = Modifier.height(72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                MetricBlock("词库数量", libraryCount, Modifier.width(metricWidth))
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            StatLine("最近导入", latestImport)
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LibraryListScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onImport: (Long) -> Unit
) {
    val state by vm.libraryList.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WordLibrarySummary?>(null) }
    deleteTarget?.let { library ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除词库") },
            text = { Text("删除“${library.name}”及其专属单词和记忆？共享单词仍会保留。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteLibrary(library.id); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
    Page("单词库", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.nameInput,
                onValueChange = vm::updateLibraryNameInput,
                label = { Text(if (state.editingId == null) "新词库名称" else "修改词库名称") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = vm::saveLibrary, modifier = Modifier.actionButton()) {
                Text(if (state.editingId == null) "新建词库" else "保存名称")
            }
            if (state.editingId != null) {
                OutlinedButton(onClick = vm::cancelLibraryEdit, modifier = Modifier.actionButton()) { Text("取消") }
            }
            state.message?.let { Text(it, color = if (it.contains("失败") || it.contains("存在") || it.contains("至少")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(state.libraries, key = { it.id }) { library ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(library.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${library.wordCount} 个单词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onOpen(library.id) }, modifier = Modifier.actionButton()) { Text("查看") }
                        Button(
                            onClick = { onImport(library.id) },
                            modifier = Modifier.actionButton(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) { Text("导入/更新") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { vm.beginRenameLibrary(library) }) { Text("重命名") }
                            TextButton(onClick = { deleteTarget = library }) { Text("删除") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun launchFirstAvailable(
    context: Context,
    candidates: List<Pair<Intent, String>>,
    launch: (Intent) -> Unit
): String? {
    candidates.forEach { (intent, label) ->
        if (intent.resolveActivity(context.packageManager) != null) {
            try {
                launch(intent)
                return label
            } catch (_: ActivityNotFoundException) {
                // Try the next OEM-compatible destination.
            }
        }
    }
    return null
}

@Composable
private fun ImportScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.importState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    Page("导入 CSV", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "目标词库：${state.libraryName.ifBlank { "加载中…" }}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { launcher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) },
                enabled = !state.isImporting,
                modifier = Modifier.actionButton()
            ) {
                Text(if (state.isImporting) "正在导入" else "选择文件")
            }
            state.result?.let {
                Text("导入成功", fontWeight = FontWeight.Bold)
                Text("总行数：${it.totalRows}")
                Text("新增单词：${it.newCount}")
                Text("已有单词：${it.existingCount}")
                Text("更新释义：${it.updatedMeaningCount}")
                Text("失败行数：${it.failedRowCount}")
                Text("文件编码：${it.encodingName}")
                if (it.skippedHeaderCount > 0) Text("已跳过表头：${it.skippedHeaderCount}")
                if (it.failureReasons.isNotEmpty()) {
                    Text("失败原因：${it.failureReasons.entries.joinToString("；") { item -> "${item.key} ${item.value} 行" }}")
                    Text("示例行号：${it.failureLineNumbers.joinToString()}")
                }
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun LibraryScreen(vm: AppViewModel, onBack: () -> Unit, onImport: () -> Unit, onDetail: (Long) -> Unit) {
    val state by vm.library.collectAsStateWithLifecycle()
    Page(state.libraryName.ifBlank { "单词库" }, onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onImport,
                modifier = Modifier.actionButton(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("导入 / 更新这个词库") }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::updateSearch,
                label = { Text("搜索英文或中文释义") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.words, key = { it.wordId }) { word ->
                    WordRow(word, onClick = { onDetail(word.wordId) })
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.detail.collectAsStateWithLifecycle()
    val word = state.word
    Page("单词详情", onBack) { modifier ->
        if (word == null) {
            Text("单词不存在")
        } else {
            Column(
                modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    word.displayTerm,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = state.editText,
                    onValueChange = vm::updateDetailEdit,
                    label = { Text("释义") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = vm::saveMeaning, modifier = Modifier.actionButton()) { Text("保存释义") }
                Row {
                    Checkbox(checked = word.spellingDisabled, onCheckedChange = { vm.setSpellingDisabled(it) })
                    Text("不再进入拼写阶段", modifier = Modifier.padding(top = 12.dp))
                }
                Text("识别：遗忘 ${state.recognition?.lapseCount ?: 0} 次，连续正确 ${state.recognition?.successStreak ?: 0} 次")
                Text("拼写：遗忘 ${state.spelling?.lapseCount ?: 0} 次，连续正确 ${state.spelling?.successStreak ?: 0} 次")
                state.message?.let { Text(it) }
            }
        }
    }
}

@Composable
private fun ReviewSetupScreen(vm: AppViewModel, onBack: () -> Unit, onChoice: () -> Unit) {
    val state by vm.setup.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val selectedName = state.libraries.firstOrNull { it.id == state.selectedLibraryId }?.name ?: "请选择词库"
    Page("复习设置", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "本次学习词库",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { expanded = true },
                modifier = Modifier.actionButton(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text(selectedName) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.libraries.forEach { library ->
                    DropdownMenuItem(
                        text = { Text("${library.name}（${library.wordCount}）") },
                        onClick = { vm.selectReviewLibrary(library.id); expanded = false }
                    )
                }
            }
            Text("当前词库：${state.wordCount} 个单词", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.plannedCountInput,
                onValueChange = vm::updatePlannedCount,
                label = { Text("本次复习数量") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.startReview(onChoice) },
                enabled = !state.isStarting,
                modifier = Modifier.actionButton()
            ) { Text(if (state.isStarting) "正在准备…" else "开始复习") }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ChoiceScreen(
    vm: AppViewModel,
    tts: TtsManager,
    onSpelling: () -> Unit,
    onTtsSettings: () -> Unit
) {
    val state by vm.choice.collectAsStateWithLifecycle()
    val ttsStatus by tts.status.collectAsStateWithLifecycle()
    val ttsDiagnostics by tts.diagnostics.collectAsStateWithLifecycle()
    val question = state.currentQuestion
    var ttsMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(question?.word?.id) {
        ttsMessage = null
        question?.word?.displayTerm?.let { tts.speak(it) }
    }
    Page("选择题") { modifier ->
        if (question == null) {
            Text("题目生成失败，请确认词库至少有 3 个不同释义。", modifier)
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "第 ${state.roundIndex} 轮  ${state.currentIndex + 1} / ${state.totalInRound}",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    question.word.displayTerm,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                        ttsMessage = if (tts.speak(question.word.displayTerm)) null else ttsStatusMessage(ttsStatus, ttsDiagnostics)
                    },
                    modifier = Modifier.actionButton(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) { Text("发音") }
                OutlinedButton(onClick = onTtsSettings, modifier = Modifier.actionButton()) { Text("语音设置") }
                ttsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                question.options.forEach { option ->
                    OutlinedButton(
                        onClick = { vm.answerChoice(option) },
                        enabled = !state.isAnswered,
                        modifier = Modifier.actionButton(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (state.selectedOption == option) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            RadioButton(selected = state.selectedOption == option, onClick = null, enabled = false)
                            Text(option, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
                Feedback(isCorrect = state.isCorrect, correctAnswer = state.correctAnswer)
                state.persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { vm.nextChoice(onSpelling) },
                    enabled = state.isAnswered && !state.isPersisting,
                    modifier = Modifier.actionButton()
                ) { Text(if (state.isPersisting) "正在保存…" else "下一个") }
            }
        }
    }
}

@Composable
private fun SpellingScreen(
    vm: AppViewModel,
    tts: TtsManager,
    onDone: () -> Unit,
    onTtsSettings: () -> Unit
) {
    val state by vm.spelling.collectAsStateWithLifecycle()
    val ttsStatus by tts.status.collectAsStateWithLifecycle()
    val ttsDiagnostics by tts.diagnostics.collectAsStateWithLifecycle()
    val question = state.currentQuestion
    var ttsMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(question?.word?.id) {
        ttsMessage = null
        question?.word?.displayTerm?.let { tts.speak(it) }
    }
    Page("拼写题") { modifier ->
        if (question == null) {
            Column(
                modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("本次没有需要拼写的单词。")
                state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onDone,
                    enabled = !state.isPersisting,
                    modifier = Modifier.actionButton()
                ) { Text(if (state.isPersisting) "正在保存…" else "查看结果") }
            }
        } else {
            Column(
                modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "第 ${state.roundIndex} 轮  ${state.currentIndex + 1} / ${state.totalInRound}",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(question.word.meaningText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                    ttsMessage = if (tts.speak(question.word.displayTerm)) null else ttsStatusMessage(ttsStatus, ttsDiagnostics)
                    },
                    modifier = Modifier.actionButton(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) { Text("发音") }
                OutlinedButton(onClick = onTtsSettings, modifier = Modifier.actionButton()) { Text("语音设置") }
                ttsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = {},
                    enabled = !state.isSubmitted,
                    readOnly = true,
                    label = { Text("输入英文拼写") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!state.isSubmitted) {
                    Text(
                        "仅可使用下方应用内键盘（不区分大小写）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.runtime.key(question.word.id) {
                        InAppSpellingKeyboard(
                            input = state.input,
                            enabled = true,
                            onInputChange = vm::updateSpellingInput
                        )
                    }
                    Button(
                        onClick = vm::submitSpelling,
                        enabled = state.input.isNotBlank(),
                        modifier = Modifier.actionButton()
                    ) { Text("提交", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = vm::disableCurrentSpelling,
                        modifier = Modifier.actionButton()
                    ) {
                        Text("该单词不再拼写")
                    }
                } else {
                    Feedback(isCorrect = state.isCorrect, correctAnswer = state.correctTerm)
                    state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { vm.nextSpelling(onDone) },
                        enabled = !state.isPersisting,
                        modifier = Modifier.actionButton()
                    ) { Text(if (state.isPersisting) "正在保存…" else "下一题", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun InAppSpellingKeyboard(
    input: String,
    enabled: Boolean,
    onInputChange: (String) -> Unit
) {
    val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        letterRows.forEach { letters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                letters.forEach { letter ->
                    SpellingKey(
                        label = letter.uppercase(),
                        enabled = enabled,
                        onClick = { onInputChange(input + letter) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpellingKey("'", enabled, { onInputChange(input + "'") }, Modifier.weight(1f))
            SpellingKey("-", enabled, { onInputChange(input + "-") }, Modifier.weight(1f))
            SpellingKey(".", enabled, { onInputChange(input + ".") }, Modifier.weight(1f))
            SpellingKey("空格", enabled, { onInputChange(input + " ") }, Modifier.weight(2.5f))
            SpellingKey("退格", enabled && input.isNotEmpty(), { onInputChange(input.dropLast(1)) }, Modifier.weight(2f))
        }
        OutlinedButton(
            onClick = { onInputChange("") },
            enabled = enabled && input.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) { Text("清空") }
    }
}

@Composable
private fun SpellingKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun ResultScreen(vm: AppViewModel, onDone: () -> Unit) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    Page("复习结果") { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryLine("复习单词数", summary.actualCount)
            SummaryLine("选择题轮数", summary.choiceRounds)
            SummaryLine("选择题错误次数", summary.choiceMistakes)
            SummaryLine("拼写题轮数", summary.spellingRounds)
            SummaryLine("拼写错误次数", summary.spellingMistakes)
            Button(onClick = onDone, modifier = Modifier.actionButton()) { Text("完成") }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WordRow(word: LibraryWordEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(word.displayTerm, fontWeight = FontWeight.Bold)
                if (word.spellingDisabled) Text("不拼写", style = MaterialTheme.typography.labelMedium)
            }
            Text(word.meaningText)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Feedback(isCorrect: Boolean?, correctAnswer: String?) {
    if (isCorrect == null) return
    HorizontalDivider()
    Text(if (isCorrect) "正确" else "错误", color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    correctAnswer?.let { Text("正确答案：$it") }
}

@Composable
private fun SummaryLine(label: String, value: Int) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TtsSettingsScreen(
    status: TtsStatus,
    diagnostics: TtsDiagnostics,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstallData: () -> Unit,
    onInstallEngine: () -> Unit
) {
    Page("语音设置", onBack) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val ready = status == TtsStatus.READY || status == TtsStatus.SPEAKING
            Text(
                if (ready) "语音服务可用" else "语音服务需要配置",
                style = MaterialTheme.typography.titleMedium,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            if (!ready) {
                val message = if (status == TtsStatus.NO_ENGINE) {
                    ttsStatusMessage(status, diagnostics)
                } else {
                    ttsErrorMessage(diagnostics.lastErrorCode) ?: ttsStatusMessage(status, diagnostics)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            diagnostics.description().takeIf { it.isNotBlank() }?.let { Text("检测信息：$it") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = onRetry, modifier = Modifier.actionButton()) { Text("重新检测") }
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.actionButton(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("系统语音设置") }
            if (status == TtsStatus.NO_ENGINE) {
                Button(onClick = onInstallEngine, modifier = Modifier.actionButton()) { Text("安装 TTS 引擎") }
            }
            if (status == TtsStatus.LANGUAGE_UNAVAILABLE || status == TtsStatus.FAILED ||
                diagnostics.lastErrorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET
            ) {
                OutlinedButton(onClick = onInstallData, modifier = Modifier.actionButton()) { Text("安装语音数据") }
            }
        }
    }
}

private fun ttsStatusMessage(status: TtsStatus, diagnostics: TtsDiagnostics = TtsDiagnostics()): String? =
    when (status) {
        TtsStatus.INITIALIZING -> "语音正在初始化，请稍后再试。"
        TtsStatus.READY -> null
        TtsStatus.SPEAKING -> null
        TtsStatus.NO_ENGINE -> if (diagnostics.installedEnginePackages.isEmpty()) {
            "手机上没有检测到 TTS 引擎。请先安装文字转语音引擎，再返回应用重新检测。"
        } else {
            "检测到 TTS 引擎已安装但未启用：${diagnostics.installedEnginePackages.joinToString()}。请在系统设置中启用后重新检测。"
        }
        TtsStatus.LANGUAGE_UNAVAILABLE -> "手机未安装或未启用英文语音，请安装英文语音包后重新检测。"
        TtsStatus.SYNTHESIS_FAILED -> "语音合成失败，已尝试恢复；请重新检测或检查系统语音设置。"
        TtsStatus.FAILED -> "语音初始化失败，请在系统语音设置中选择可用引擎后重新检测。"
    }
