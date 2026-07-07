package com.example.wordreview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.domain.ReviewSummary
import com.example.wordreview.tts.TtsManager
import com.example.wordreview.tts.TtsStatus
import com.example.wordreview.ui.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WordReviewApp()
                }
            }
        }
    }
}

private object Routes {
    const val Home = "home"
    const val Import = "import"
    const val Library = "library"
    const val Detail = "detail/{wordId}"
    const val Setup = "setup"
    const val Choice = "choice"
    const val Spelling = "spelling"
    const val Result = "result"
    fun detail(wordId: Long) = "detail/$wordId"
}

private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@Composable
private fun WordReviewApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val tts = remember { TtsManager(context) }
    val ttsStatus by tts.status.collectAsState()
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val ttsSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tts.reinitialize()
    }
    val ttsInstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tts.reinitialize()
    }
    val openTtsSettings = {
        try {
            ttsSettingsLauncher.launch(Intent(ACTION_TTS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "无法打开系统语音设置。", Toast.LENGTH_SHORT).show()
        }
    }
    val installTtsData = {
        try {
            ttsInstallLauncher.launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "无法打开语音数据安装页面。", Toast.LENGTH_SHORT).show()
        }
    }

    NavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                vm,
                onImport = { nav.navigate(Routes.Import) },
                onLibrary = { nav.navigate(Routes.Library) },
                onReview = { vm.openReview { nav.navigate(Routes.Setup) } },
                onContinueChoice = { nav.navigate(Routes.Choice) },
                onContinueSpelling = { nav.navigate(Routes.Spelling) },
                onRestartChoice = { nav.navigate(Routes.Choice) },
                ttsStatus = ttsStatus,
                onRetryTts = tts::reinitialize,
                onOpenTtsSettings = openTtsSettings,
                onInstallTtsData = installTtsData
            )
        }
        composable(Routes.Import) {
            ImportScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.Library) {
            LibraryScreen(vm, onBack = { nav.popBackStack() }, onDetail = { nav.navigate(Routes.detail(it)) })
        }
        composable(
            Routes.Detail,
            arguments = listOf(navArgument("wordId") { type = NavType.LongType })
        ) { entry ->
            val wordId = entry.arguments?.getLong("wordId") ?: 0L
            LaunchedEffect(wordId) { vm.loadDetail(wordId) }
            DetailScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.Setup) {
            ReviewSetupScreen(vm, onBack = { nav.popBackStack() }, onChoice = { nav.navigate(Routes.Choice) })
        }
        composable(Routes.Choice) {
            ChoiceScreen(
                vm,
                tts,
                onSpelling = { nav.navigate(Routes.Spelling) },
                onRetryTts = tts::reinitialize,
                onOpenTtsSettings = openTtsSettings,
                onInstallTtsData = installTtsData
            )
        }
        composable(Routes.Spelling) {
            SpellingScreen(
                vm,
                tts,
                onDone = { nav.navigate(Routes.Result) },
                onRetryTts = tts::reinitialize,
                onOpenTtsSettings = openTtsSettings,
                onInstallTtsData = installTtsData
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
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding -> content(Modifier.padding(padding).padding(16.dp)) }
}

@Composable
private fun HomeScreen(
    vm: AppViewModel,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onReview: () -> Unit,
    onContinueChoice: () -> Unit,
    onContinueSpelling: () -> Unit,
    onRestartChoice: () -> Unit,
    ttsStatus: TtsStatus,
    onRetryTts: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onInstallTtsData: () -> Unit
) {
    val state by vm.home.collectAsState()
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
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("总单词数", state.wordCount.toString())
            StatCard("最近导入", state.latestImport?.let { "${it.newCount} 新增 / ${it.existingCount} 已有" } ?: "暂无")
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("开始复习") }
            state.reviewErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onOpenTtsSettings, modifier = Modifier.fillMaxWidth()) { Text("配置语音模型") }
            TtsStatusPanel(ttsStatus, onRetryTts, onOpenTtsSettings, onInstallTtsData)
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("导入 CSV") }
            OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) { Text("单词库") }
        }
    }
}

