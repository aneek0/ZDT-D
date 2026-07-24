package com.android.zdtd.service.diagnostics.nfqws

import android.content.Context
import com.android.zdtd.service.diagnostics.BinaryInstaller
import java.io.File

class NfqwsTesterBinary(private val context: Context) {
    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "bin"), BINARY_NAME)

    fun ensureInstalled(): File = BinaryInstaller.install(
        context,
        "nfqws-tester/${BinaryInstaller.preferredAbi()}/nfqws_tester",
        BINARY_NAME,
    )

    companion object {
        const val BINARY_NAME = "nfqws_tester"
    }
}
