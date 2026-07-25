package com.android.zdtd.service

data class BackupItem(
  val name: String,
  val sizeBytes: Long = 0L,
  val createdAtText: String = "",
)

data class BackupUiState(
  val loading: Boolean = false,
  val items: List<BackupItem> = emptyList(),
  val error: String? = null,

  // Progress dialog (create / restore / import / delete)
  val progressVisible: Boolean = false,
  val progressTitle: String = "",
  val progressText: String = "",
  val progressPercent: Int = 0,
  val progressFinished: Boolean = false,
  val progressError: String? = null,

  // Version mismatch: allow user to force restore (advanced).
  val forceRestoreAvailable: Boolean = false,
  val forceRestoreName: String? = null,

  // Backup file opened externally by Android file manager (.zdtb ACTION_VIEW).
  val externalRestorePromptVisible: Boolean = false,
  val externalRestoreName: String? = null,
  val externalRestoreDisplayName: String = "",
)

sealed class BackupEvent {
  object RequestImport : BackupEvent()
  data class ShareFile(val filePath: String, val mime: String) : BackupEvent()
}
