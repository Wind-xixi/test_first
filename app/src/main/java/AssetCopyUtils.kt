package com.example.myapppy

import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
    return try {
        val files = assetManager.list(fromAssetPath) ?: return false
        val targetDir = File(toPath)
        if (!targetDir.exists()) targetDir.mkdirs()

        for (file in files) {
            val assetPath = "$fromAssetPath/$file"
            val outPath = "$toPath/$file"
            if ((assetManager.list(assetPath) ?: emptyArray()).isNotEmpty()) {
                // 是目录，递归复制
                copyAssetFolder(assetManager, assetPath, outPath)
            } else {
                // 是文件，直接复制
                copyAssetFile(assetManager, assetPath, outPath)
            }
        }
        true
    } catch (e: IOException) {
        e.printStackTrace()
        false
    }
}

fun copyAssetFile(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
    val inputStream: InputStream = assetManager.open(fromAssetPath)
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
