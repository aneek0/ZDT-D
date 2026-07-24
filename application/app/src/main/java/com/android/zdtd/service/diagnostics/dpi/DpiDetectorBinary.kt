package com.android.zdtd.service.diagnostics.dpi

import android.content.Context
import com.android.zdtd.service.diagnostics.BinaryInstaller
import java.io.File

class DpiDetectorBinary(private val context: Context) {
    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "bin"), BINARY_NAME)

    fun ensureInstalled(): File = BinaryInstaller.install(
        context,
        "dpi-detector/${BinaryInstaller.preferredAbi()}/dpi-detector",
        BINARY_NAME,
    )

    companion object {
        const val BINARY_NAME = "dpi-detector"
    }
}
