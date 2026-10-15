package com.android.zdtd.service.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.MainViewModel.ZapretPreset
import com.android.zdtd.service.api.ApiModels
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.launch

private const val HOSTLIST_PREFIX = "/data/adb/modules/ZDT-D/strategic/list/"
// Marker the daemon writes at the top of a config to remember which built-in
// preset was applied. Reading it back lets us keep showing the preset name even
// after the user edits the hostlist selection (which changes the file hash).
private const val VARIANT_MARKER = "# @zdtd-variant "

/** Returns the applied preset file name stored in a config via [VARIANT_MARKER]. */
private fun readVariantMarker(text: String): String? {
  for (line in text.lineSequence()) {
    val trimmed = line.trimStart()
    if (trimmed.startsWith(VARIANT_MARKER)) {
      val name = trimmed.removePrefix(VARIANT_MARKER).trim()
      if (name.isNotEmpty()) return name
    }
  }
  return null
}

// IP-set selection files live in the same strategic/list/ directory as the
// hostlists; the daemon injects them as --ipset=/.../list/<name>. Every ipset
// file is named with the "ipset" substring, so classify by name rather than a
// hard-coded allowlist (which drifted out of sync with the actual files shipped
// in strategic/list/ and caused ipsets to show up in the hostlist chooser).
private fun isIpSetFile(name: String): Boolean = name.lowercase(Locale.ROOT).contains("ipset")

private data class StrategySelection(
  val hostlists: List<String>,
  val excludeHostlists: List<String>,
  val ipsets: List<String>,
  val excludeIpsets: List<String>,
)

private fun parseStrategySelection(text: String): StrategySelection {
  val include = mutableListOf<String>()
  val exclude = mutableListOf<String>()
  val ipsets = mutableListOf<String>()
  val excludeIpsets = mutableListOf<String>()
  for (token in text.split(Regex("\\s+"))) {
    val t = token.trim()
    // Stop at the first profile section: everything after `--new` belongs to
    // the preset strategy, not the user's global selection. Mirrors the
    // daemon's extract_selection_from_config so the chips never re-apply
    // preset-internal lists as the user's own choice.
    if (t == "--new") break
    when {
      t.startsWith("--hostlist=") -> {
        val path = t.removePrefix("--hostlist=")
        if (path.startsWith(HOSTLIST_PREFIX)) include.add(path.removePrefix(HOSTLIST_PREFIX))
      }
      t.startsWith("--hostlist-exclude=") -> {
        val path = t.removePrefix("--hostlist-exclude=")
        if (path.startsWith(HOSTLIST_PREFIX)) exclude.add(path.removePrefix(HOSTLIST_PREFIX))
      }
      t.startsWith("--ipset=") -> {
        val path = t.removePrefix("--ipset=")
        if (path.startsWith(HOSTLIST_PREFIX)) ipsets.add(path.removePrefix(HOSTLIST_PREFIX))
      }
      t.startsWith("--ipset-exclude=") -> {
        val path = t.removePrefix("--ipset-exclude=")
        if (path.startsWith(HOSTLIST_PREFIX)) excludeIpsets.add(path.removePrefix(HOSTLIST_PREFIX))
      }
    }
  }
  return StrategySelection(include, exclude, ipsets, excludeIpsets)
}

private fun sha256HexUtf8(s: String): String {
  val md = MessageDigest.getInstance("SHA-256")
  val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) sb.append(String.format("%02x", b))
  return sb.toString()
}

