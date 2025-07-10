package com.example.myapppy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp



class MainActivity : ComponentActivity() {

    private val sampleRate = 16000
    private lateinit var model: Model
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    //在 setContent {} 外层或 onCreate 中新增以下变量用于控制弹窗：
    private var showSaveDialog by mutableStateOf(false)
    private var suggestedFileName by mutableStateOf("")

    private var showFileListDialog by mutableStateOf(false)
    private var savedFiles by mutableStateOf(listOf<File>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var recognizedText by remember { mutableStateOf("等待模型加载...") }
            var recordingState by remember { mutableStateOf(false) }
            var modelLoaded by remember { mutableStateOf(false) }
            var fullText by remember { mutableStateOf("") }  // 新增：完整文本记录
            var selectedFileContent by remember { mutableStateOf<String?>(null) }
            var selectedFileName by remember { mutableStateOf<String?>(null) }

            // 模型加载线程
            LaunchedEffect(true) {
                try {
                    val modelName = "vosk-model-small-cn-0.22"
                    val modelDir = File(filesDir, modelName)

                    if (!modelDir.exists()) {
                        copyAssetFolder(assets, modelName, modelDir.absolutePath)
                    }

                    model = Model(modelDir.absolutePath)
                    recognizedText = "模型加载成功，准备识别"
                    modelLoaded = true
                } catch (e: Exception) {
                    recognizedText = "模型加载失败: ${e.message}"
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("语音转文本", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(20.dp))
                Text("当前状态：$recognizedText")

                // 开始/停止按钮
                Button(
                    onClick = {
                        if (!modelLoaded) {
                            Toast.makeText(this@MainActivity, "模型未加载完成", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                1001
                            )
                        } else {
                            if (!recordingState) {
                                recordingState = true
                                recognizedText = "录音中..."
                                startRecording { result ->
                                    recognizedText = result
                                    // 提取 "text" 字段的内容（跳过空 partial）
                                    val regex = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                                    val match = regex.find(result)
                                    val spokenText = match?.groupValues?.get(1) ?: ""
                                    // 只累加带有内容的 JSON 且不是仅 partial 的
                                    if (result.contains("text") && !result.contains("\"text\" : \"\"")) {
                                        fullText += "$spokenText "
                                    }
                                }

                            } else {
                                stopRecording()
                                recordingState = false
                            }
                        }
                    }
                ) {
                    Text(if (recordingState) "停止录音" else "开始录音")
                }

                // ✅ 这部分是实时文本和清除按钮，已经移出 Button 块
                Spacer(modifier = Modifier.height(16.dp))
                Text("识别文本（实时显示）：")
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (fullText.isNotBlank()) fullText else "暂无识别文本",
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = {
                    val files = listSavedFiles()
                    if (files.isEmpty()) {
                        Toast.makeText(this@MainActivity, "没有找到任何保存的文件。", Toast.LENGTH_SHORT).show()
                    } else {
                        savedFiles = files
                        showFileListDialog = true
                    }
                }) {
                    Text("查看已保存的文件")
                }


                Button(onClick = { fullText = "" }) {
                    Text("清除文本")
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text("识别文本：$recognizedText")
            }
            //在 setContent {} 的 Column {} 外层或末尾添加：
            if (showSaveDialog) {
                var fileName by remember { mutableStateOf(suggestedFileName) }

                AlertDialog(
                    onDismissRequest = { showSaveDialog = false },
                    title = { Text("保存录音") },
                    text = {
                        Column {
                            Text("请输入文件名")
                            OutlinedTextField(
                                value = fileName,
                                onValueChange = { fileName = it },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            saveTextToFile(fileName, fullText)
                            Toast.makeText(this@MainActivity, "文件已保存", Toast.LENGTH_SHORT).show()
                            showSaveDialog = false
                        }) {
                            Text("保存")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveDialog = false }) {
                            Text("取消")
                        }
                    }
                )

            }
            if (showFileListDialog) {
                AlertDialog(
                    onDismissRequest = { showFileListDialog = false },
                    title = { Text("选择要查看的文件") },
                    text = {
                        Column {
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


            if (selectedFileContent != null && selectedFileName != null) {
                AlertDialog(
                    onDismissRequest = {
                        selectedFileContent = null
                        selectedFileName = null
                    },
                    title = { Text("文件：${selectedFileName}") },
                    text = {
                        Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(selectedFileContent ?: "")
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

        }

    }

    private fun startRecording(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "未获得麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
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

        val time = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
        suggestedFileName = "${time}_班级_姓名" // 修改为你真实的提示格式
        showSaveDialog = true
    }

    private fun copyAssetFolderToCache(context: Context, folderName: String): File {
        val outDir = File(context.cacheDir, folderName)
        if (!outDir.exists()) {
            outDir.mkdirs()
            val assetManager = context.assets
            val files = assetManager.list(folderName)
            files?.forEach { filename ->
                val inStream = assetManager.open("$folderName/$filename")
                val outFile = File(outDir, filename)
                val outStream = FileOutputStream(outFile)
                inStream.copyTo(outStream)
                inStream.close()
                outStream.close()
            }
        }
        return outDir
    }
    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val files = assetManager.list(fromAssetPath) ?: return false
            val targetDir = File(toPath)
            if (!targetDir.exists()) targetDir.mkdirs()

            for (file in files) {
                val assetPath = "$fromAssetPath/$file"
                val outPath = "$toPath/$file"
                if ((assetManager.list(assetPath) ?: emptyArray()).isNotEmpty()) {
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
        val outputStream = FileOutputStream(outFile)

        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }

        inputStream.close()
        outputStream.flush()
        outputStream.close()
    }
    //在 MainActivity 中添加一个函数，用于将识别的文本保存到本地文件
    private fun saveTextToFile(fileName: String, content: String) {
        try {
            val targetDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "doct")
            if (!targetDir.exists()) targetDir.mkdirs()

            val file = File(targetDir, "$fileName.txt")
            file.writeText(content)

            Toast.makeText(this, "文件已保存至: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }




    //添加一个函数读取指定文件名的文本
    private fun readTextFromFile(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }

    //你可以列出 filesDir 中所有 .txt 文件
    private fun listSavedFiles(): List<File> {
        val targetDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "doct")
        if (!targetDir.exists()) return emptyList()
        return targetDir.listFiles { _, name -> name.endsWith(".txt") }?.toList() ?: emptyList()
    }





}