@Composable
private fun ImportScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.importState.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    Page("导入 CSV", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { launcher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }, enabled = !state.isImporting) {
                Text(if (state.isImporting) "正在导入" else "选择文件")
            }
            state.result?.let {
                Text("导入成功", fontWeight = FontWeight.Bold)
                Text("总行数：${it.totalRows}")
                Text("新增单词：${it.newCount}")
                Text("已有单词：${it.existingCount}")
                Text("更新释义：${it.updatedMeaningCount}")
                Text("失败行数：${it.failedRowCount}")
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun LibraryScreen(vm: AppViewModel, onBack: () -> Unit, onDetail: (Long) -> Unit) {
    val state by vm.library.collectAsState()
    Page("单词库", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::updateSearch,
                label = { Text("搜索英文或中文释义") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.words, key = { it.id }) { word ->
                    WordRow(word, onClick = { onDetail(word.id) })
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.detail.collectAsState()
    val word = state.word
    Page("单词详情", onBack) { modifier ->
        if (word == null) {
            Text("单词不存在")
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(word.displayTerm, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.editText,
                    onValueChange = vm::updateDetailEdit,
                    label = { Text("释义") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = vm::saveMeaning) { Text("保存释义") }
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
    val state by vm.setup.collectAsState()
    Page("复习设置", onBack) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前词库：${state.wordCount} 个单词")
            OutlinedTextField(
                value = state.plannedCountInput,
                onValueChange = vm::updatePlannedCount,
                label = { Text("本次复习数量") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.startReview(onChoice) }, modifier = Modifier.fillMaxWidth()) { Text("开始复习") }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ChoiceScreen(
    vm: AppViewModel,
    tts: TtsManager,
    onSpelling: () -> Unit,
    onRetryTts: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onInstallTtsData: () -> Unit
) {
    val state by vm.choice.collectAsState()
    val ttsStatus by tts.status.collectAsState()
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
                Text("第 ${state.roundIndex} 轮  ${state.currentIndex + 1} / ${state.totalInRound}")
                Text(question.word.displayTerm, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    ttsMessage = if (tts.speak(question.word.displayTerm)) null else ttsStatusMessage(ttsStatus)
                }) { Text("发音") }
                TtsStatusPanel(ttsStatus, onRetryTts, onOpenTtsSettings, onInstallTtsData)
                ttsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                question.options.forEach { option ->
                    OutlinedButton(
                        onClick = { vm.answerChoice(option) },
                        enabled = !state.isAnswered,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            RadioButton(selected = state.selectedOption == option, onClick = null, enabled = false)
                            Text(option, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
                Feedback(isCorrect = state.isCorrect, correctAnswer = state.correctAnswer)
                Button(onClick = { vm.nextChoice(onSpelling) }, enabled = state.isAnswered, modifier = Modifier.fillMaxWidth()) { Text("下一个") }
            }
        }
    }
}

@Composable
private fun SpellingScreen(
    vm: AppViewModel,
    tts: TtsManager,
    onDone: () -> Unit,
    onRetryTts: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onInstallTtsData: () -> Unit
) {
    val state by vm.spelling.collectAsState()
    val ttsStatus by tts.status.collectAsState()
    val question = state.currentQuestion
    var ttsMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(question?.word?.id) {
        ttsMessage = null
        question?.word?.displayTerm?.let { tts.speak(it) }
    }
    Page("拼写题") { modifier ->
        if (question == null) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("本次没有需要拼写的单词。")
                Button(onClick = onDone) { Text("查看结果") }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("第 ${state.roundIndex} 轮  ${state.currentIndex + 1} / ${state.totalInRound}")
                Text(question.word.meaningText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    ttsMessage = if (tts.speak(question.word.displayTerm)) null else ttsStatusMessage(ttsStatus)
                }) { Text("发音") }
                TtsStatusPanel(ttsStatus, onRetryTts, onOpenTtsSettings, onInstallTtsData)
                ttsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::updateSpellingInput,
                    enabled = !state.isSubmitted,
                    label = { Text("输入英文拼写") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::submitSpelling, enabled = !state.isSubmitted && state.input.isNotBlank()) { Text("提交") }
                    OutlinedButton(onClick = vm::disableCurrentSpelling) { Text("该单词不再拼写") }
                }
                Feedback(isCorrect = state.isCorrect, correctAnswer = state.correctTerm)
                Button(onClick = { vm.nextSpelling(onDone) }, enabled = state.isSubmitted, modifier = Modifier.fillMaxWidth()) { Text("下一个") }
            }
        }
    }
}

@Composable
private fun ResultScreen(vm: AppViewModel, onDone: () -> Unit) {
    val summary by vm.summary.collectAsState()
    Page("复习结果") { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本次复习完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SummaryLine("复习单词数", summary.actualCount)
            SummaryLine("选择题轮数", summary.choiceRounds)
            SummaryLine("选择题错误次数", summary.choiceMistakes)
            SummaryLine("拼写题轮数", summary.spellingRounds)
            SummaryLine("拼写错误次数", summary.spellingMistakes)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WordRow(word: WordEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(word.displayTerm, fontWeight = FontWeight.Bold)
                if (word.spellingDisabled) Text("不拼写", style = MaterialTheme.typography.labelMedium)
            }
            Text(word.meaningText)
        }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TtsStatusPanel(
    status: TtsStatus,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstallData: () -> Unit
) {
    val message = ttsStatusMessage(status) ?: return
    Text(message, color = MaterialTheme.colorScheme.error)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新检测") }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("系统语音设置") }
        if (status == TtsStatus.LANGUAGE_UNAVAILABLE || status == TtsStatus.FAILED) {
            OutlinedButton(onClick = onInstallData, modifier = Modifier.fillMaxWidth()) {
                Text("安装语音数据")
            }
        }
    }
}

private fun ttsStatusMessage(status: TtsStatus): String? =
    when (status) {
        TtsStatus.INITIALIZING -> "语音正在初始化，请稍后再试。"
        TtsStatus.READY -> null
        TtsStatus.LANGUAGE_UNAVAILABLE -> "手机未安装或未启用英文语音，请安装英文语音包后重新检测。"
        TtsStatus.FAILED -> "语音初始化失败，请在系统语音设置中选择可用引擎后重新检测。"
    }