private fun ApiModels.StrategyVariant.displayName(): String = name.removeSuffix(".txt")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategicVarConfigCard(
  programId: String,
  profile: String,
  configPath: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compactWidth = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
  val compactChooser = rememberUseScrollableTabs() || shortHeight

  var text by remember(configPath) { mutableStateOf("") }
  var lastLoaded by remember(configPath) { mutableStateOf("") }
  var loading by remember(configPath) { mutableStateOf(true) }
  var saving by remember(configPath) { mutableStateOf(false) }

  var variants by remember(programId) { mutableStateOf<List<ApiModels.StrategyVariant>>(emptyList()) }
  var variantsLoading by remember(programId) { mutableStateOf(true) }
  var chooserOpen by remember(programId) { mutableStateOf(false) }
  var applying by remember(programId, profile) { mutableStateOf(false) }

  // Last applied built-in strategy variant name. Tracked separately from the
  // file hash so that visual feedback survives hostlist appends (which change
  // the file hash) and survives config edits that keep the variant intact.
  var currentVariantName by remember(programId, profile) { mutableStateOf<String?>(null) }

  // nfqws2 hostlist/ipset refs live inside per-item strategy text (Items
  // tab); the global chooser/injection below is the legacy model kept for
  // byedpi only.
  val legacyGlobalLists = programId != "nfqws2"
  // hostlist selection
  var hostlistFiles by remember(programId) { mutableStateOf<List<String>>(emptyList()) }
  var hostlistFilesLoading by remember(programId) { mutableStateOf(true) }
  var selectedHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var selectedExcludeHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var hostlistChooserOpen by remember(programId, profile) { mutableStateOf(false) }
  var applyingHostlists by remember(programId, profile) { mutableStateOf(false) }
  var lastLoadedHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var lastLoadedExcludeHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }

  // ipset selection (same strategic/list/ directory, filtered to known ipset files)
  var selectedIpsets by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var selectedExcludeIpsets by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var ipsetChooserOpen by remember(programId, profile) { mutableStateOf(false) }
  var lastLoadedIpsets by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var lastLoadedExcludeIpsets by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }

  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  var presetImportError by remember { mutableStateOf<String?>(null) }
  var userPresets by remember(programId) { mutableStateOf<List<ZapretPreset>>(emptyList()) }
  var presetEditorOpen by remember { mutableStateOf(false) }
  var presetName by remember { mutableStateOf("") }
  var presetContent by remember { mutableStateOf("") }
  var presetBusy by remember { mutableStateOf(false) }
  var editingPreset by remember { mutableStateOf<String?>(null) }
  var presetToDelete by remember { mutableStateOf<String?>(null) }
  var managePresetsOpen by remember { mutableStateOf(false) }
  var manageTarget by remember { mutableStateOf<ZapretPreset?>(null) }
  var manageAction by remember { mutableStateOf("duplicate") }
  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) {
      val readResult = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Unable to read selected file") }
      val imported = readResult.getOrNull()
      val fileName = (uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null } ?: "imported").removeSuffix(".txt").trim() + ".txt"
      if (imported == null) {
        presetImportError = ctx.getString(R.string.common_error)
      } else {
        actions.importZapretPreset(fileName, imported) { result ->
          if (result.isSuccess) {
            presetImportError = null
            actions.listZapretPresets { users -> userPresets = users ?: emptyList() }
          } else {
            presetImportError = ctx.getString(R.string.common_error)
          }
        }
      }
    }
  }
  var pendingExport by remember { mutableStateOf<String?>(null) }
  val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    val content = pendingExport
    if (uri != null && content != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) } }
    pendingExport = null
  }

  fun reloadConfig() {
    loading = true
    actions.loadText(configPath) { content ->
      val t = content ?: ""
      text = t
      lastLoaded = t
      val sel = parseStrategySelection(t)
      // Deduplicate: a corrupted/duplicated file should never propagate bloat.
      val hl = sel.hostlists.distinct()
      val ex = sel.excludeHostlists.distinct()
      val ips = sel.ipsets.distinct()
      val exi = sel.excludeIpsets.distinct()
      // Prefer the persisted preset marker over a hash match: the marker survives
      // hostlist edits (which change the file hash) and survives re-entering the
      // screen (where the in-memory currentVariantName is reset). Fall back to a
      // hash match of the loaded config so an unmarked config still resolves to a
      // known preset name.
      val marker = readVariantMarker(t)
      val hashMatch = variants.firstOrNull {
        it.sha256 != null && it.sha256.equals(sha256HexUtf8(t), ignoreCase = true)
      }
      selectedHostlists = hl
      selectedExcludeHostlists = ex
      selectedIpsets = ips
      selectedExcludeIpsets = exi
      lastLoadedHostlists = hl
      lastLoadedExcludeHostlists = ex
      lastLoadedIpsets = ips
      lastLoadedExcludeIpsets = exi
      currentVariantName = marker ?: hashMatch?.name
      loading = false
    }
  }

  fun reloadVariants() {
    variantsLoading = true
    actions.listStrategicVariants(programId) { builtIns ->
      actions.listZapretPresets { users ->
        userPresets = users ?: emptyList()
        variants = ((builtIns ?: emptyList()) + userPresets.map { ApiModels.StrategyVariant(it.name, null) })
          .distinctBy { it.name }.sortedBy { it.name.lowercase(Locale.ROOT) }
        variantsLoading = false
      }
    }
  }

  fun reloadHostlistFiles() {
    hostlistFilesLoading = true
    actions.listStrategicFiles("list") { files ->
      hostlistFiles = (files ?: emptyList()).filter { it.endsWith(".txt") }.sorted()
      hostlistFilesLoading = false
    }
  }

  fun applyVariant(variant: ApiModels.StrategyVariant) {
    chooserOpen = false
    applying = true
    // De-duplicate before sending so the daemon never receives bloat, even
    // if upstream state accidentally accumulated duplicates.
    // nfqws2: global list selection is not part of the apply payload — its
    // list refs live inside per-item strategy text.
    val hl = if (legacyGlobalLists) selectedHostlists.distinct() else emptyList()
    val ex = if (legacyGlobalLists) selectedExcludeHostlists.distinct() else emptyList()
    val ips = if (legacyGlobalLists) selectedIpsets.distinct() else emptyList()
    val exi = if (legacyGlobalLists) selectedExcludeIpsets.distinct() else emptyList()
    val userPreset = userPresets.any { it.name == variant.name }
    fun complete(ok: Boolean) {
      applying = false
      if (ok) { currentVariantName = variant.name; reloadConfig() }
      scope.launch { snackHost.showSnackbar(if (ok) ctx.getString(R.string.common_applied_with_value, variant.displayName()) else ctx.getString(R.string.common_apply_failed)) }
    }
    if (userPreset) {
      actions.loadZapretPreset(variant.name) { preset ->
        if (preset == null) complete(false)
        else actions.applyZapretPreset(profile, variant.name, preset.content, ::complete)
      }
    } else actions.applyStrategicVariant(programId, profile, variant.name, hl, ex, ips, exi, ::complete)
  }

  fun applyHostlistsOnly() {
    applyingHostlists = true
    val hl = selectedHostlists.distinct()
    val ex = selectedExcludeHostlists.distinct()
    val ips = selectedIpsets.distinct()
    val exi = selectedExcludeIpsets.distinct()
    actions.applyProfileHostlists(programId, profile, hl, ex, ips, exi) { ok ->
      applyingHostlists = false
      if (ok) reloadConfig()
      scope.launch {
        snackHost.showSnackbar(
          if (ok) ctx.getString(R.string.strategic_hostlists_applied)
          else ctx.getString(R.string.common_apply_failed)
        )
      }
    }
  }

  LaunchedEffect(configPath) { reloadConfig() }
  LaunchedEffect(programId) { reloadVariants() }
  LaunchedEffect(programId) { if (legacyGlobalLists) reloadHostlistFiles() }

  // When the user returns to this screen (e.g. after applying a strategy from
  // blockcheck on the same profile), the config may have changed out-of-band.
  // Refresh so the "currently applied" indicator reflects reality.
  LifecycleResumeEffect(Unit) {
    reloadConfig()
    reloadVariants()
    if (legacyGlobalLists) reloadHostlistFiles()
    onPauseOrDispose { }
  }

  val savedHash = remember(lastLoaded) { sha256HexUtf8(lastLoaded) }
  val matched = remember(savedHash, variants) {
    variants.firstOrNull { it.sha256 != null && it.sha256.equals(savedHash, ignoreCase = true) }
  }
  // Prefer the explicit tracking variable; fall back to the hash match.
  // This keeps the chip green/stable when hostlists are appended (which change
  // the file hash) without losing the prior behaviour.
  val activeVariantName = currentVariantName ?: matched?.name
  val activeVariant = remember(activeVariantName, variants) {
    activeVariantName?.let { name -> variants.firstOrNull { it.name == name } }
  }

  val strategyLabel = when {
    variantsLoading -> stringResource(R.string.strategic_strategies_loading)
    variants.isEmpty() -> stringResource(R.string.strategic_strategies_none)
    activeVariant != null -> stringResource(R.string.strategic_strategy_selected, activeVariant.displayName())
    else -> stringResource(R.string.strategic_user_config)
  }
  val isUserConfig = !variantsLoading && variants.isNotEmpty() && activeVariant == null
  val canChooseVariant = !loading && !saving && !applying && !variantsLoading && variants.isNotEmpty()
  val hostlistsChanged = legacyGlobalLists && (selectedHostlists != lastLoadedHostlists || selectedExcludeHostlists != lastLoadedExcludeHostlists)
  val ipsetsChanged = legacyGlobalLists && (selectedIpsets != lastLoadedIpsets || selectedExcludeIpsets != lastLoadedExcludeIpsets)
  val canSave = !loading && !saving && !applying && (text != lastLoaded || hostlistsChanged || ipsetsChanged)

  fun saveConfig() {
    saving = true
    // Dedupe before writing so we never commit accumulated duplicates.
    val wantedInclude = selectedHostlists.distinct()
    val wantedExclude = selectedExcludeHostlists.distinct()
    val wantedIpsets = selectedIpsets.distinct()
    val wantedExcludeIpsets = selectedExcludeIpsets.distinct()
    val hostlistArgs = buildList {
      for (hl in wantedInclude) add("--hostlist=$HOSTLIST_PREFIX$hl")
      for (ex in wantedExclude) add("--hostlist-exclude=$HOSTLIST_PREFIX$ex")
      for (ips in wantedIpsets) add("--ipset=$HOSTLIST_PREFIX$ips")
      for (exi in wantedExcludeIpsets) add("--ipset-exclude=$HOSTLIST_PREFIX$exi")
    }
    val cleaned = text
      .replace(Regex("""--hostlist=[^\s]+"""), "")
      .replace(Regex("""--hostlist-exclude=[^\s]+"""), "")
      .replace(Regex("""--ipset=[^\s]+"""), "")
      .replace(Regex("""--ipset-exclude=[^\s]+"""), "")
      .replace(Regex("""[ \t]{2,}"""), " ")
      .trimEnd()
    // nfqws2: write the text verbatim — list refs belong to item lines and
    // must not be stripped/re-injected globally.
    val newText = if (!legacyGlobalLists) {
      text
    } else if (hostlistArgs.isEmpty()) {
      cleaned
    } else {
      cleaned + "\n" + hostlistArgs.joinToString(" ") + "\n"
    }
    actions.saveText(configPath, newText) { ok ->
      saving = false
      if (ok) {
        lastLoaded = newText
        lastLoadedHostlists = wantedInclude
        lastLoadedExcludeHostlists = wantedExclude
        lastLoadedIpsets = wantedIpsets
        lastLoadedExcludeIpsets = wantedExcludeIpsets
        // If the saved text is no longer exactly a prebuilt variant (the user
        // edited it), drop the pinned variant name so the chip reverts to
        // "user config" instead of falsely claiming the variant is active.
        val hash = sha256HexUtf8(newText)
        val stillMatches = variants.any { it.sha256 != null && it.sha256.equals(hash, ignoreCase = true) }
        if (!stillMatches) currentVariantName = null
      }
      scope.launch {
        snackHost.showSnackbar(
          if (ok) ctx.getString(R.string.editor_saved_apply_restart)
          else ctx.getString(R.string.editor_save_failed)
        )
      }
    }
  }

  if (presetEditorOpen) {
    AlertDialog(
      onDismissRequest = { presetEditorOpen = false },
      title = { Text(stringResource(if (editingPreset == null) R.string.strategic_preset_create else R.string.strategic_preset_edit)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text(stringResource(R.string.strategic_preset_name)) }, enabled = editingPreset == null)
          OutlinedTextField(value = presetContent, onValueChange = { presetContent = it }, label = { Text(stringResource(R.string.strategic_preset_content)) }, minLines = 8)
        }
      },
      confirmButton = {
        TextButton(enabled = !presetBusy && presetName.isNotBlank(), onClick = {
          presetBusy = true
          val done: (Boolean) -> Unit = { ok ->
            presetBusy = false
            if (ok) { presetEditorOpen = false; reloadVariants() }
            scope.launch { snackHost.showSnackbar(ctx.getString(if (ok) R.string.common_saved else R.string.common_error)) }
          }
          val normalizedName = "${presetName.trim().removeSuffix(".txt").trim()}.txt"
          if (editingPreset == null) actions.saveZapretPreset(normalizedName, presetContent, done)
          else actions.updateZapretPreset(editingPreset!!, presetContent, done)
        }) { Text(stringResource(R.string.common_save)) }
      },
      dismissButton = { TextButton(onClick = { presetEditorOpen = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }
  presetImportError?.let { error ->
    AlertDialog(
      onDismissRequest = { presetImportError = null },
      title = { Text(stringResource(R.string.common_error)) },
      text = { Text(error) },
      confirmButton = {
        TextButton(onClick = { presetImportError = null }) { Text(stringResource(R.string.common_ok)) }
      },
    )
  }

  if (managePresetsOpen) {
    ModalBottomSheet(onDismissRequest = { managePresetsOpen = false }) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.strategic_presets_manage), style = MaterialTheme.typography.titleMedium)
        var manageQuery by remember { mutableStateOf("") }
        val manageFiltered = remember(userPresets, manageQuery) {
          if (manageQuery.isBlank()) userPresets
          else userPresets.filter { it.name.removeSuffix(".txt").contains(manageQuery.trim(), ignoreCase = true) }
        }
        if (userPresets.size > 8) {
          OutlinedTextField(
            value = manageQuery,
            onValueChange = { manageQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.nfqws2_list_search)) },
          )
        }
        if (userPresets.isEmpty()) Text(stringResource(R.string.strategic_strategies_none), style = MaterialTheme.typography.bodyMedium)
        manageFiltered.forEach { item ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.name.removeSuffix(".txt"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
              managePresetsOpen = false; editingPreset = item.name; presetName = item.name
              actions.loadZapretPreset(item.name) { content -> presetContent = content?.content.orEmpty(); presetEditorOpen = content != null }
            }) { Text(stringResource(R.string.strategic_preset_edit)) }
            TextButton(onClick = { managePresetsOpen = false; manageAction = "duplicate"; manageTarget = item; presetName = "${item.name.removeSuffix(".txt")}-copy" }) { Text(stringResource(R.string.strategic_preset_duplicate)) }
            TextButton(onClick = { managePresetsOpen = false; manageAction = "rename"; manageTarget = item; presetName = item.name.removeSuffix(".txt") }) { Text(stringResource(R.string.strategic_preset_rename)) }
            TextButton(onClick = { managePresetsOpen = false; presetToDelete = item.name }) { Text(stringResource(R.string.strategic_preset_delete)) }
            TextButton(onClick = {
              actions.loadZapretPreset(item.name) { content ->
                if (content != null) { pendingExport = content.content; exportLauncher.launch(item.name) }
              }
            }) { Text(stringResource(R.string.strategic_preset_export)) }
          }
        }
      }
    }
  }

  presetToDelete?.let { name ->
    AlertDialog(
      onDismissRequest = { presetToDelete = null },
      title = { Text(stringResource(R.string.strategic_preset_delete)) },
      text = { Text(stringResource(R.string.strategic_preset_delete_confirm, name)) },
      confirmButton = { TextButton(onClick = {
        presetBusy = true
        actions.deleteZapretPreset(name) { ok ->
          presetBusy = false
          presetToDelete = null
          if (!ok) {
            scope.launch { snackHost.showSnackbar(ctx.getString(R.string.common_error)) }
            return@deleteZapretPreset
          }
          val wasActive = currentVariantName == name
          reloadVariants()
          if (wasActive) {
            actions.listStrategicVariants(programId) { builtIns ->
              val fallback = builtIns.orEmpty().firstOrNull()
              if (fallback == null) {
                scope.launch { snackHost.showSnackbar(ctx.getString(R.string.common_error)) }
                return@listStrategicVariants
              }
              applying = true
              actions.applyStrategicVariant(
                programId, profile, fallback.name,
                if (legacyGlobalLists) selectedHostlists.distinct() else emptyList(),
                if (legacyGlobalLists) selectedExcludeHostlists.distinct() else emptyList(),
                if (legacyGlobalLists) selectedIpsets.distinct() else emptyList(),
                if (legacyGlobalLists) selectedExcludeIpsets.distinct() else emptyList(),
              ) { applied ->
                applying = false
                if (applied) {
                  currentVariantName = fallback.name
                  reloadConfig()
                  scope.launch { snackHost.showSnackbar(ctx.getString(R.string.common_applied_with_value, fallback.displayName())) }
                } else {
                  scope.launch { snackHost.showSnackbar(ctx.getString(R.string.common_error)) }
                }
              }
            }
          }
          scope.launch { snackHost.showSnackbar(ctx.getString(R.string.common_deleted)) }
        }
      }) { Text(stringResource(R.string.strategic_preset_delete)) } },
      dismissButton = { TextButton(onClick = { presetToDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }

  manageTarget?.let { target ->
    AlertDialog(
      onDismissRequest = { manageTarget = null },
      title = { Text(stringResource(if (manageAction == "duplicate") R.string.strategic_preset_duplicate else R.string.strategic_preset_rename)) },
      text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text(stringResource(R.string.strategic_preset_name)) }) },
      confirmButton = { TextButton(enabled = presetName.isNotBlank(), onClick = {
        val entered = presetName.trim().removeSuffix(".txt").trim()
        val normalizedName = "$entered.txt"
        val done: (Boolean) -> Unit = { ok ->
          manageTarget = null
          if (ok) reloadVariants()
          scope.launch { snackHost.showSnackbar(ctx.getString(if (ok) R.string.common_saved else R.string.common_error)) }
        }
        if (manageAction == "duplicate") actions.duplicateZapretPreset(target.name, normalizedName, done)
        else actions.renameZapretPreset(target.name, normalizedName, done)
      }) { Text(stringResource(R.string.common_save)) } },
      dismissButton = { TextButton(onClick = { manageTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }

  if (chooserOpen) {
    StrategicVariantsBottomSheet(
      variants = variants,
      currentVariantName = activeVariantName,
      onDismiss = { chooserOpen = false },
      onChoose = ::applyVariant,
    )
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      if (compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.fillMaxWidth()) {
            Text(
              stringResource(R.string.strategic_config_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              stringResource(R.string.strategic_config_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Button(
            onClick = { saveConfig() },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(R.string.strategic_config_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              stringResource(R.string.strategic_config_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Button(
            onClick = { saveConfig() },
            enabled = canSave,
          ) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      val chipColors = if (isUserConfig) {
        AssistChipDefaults.assistChipColors(
          containerColor = MaterialTheme.colorScheme.errorContainer,
          labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
      } else {
        AssistChipDefaults.assistChipColors()
      }

      if (compactChooser) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AssistChip(
            modifier = Modifier.weight(1f),
            onClick = { },
            label = {
              Text(
                strategyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            colors = chipColors,
          )
          Surface(
            shape = CircleShape,
            color = if (canChooseVariant) {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
            } else {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
            },
          ) {
            IconButton(
              onClick = { chooserOpen = true },
              enabled = canChooseVariant,
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.common_choose),
              )
            }
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AssistChip(
            modifier = Modifier.weight(1f),
            onClick = { },
            label = {
              Text(
                strategyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            colors = chipColors,
          )
          Spacer(Modifier.width(12.dp))
          Button(
            onClick = { chooserOpen = true },
            enabled = canChooseVariant,
          ) {
            Text(if (applying) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_choose))
          }
        }
      }
      TextButton(onClick = { managePresetsOpen = true }) { Text(stringResource(R.string.strategic_presets_manage)) }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { editingPreset = null; presetName = ""; presetContent = text; presetEditorOpen = true }) {
          Text(stringResource(R.string.strategic_preset_create))
        }
        TextButton(onClick = { importLauncher.launch("text/*") }) { Text(stringResource(R.string.strategic_preset_import)) }
        if (activeVariantName != null && userPresets.any { it.name == activeVariantName }) {
          TextButton(onClick = {
            val selected = userPresets.first { it.name == activeVariantName }
            editingPreset = selected.name; presetName = selected.name
            actions.loadZapretPreset(selected.name) { preset -> presetContent = preset?.content.orEmpty(); presetEditorOpen = true }
          }) { Text(stringResource(R.string.strategic_preset_edit)) }
          TextButton(onClick = {
            val selected = userPresets.first { it.name == activeVariantName }
            actions.loadZapretPreset(selected.name) { preset ->
              if (preset != null) { pendingExport = preset.content; exportLauncher.launch(selected.name) }
            }
          }) { Text(stringResource(R.string.strategic_preset_export)) }
          TextButton(onClick = {
            val selected = userPresets.first { it.name == activeVariantName }
            presetName = "${selected.name.removeSuffix(".txt")}-copy"; manageAction = "duplicate"; manageTarget = selected
          }) { Text(stringResource(R.string.strategic_preset_duplicate)) }
          TextButton(onClick = {
            val selected = userPresets.first { it.name == activeVariantName }
            presetName = selected.name.removeSuffix(".txt"); manageAction = "rename"; manageTarget = selected
          }) { Text(stringResource(R.string.strategic_preset_rename)) }
          TextButton(onClick = { presetToDelete = activeVariantName }) { Text(stringResource(R.string.strategic_preset_delete)) }
        }
      }

      Spacer(Modifier.height(10.dp))

      // ---- hostlist selection ----
      if (legacyGlobalLists && hostlistChooserOpen) {
        HostlistChooserBottomSheet(
          allFiles = hostlistFiles,
          selectedHostlists = selectedHostlists,
          selectedExclude = selectedExcludeHostlists,
          onDismiss = { hostlistChooserOpen = false },
          onSave = { h, e ->
            selectedHostlists = h
            selectedExcludeHostlists = e
            hostlistChooserOpen = false
            if (activeVariant != null) {
              applyVariant(activeVariant)
            } else {
              applyHostlistsOnly()
            }
          },
        )
      }

      // ---- ipset selection ----
      if (legacyGlobalLists && ipsetChooserOpen) {
        IpSetChooserBottomSheet(
          allFiles = hostlistFiles,
          selectedIpsets = selectedIpsets,
          selectedExclude = selectedExcludeIpsets,
          onDismiss = { ipsetChooserOpen = false },
          onSave = { h, e ->
            selectedIpsets = h
            selectedExcludeIpsets = e
            ipsetChooserOpen = false
            if (activeVariant != null) {
              applyVariant(activeVariant)
            } else {
              applyHostlistsOnly()
            }
          },
        )
      }

      if (legacyGlobalLists) {
        Spacer(Modifier.height(10.dp))

        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.strategic_ipsets_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = { ipsetChooserOpen = true },
              enabled = !loading && !saving && !applying && !hostlistFilesLoading,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Text(
                if (selectedIpsets.isNotEmpty() || selectedExcludeIpsets.isNotEmpty()) {
                  stringResource(R.string.strategic_ipsets_edit)
                } else {
                  stringResource(R.string.common_choose)
                },
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }

        Spacer(Modifier.height(6.dp))

        val ipsetSummary = buildList {
          if (selectedIpsets.isNotEmpty()) {
            add(stringResource(R.string.strategic_ipsets_count, selectedIpsets.size))
          }
          if (selectedExcludeIpsets.isNotEmpty()) {
            add(stringResource(R.string.strategic_exclude_ipsets_count, selectedExcludeIpsets.size))
          }
        }.joinToString(", ")

        Text(
          text = if (ipsetSummary.isNotEmpty()) ipsetSummary else stringResource(R.string.strategic_ipsets_none),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        Spacer(Modifier.height(10.dp))
      }
      if (legacyGlobalLists) {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.strategic_hostlists_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activeVariant == null && (selectedHostlists.isNotEmpty() || selectedExcludeHostlists.isNotEmpty())) {
              Button(
                onClick = { applyHostlistsOnly() },
                enabled = !loading && !saving && !applying && !applyingHostlists && !hostlistFilesLoading,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
              ) {
                Text(
                  if (applyingHostlists) stringResource(R.string.common_ellipsis)
                  else stringResource(R.string.strategic_hostlists_apply),
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }
            Button(
              onClick = { hostlistChooserOpen = true },
              enabled = !loading && !saving && !applying && !hostlistFilesLoading,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Text(
                if (selectedHostlists.isNotEmpty() || selectedExcludeHostlists.isNotEmpty()) {
                  stringResource(R.string.strategic_hostlists_edit)
                } else {
                  stringResource(R.string.common_choose)
                },
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }

        Spacer(Modifier.height(6.dp))

        val hostlistSummary = buildList {
          if (selectedHostlists.isNotEmpty()) {
            add(stringResource(R.string.strategic_hostlists_count, selectedHostlists.size))
          }
          if (selectedExcludeHostlists.isNotEmpty()) {
            add(stringResource(R.string.strategic_exclude_hostlists_count, selectedExcludeHostlists.size))
          }
        }.joinToString(", ")

        Text(
          text = if (hostlistSummary.isNotEmpty()) hostlistSummary else stringResource(R.string.strategic_hostlists_none),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        Spacer(Modifier.height(10.dp))
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (shortHeight) 120.dp else 140.dp),
        enabled = !loading && !saving && !applying,
        label = { Text(if (loading) stringResource(R.string.common_loading) else "") },
        maxLines = 24,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategicVariantsBottomSheet(
  variants: List<ApiModels.StrategyVariant>,
  currentVariantName: String?,
  onDismiss: () -> Unit,
  onChoose: (ApiModels.StrategyVariant) -> Unit,
) {
  val shortHeight = rememberIsShortHeight()
  val narrowWidth = rememberIsNarrowWidth()
  val currentDisplay = currentVariantName?.removeSuffix(".txt") ?: stringResource(R.string.strategic_user_config)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.strategic_variants_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            stringResource(R.string.current_value_fmt, currentDisplay),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = if (narrowWidth) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
          IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      var sheetQuery by remember { mutableStateOf("") }
      val sheetFiltered = remember(variants, sheetQuery) {
        if (sheetQuery.isBlank()) variants
        else variants.filter { it.name.removeSuffix(".txt").contains(sheetQuery.trim(), ignoreCase = true) }
      }
      if (variants.size > 8) {
        OutlinedTextField(
          value = sheetQuery,
          onValueChange = { sheetQuery = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.nfqws2_list_search)) },
        )
      }

      Spacer(Modifier.height(12.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (shortHeight) 220.dp else 260.dp, max = if (shortHeight) 420.dp else 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
      ) {
        items(sheetFiltered, key = { it.name }, contentType = { "strategic_variant" }) { variant ->
          val isCurrent = variant.name == currentVariantName
          Card(
            onClick = { onChoose(variant) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
              }
            ),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f)) {
                Text(
                  text = variant.displayName(),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                  text = if (isCurrent) {
                    stringResource(R.string.strategic_variants_current_hint)
                  } else {
                    stringResource(R.string.strategic_variants_tap_to_apply)
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                )
              }
              if (isCurrent) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                  Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Filled.Check,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimary,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostlistChooserBottomSheet(
  allFiles: List<String>,
  selectedHostlists: List<String>,
  selectedExclude: List<String>,
  onDismiss: () -> Unit,
  onSave: (hostlists: List<String>, exclude: List<String>) -> Unit,
) {
  val shortHeight = rememberIsShortHeight()

  // Hostlists only: ipset files belong in the other sheet.
  val regularFiles = allFiles.filter { it != "exclude.txt" && !isIpSetFile(it) }
  val excludeFile = if (allFiles.contains("exclude.txt")) "exclude.txt" else null

  var selected by remember(selectedHostlists, selectedExclude) {
    mutableStateOf(selectedHostlists.toSet())
  }
  var selectedExcl by remember(selectedHostlists, selectedExclude) {
    mutableStateOf(selectedExclude.toSet())
  }
  // Guard against double-tap: recomposition lags behind state changes, so a
  // user mashing Apply could fire onSave twice before the sheet closes.
  var applyRequested by remember { mutableStateOf(false) }

  val hasChanges = selected != selectedHostlists.toSet() || selectedExcl != selectedExclude.toSet()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.strategic_hostlists_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            stringResource(R.string.strategic_hostlists_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
          IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      if (excludeFile != null) {
        Row(
          modifier = Modifier.fillMaxWidth().clickable {
            selectedExcl = if (excludeFile in selectedExcl) selectedExcl - excludeFile else selectedExcl + excludeFile
          }.padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(
            checked = excludeFile in selectedExcl,
            onCheckedChange = { c ->
              selectedExcl = if (c) selectedExcl + excludeFile else selectedExcl - excludeFile
            },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            excludeFile.removeSuffix(".txt"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
          )
        }

        Spacer(Modifier.height(4.dp))
        Text(
          stringResource(R.string.strategic_hostlists_include),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(4.dp))
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (shortHeight) 180.dp else 220.dp, max = if (shortHeight) 300.dp else 400.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
      ) {
        items(regularFiles, key = { it }) { file ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable {
              selected = if (file in selected) selected - file else selected + file
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = file in selected,
              onCheckedChange = { c ->
                selected = if (c) selected + file else selected - file
              },
            )
            Spacer(Modifier.width(8.dp))
            Text(
              file.removeSuffix(".txt"),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(
          onClick = {
            if (applyRequested) return@Button
            applyRequested = true
            onSave(selected.toList(), selectedExcl.toList())
          },
          enabled = hasChanges && !applyRequested,
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.strategic_hostlists_apply))
        }
        Button(
          onClick = {
            selected = emptySet()
            selectedExcl = emptySet()
          },
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.common_clear))
        }
      }

      Spacer(Modifier.height(16.dp))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IpSetChooserBottomSheet(
  allFiles: List<String>,
  selectedIpsets: List<String>,
  selectedExclude: List<String>,
  onDismiss: () -> Unit,
  onSave: (ipsets: List<String>, exclude: List<String>) -> Unit,
) {
  val shortHeight = rememberIsShortHeight()

  // Only the ipset files are selectable here (the same strategic/list/
  // directory also contains hostlists which are chosen in the other sheet).
  val knownFiles = allFiles.filter { isIpSetFile(it) }
  val regularFiles = knownFiles.filter { it != "exclude.txt" }
  val excludeFile = if (knownFiles.contains("exclude.txt")) "exclude.txt" else null

  var selected by remember(selectedIpsets, selectedExclude) {
    mutableStateOf(selectedIpsets.toSet())
  }
  var selectedExcl by remember(selectedIpsets, selectedExclude) {
    mutableStateOf(selectedExclude.toSet())
  }
  // Guard against double-tap: recomposition lags behind state changes, so a
  // user mashing Apply could fire onSave twice before the sheet closes.
  var applyRequested by remember { mutableStateOf(false) }

  val hasChanges = selected != selectedIpsets.toSet() || selectedExcl != selectedExclude.toSet()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.strategic_ipsets_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            stringResource(R.string.strategic_ipsets_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
          IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      if (excludeFile != null) {
        Row(
          modifier = Modifier.fillMaxWidth().clickable {
            selectedExcl = if (excludeFile in selectedExcl) selectedExcl - excludeFile else selectedExcl + excludeFile
          }.padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(
            checked = excludeFile in selectedExcl,
            onCheckedChange = { c ->
              selectedExcl = if (c) selectedExcl + excludeFile else selectedExcl - excludeFile
            },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            excludeFile.removeSuffix(".txt"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
          )
        }

        Spacer(Modifier.height(4.dp))
        Text(
          stringResource(R.string.strategic_ipsets_include),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(4.dp))
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (shortHeight) 180.dp else 220.dp, max = if (shortHeight) 300.dp else 400.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
      ) {
        items(regularFiles, key = { it }) { file ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable {
              selected = if (file in selected) selected - file else selected + file
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = file in selected,
              onCheckedChange = { c ->
                selected = if (c) selected + file else selected - file
              },
            )
            Spacer(Modifier.width(8.dp))
            Text(
              file.removeSuffix(".txt"),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(
          onClick = {
            if (applyRequested) return@Button
            applyRequested = true
            onSave(selected.toList(), selectedExcl.toList())
          },
          enabled = hasChanges && !applyRequested,
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.strategic_ipsets_apply))
        }
        Button(
          onClick = {
            selected = emptySet()
            selectedExcl = emptySet()
          },
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.common_clear))
        }
      }

      Spacer(Modifier.height(16.dp))
    }
  }
}
