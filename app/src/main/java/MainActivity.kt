package com.example.myapppy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

// App内部使用的数据结构
data class StudentSelfEvalResult(
    val workLogic: Float,
    val workInnovation: Float,
    val workPracticality: Float,
    val workCompleteness: Float,
    val presentationFluency: Float,
    val totalScore: Int
)

data class TeacherEvalResult(
    val grade: String,
    val summary: String
)


class MainActivity : ComponentActivity() {

    private val sampleRate = 16000
    private lateinit var model: Model
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false

    private var showSaveDialog by mutableStateOf(false)

    private var showFileListDialog by mutableStateOf(false)
    private var savedFiles by mutableStateOf(listOf<File>())
    private var selectedFileContent by mutableStateOf<String?>(null)
    private var selectedFileName by mutableStateOf<String?>(null)

    private var currentScreen by mutableStateOf("main")
    private var evaluationMode by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        "main" -> MainScreen()
                        "teacher" -> EvaluationScreen(mode = "teacher")
                        "student" -> EvaluationScreen(mode = "student")
                    }

                    if (showFileListDialog) {
                        FileListDialog()
                    }

                    if (selectedFileContent != null) {
                        FileContentDialog()
                    }
                }
            }
        }
    }

    @Composable
    private fun MainScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("课堂评价系统", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(60.dp))

            Button(
                onClick = {
                    evaluationMode = "teacher"
                    currentScreen = "teacher"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("教师评价", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    evaluationMode = "student"
                    currentScreen = "student"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("学生自评", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    val files = listSavedFiles()
                    if (files.isEmpty()) {
                        Toast.makeText(this@MainActivity, "没有找到任何保存的文件。", Toast.LENGTH_SHORT).show()
                    } else {
                        savedFiles = files
                        showFileListDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("查看已保存的评价", fontSize = 18.sp)
            }
        }
    }

    @Composable
    private fun EvaluationScreen(mode: String) {
        var recognizedText by remember { mutableStateOf("等待模型加载...") }
        var recordingState by remember { mutableStateOf(false) }
        var modelLoaded by remember { mutableStateOf(false) }
        var fullText by remember { mutableStateOf("") }

        var studentResult by remember { mutableStateOf<StudentSelfEvalResult?>(null) }
        var teacherResult by remember { mutableStateOf<TeacherEvalResult?>(null) }
        var isLoadingApi by remember { mutableStateOf(false) }

        LaunchedEffect(true) {
            try {
                val modelName = "vosk-model-small-cn-0.22"
                val modelDir = File(filesDir, modelName)
                if (!modelDir.exists()) {
                    copyAssetFolder(assets, modelName, modelDir.absolutePath)
                }
                model = Model(modelDir.absolutePath)
                recognizedText = "模型加载成功，请开始"
                modelLoaded = true
            } catch (e: Exception) {
                recognizedText = "模型加载失败: ${e.message}"
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (mode == "teacher") "教师评价模式" else "学生自评模式",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text("当前状态：$recognizedText")

            Button(onClick = {
                if (!modelLoaded) {
                    Toast.makeText(this@MainActivity, "模型未加载完成", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                } else {
                    if (!recordingState) {
                        recordingState = true
                        fullText = ""
                        studentResult = null
                        teacherResult = null
                        recognizedText = "录音中..."
                        startRecording { result ->
                            val regex = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                            val match = regex.find(result)
                            val spokenText = match?.groupValues?.get(1) ?: ""
                            if (spokenText.isNotBlank()) {
                                fullText += "$spokenText "
                                recognizedText = spokenText
                            }
                        }
                    } else {
                        stopRecording()
                        recordingState = false
                        recognizedText = "录音结束，准备保存和分析"
                    }
                }
            }) {
                Text(if (recordingState) "停止录音" else "开始录音")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("识别总文本：")
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .height(150.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text(
                    text = if (fullText.isNotBlank()) fullText else "暂无识别文本",
                    fontSize = 16.sp
                )
            }

            if (isLoadingApi) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("正在分析中...", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            if (mode == "student" && studentResult != null) {
                studentResult?.let { result ->
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("综合得分: ${result.totalScore}", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    RadarChart(
                        data = listOf(
                            result.workLogic,
                            result.workInnovation,
                            result.workPracticality,
                            result.workCompleteness,
                            result.presentationFluency
                        ),
                        labels = listOf("逻辑性", "创新性", "实用性", "完成度", "流畅性")
                    )
                }
            }

            if (mode == "teacher" && teacherResult != null) {
                teacherResult?.let { result ->
                    Spacer(modifier = Modifier.height(20.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("评价等级: ${result.grade}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("评价总结: ${result.summary}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { currentScreen = "main" }) {
                Text("返回主菜单")
            }
        }

        if (showSaveDialog) {
            var fileName by remember { mutableStateOf(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + "_班级_姓名") }

            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("保存并分析") },
                text = {
                    Column {
                        Text("请输入文件名（包含班级和姓名）")
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSaveDialog = false
                        isLoadingApi = true
                        if (evaluationMode == "teacher") {
                            callTeacherApi(
                                text = fullText,
                                onResult = { result ->
                                    isLoadingApi = false
                                    teacherResult = result
                                    val contentToSave = "$fullText\n\n--- 教师评价结果 ---\n等级: ${result.grade}\n总结: ${result.summary}"
                                    saveTextToFile(fileName, contentToSave)
                                },
                                onError = { errorMessage ->
                                    isLoadingApi = false
                                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            // 学生自评恢复使用模拟数据
                            callStudentApi { result ->
                                isLoadingApi = false
                                studentResult = result
                                val contentToSave = "$fullText\n\n--- 学生自评结果 ---\n分数: ${result.totalScore}\n逻辑:${result.workLogic}, 创新:${result.workInnovation}, 实用:${result.workPracticality}, 完成:${result.workCompleteness}, 流畅:${result.presentationFluency}"
                                saveTextToFile(fileName, contentToSave)
                            }
                        }
                    }) {
                        Text("确认并分析")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }

    @Composable
    private fun FileListDialog() {
        AlertDialog(
            onDismissRequest = { showFileListDialog = false },
            title = { Text("选择要查看的文件") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    savedFiles.forEach { file ->
                        TextButton(onClick = {
                            selectedFileContent = readTextFromFile(file)
                            selectedFileName = file.nameWithoutExtension
                            showFileListDialog = false
                        }) {
                            Text(file.nameWithoutExtension)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFileListDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    @Composable
    private fun FileContentDialog() {
        AlertDialog(
            onDismissRequest = {
                selectedFileContent = null
                selectedFileName = null
            },
            title = { Text(selectedFileName ?: "文件内容") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(selectedFileContent ?: "无法读取文件内容。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedFileContent = null
                    selectedFileName = null
                }) {
                    Text("关闭")
                }
            }
        )
    }

    private fun callTeacherApi(text: String, onResult: (TeacherEvalResult) -> Unit, onError: (String) -> Unit) {
        if (text.isBlank()) {
            onError("无法分析空文本")
            return
        }
        lifecycleScope.launch {
            try {
                val requestBody = EvaluationRequestBody(text = text)
                val response = ApiClient.instance.evaluateTeacher(requestBody)
                val result = TeacherEvalResult(
                    grade = response.grade,
                    summary = response.summary
                )
                onResult(result)
            } catch (e: Exception) {
                e.printStackTrace()
                onError("教师评价API调用失败: ${e.message}")
            }
        }
    }

    // 学生自评函数恢复为模拟数据版本
    private fun callStudentApi(onResult: (StudentSelfEvalResult) -> Unit) {
        lifecycleScope.launch {
            // 修正点：删除此处的 isLoadingApi = true
            delay(1500) // 模拟网络延迟
            val mockResult = StudentSelfEvalResult(
                workLogic = 4.5f,
                workInnovation = 3.0f,
                workPracticality = 4.0f,
                workCompleteness = 5.0f,
                presentationFluency = 3.5f,
                totalScore = 85
            )
            onResult(mockResult)
        }
    }

    private fun startRecording(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未获得麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        recognizer = Recognizer(model, sampleRate.toFloat())
        isRecording = true
        recordingThread = Thread {
            val byteBuffer = ByteArray(bufferSize)
            recorder?.startRecording()
            while (isRecording) {
                val read = recorder?.read(byteBuffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    if (recognizer?.acceptWaveForm(byteBuffer, read) == true) {
                        val res = recognizer?.result ?: ""
                        runOnUiThread { onResult(res) }
                    } else {
                        val partial = recognizer?.partialResult ?: ""
                        runOnUiThread { onResult(partial) }
                    }
                }
            }
            recorder?.stop()
            recorder?.release()
            recorder = null
            val finalResult = recognizer?.finalResult ?: ""
            runOnUiThread { onResult(finalResult) }
            recognizer = null
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        showSaveDialog = true
    }

    private fun saveTextToFile(fileName: String, content: String) {
        val targetDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "doct")
        if (!targetDir.exists()) targetDir.mkdirs()
        val file = File(targetDir, "$fileName.txt")
        try {
            file.writeText(content)
            Toast.makeText(this, "文件已保存并分析完成:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listSavedFiles(): List<File> {
        val targetDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "doct")
        if (!targetDir.exists()) return emptyList()
        return targetDir.listFiles { _, name -> name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun readTextFromFile(file: File): String {
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "读取文件失败: ${e.message}"
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val files = assetManager.list(fromAssetPath) ?: return false
            val targetDir = File(toPath)
            if (!targetDir.exists()) targetDir.mkdirs()
            for (file in files) {
                val assetPath = "$fromAssetPath/$file"
                val outPath = "$toPath/$file"
                if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                    copyAssetFolder(assetManager, assetPath, outPath)
                } else {
                    copyAssetFile(assetManager, assetPath, outPath)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String) {
        val inputStream = assetManager.open(fromAssetPath)
        val outFile = File(toPath)
        val outputStream = outFile.outputStream()
        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
        inputStream.close()
        outputStream.flush()
        outputStream.close()
    }

    @Composable
    fun RadarChart(
        data: List<Float>,
        labels: List<String>,
        modifier: Modifier = Modifier,
        maxDataValue: Float = 5.0f
    ) {
        val textSize = with(LocalDensity.current) { 12.sp.toPx() }
        val dataColor = MaterialTheme.colorScheme.primary

        Canvas(
            modifier = modifier
                .aspectRatio(1f)
                .padding(20.dp)
        ) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = size.width / 2 * 0.8f
            val angleStep = 2 * Math.PI / labels.size
            val gridColor = Color.Gray

            (1..5).forEach { i ->
                val r = radius * i / 5
                val path = Path().apply {
                    moveTo(centerX + r, centerY)
                    for (j in 1..labels.size) {
                        val angle = j * angleStep
                        lineTo(
                            centerX + (r * cos(angle)).toFloat(),
                            centerY + (r * sin(angle)).toFloat()
                        )
                    }
                    close()
                }
                drawPath(path, color = gridColor, style = Stroke(width = 1.dp.toPx()))
            }
            for (i in labels.indices) {
                val angle = i * angleStep
                drawLine(
                    gridColor,
                    Offset(centerX, centerY),
                    Offset(
                        centerX + (radius * cos(angle)).toFloat(),
                        centerY + (radius * sin(angle)).toFloat()
                    )
                )
            }

            val dataPath = Path()
            data.forEachIndexed { i, value ->
                val r = radius * (value / maxDataValue).coerceIn(0f, 1f)
                val angle = i * angleStep
                val x = centerX + (r * cos(angle)).toFloat()
                val y = centerY + (r * sin(angle)).toFloat()
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            dataPath.close()
            drawPath(dataPath, color = dataColor, alpha = 0.4f)
            drawPath(dataPath, color = dataColor, style = Stroke(width = 2.dp.toPx()))

            val labelRadius = size.width / 2 * 0.95f
            val textPaint = Paint().apply {
                this.color = Color.Black.toArgb()
                this.textSize = textSize
                this.textAlign = Paint.Align.CENTER
            }
            labels.forEachIndexed { i, label ->
                val angle = i * angleStep
                val x = centerX + (labelRadius * cos(angle)).toFloat()
                val y = centerY + (labelRadius * sin(angle)).toFloat() + (textSize / 3)
                drawContext.canvas.nativeCanvas.drawText(label, x, y, textPaint)
            }
        }
    }
}
