package com.android.zdtd.service.ui

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
import com.android.zdtd.service.api.ApiModels
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.launch

private const val HOSTLIST_PREFIX = "/data/adb/modules/ZDT-D/strategic/list/"

private fun parseHostlistText(text: String): Pair<List<String>, List<String>> {
  val include = mutableListOf<String>()
  val exclude = mutableListOf<String>()
  for (token in text.split(Regex("\\s+"))) {
    val t = token.trim()
    when {
      t.startsWith("--hostlist=") -> {
        val path = t.removePrefix("--hostlist=")
        if (path.startsWith(HOSTLIST_PREFIX)) include.add(path.removePrefix(HOSTLIST_PREFIX))
      }
      t.startsWith("--hostlist-exclude=") -> {
        val path = t.removePrefix("--hostlist-exclude=")
        if (path.startsWith(HOSTLIST_PREFIX)) exclude.add(path.removePrefix(HOSTLIST_PREFIX))
      }
    }
  }
  return Pair(include, exclude)
}

private fun sha256HexUtf8(s: String): String {
  val md = MessageDigest.getInstance("SHA-256")
  val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) sb.append(String.format("%02x", b))
  return sb.toString()
}

private fun ApiModels.StrategyVariant.displayName(): String = name.removeSuffix(".txt")

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

  // hostlist selection
  var hostlistFiles by remember(programId) { mutableStateOf<List<String>>(emptyList()) }
  var hostlistFilesLoading by remember(programId) { mutableStateOf(true) }
  var selectedHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var selectedExcludeHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var hostlistChooserOpen by remember(programId, profile) { mutableStateOf(false) }
  var applyingHostlists by remember(programId, profile) { mutableStateOf(false) }
  var lastLoadedHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }
  var lastLoadedExcludeHostlists by remember(programId, profile) { mutableStateOf<List<String>>(emptyList()) }

  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current

  fun reloadConfig() {
    loading = true
    actions.loadText(configPath) { content ->
      val t = content ?: ""
      text = t
      lastLoaded = t
      val (h, e) = parseHostlistText(t)
      // Deduplicate: a corrupted/duplicated file should never propagate bloat.
      val hl = h.distinct()
      val ex = e.distinct()
      // If the freshly-loaded file already matches a prebuilt variant, lift
      // that into currentVariantName so visual feedback survives hostlist edits.
      val hash = sha256HexUtf8(t)
      val match = variants.firstOrNull { it.sha256 != null && it.sha256.equals(hash, ignoreCase = true) }
      selectedHostlists = hl
      selectedExcludeHostlists = ex
      lastLoadedHostlists = hl
      lastLoadedExcludeHostlists = ex
      if (match != null) currentVariantName = match.name
      loading = false
    }
  }

  fun reloadVariants() {
    variantsLoading = true
    actions.listStrategicVariants(programId) { v ->
      variants = (v ?: emptyList()).sortedBy { it.name.lowercase(Locale.ROOT) }
      variantsLoading = false
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
    val hl = selectedHostlists.distinct()
    val ex = selectedExcludeHostlists.distinct()
    actions.applyStrategicVariant(programId, profile, variant.name, hl, ex) { ok ->
      applying = false
      if (ok) {
        currentVariantName = variant.name
        reloadConfig()
      }
      scope.launch {
        snackHost.showSnackbar(
          if (ok) ctx.getString(R.string.common_applied_with_value, variant.displayName())
          else ctx.getString(R.string.common_apply_failed)
        )
      }
    }
  }

  fun applyHostlistsOnly() {
    applyingHostlists = true
    val hl = selectedHostlists.distinct()
    val ex = selectedExcludeHostlists.distinct()
    actions.applyProfileHostlists(programId, profile, hl, ex) { ok ->
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
  LaunchedEffect(programId) { reloadHostlistFiles() }

  // When the user returns to this screen (e.g. after applying a strategy from
  // blockcheck on the same profile), the config may have changed out-of-band.
  // Refresh so the "currently applied" indicator reflects reality.
  LifecycleResumeEffect(Unit) {
    reloadConfig()
    reloadVariants()
    reloadHostlistFiles()
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
  val hostlistsChanged = selectedHostlists != lastLoadedHostlists || selectedExcludeHostlists != lastLoadedExcludeHostlists
  val canSave = !loading && !saving && !applying && (text != lastLoaded || hostlistsChanged)

  fun saveConfig() {
    saving = true
    // Dedupe before writing so we never commit accumulated duplicates.
    val wantedInclude = selectedHostlists.distinct()
    val wantedExclude = selectedExcludeHostlists.distinct()
    val hostlistArgs = buildList {
      for (hl in wantedInclude) add("--hostlist=$HOSTLIST_PREFIX$hl")
      for (ex in wantedExclude) add("--hostlist-exclude=$HOSTLIST_PREFIX$ex")
    }
    val cleaned = text
      .replace(Regex("""--hostlist=[^\s]+"""), "")
      .replace(Regex("""--hostlist-exclude=[^\s]+"""), "")
      .replace(Regex("""[ \t]{2,}"""), " ")
      .trimEnd()
    val newText = if (hostlistArgs.isEmpty()) cleaned else {
      cleaned + "\n" + hostlistArgs.joinToString(" ") + "\n"
    }
    actions.saveText(configPath, newText) { ok ->
      saving = false
      if (ok) {
        lastLoaded = newText
        lastLoadedHostlists = wantedInclude
        lastLoadedExcludeHostlists = wantedExclude
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

      Spacer(Modifier.height(10.dp))

      // ---- hostlist selection ----
      if (hostlistChooserOpen) {
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
      Text(
        stringResource(R.string.strategic_variants_sheet_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )

      Spacer(Modifier.height(12.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (shortHeight) 220.dp else 260.dp, max = if (shortHeight) 420.dp else 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
      ) {
        items(variants, key = { it.name }, contentType = { "strategic_variant" }) { variant ->
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

  val regularFiles = allFiles.filter { it != "exclude.txt" }
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
