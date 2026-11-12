package com.android.zdtd.service.diagnostics.blockcheck

import android.content.Context
import com.android.zdtd.service.diagnostics.BinaryInstaller
import java.io.File

/**
 * blockcheckw binary for the parallel search phase of `auto` scans.
 * Missing asset (older APK) degrades to the legacy sequential scan: the
 * tester treats an unreadable --blockcheckw path as "phase skipped".
 */
class BlockcheckwBinary(private val context: Context) {
    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "bin"), BINARY_NAME)

    fun ensureInstalled(): File = BinaryInstaller.install(
        context,
        "blockcheckw/${BinaryInstaller.preferredAbi()}/blockcheckw",
        BINARY_NAME,
    )

    companion object {
        const val BINARY_NAME = "blockcheckw"
    }
}
