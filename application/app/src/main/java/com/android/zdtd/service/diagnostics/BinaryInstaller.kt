package com.android.zdtd.service.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal object BinaryInstaller {
    fun preferredAbi(): String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }

    fun install(context: Context, assetPath: String, binaryName: String): File {
        val parent = File(context.noBackupFilesDir, "bin")
        val target = File(parent, binaryName)
        if (!parent.exists() && !parent.mkdirs()) error("Unable to create bin directory: $parent")

        val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
        val assetSha = sha256(assetBytes)
        if (!target.exists() || sha256OrNull(target) != assetSha) {
            val tmp = File(parent, "$binaryName.tmp")
            tmp.outputStream().use { it.write(assetBytes) }
            tmp.setReadable(true, true); tmp.setWritable(true, true); tmp.setExecutable(true, true)
            if (target.exists() && !target.delete()) { tmp.delete(); error("Unable to replace $binaryName") }
            if (!tmp.renameTo(target)) { tmp.delete(); error("Unable to install $binaryName") }
        }
        target.setReadable(true, true); target.setWritable(true, true); target.setExecutable(true, true)
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256OrNull(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n <= 0) break; digest.update(buf, 0, n) }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
