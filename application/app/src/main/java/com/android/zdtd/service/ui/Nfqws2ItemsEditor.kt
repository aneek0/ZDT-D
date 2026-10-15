package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.MainViewModel.Nfqws2Item
import com.android.zdtd.service.MainViewModel.Nfqws2ListSummary
import com.android.zdtd.service.MainViewModel.Nfqws2StrategyOption
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.launch

/**
 * Per-item editor for an nfqws2 profile's config.txt.
 *
 * Each `--new`-delimited block is one "item": its match filters, list refs and
 * strategy lines are shown as chips, with enable/duplicate/delete/move actions
 * calling the daemon item API (which rewrites config.txt atomically).
 */
@Composable
fun Nfqws2ItemsEditor(
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()

  var items by remember(profile) { mutableStateOf<List<Nfqws2Item>?>(null) }
  var strategies by remember { mutableStateOf<Map<String, List<Nfqws2StrategyOption>>>(emptyMap()) }
  var busy by remember(profile) { mutableStateOf(false) }
  var strategyPickerFor by remember { mutableStateOf<Nfqws2Item?>(null) }

  fun reload() {
    actions.listNfqws2Items(profile) { list -> items = list }
  }
  LaunchedEffect(profile) { reload() }
  LaunchedEffect(Unit) {
    actions.listNfqws2Strategies { families -> strategies = families }
  }
  var matchEditorFor by remember(profile) { mutableStateOf<Nfqws2Item?>(null) }
  var rawEditorFor by remember(profile) { mutableStateOf<Nfqws2Item?>(null) }

  fun snack(ok: Boolean) {
    scope.launch {
      snackHost.showSnackbar(
        ctx.getString(if (ok) R.string.editor_saved_apply_restart else R.string.common_apply_failed)
      )
    }
  }

  fun mutate(block: (done: (Boolean) -> Unit) -> Unit) {
    if (busy) return
    busy = true
    block { ok ->
      busy = false
      snack(ok)
      if (ok) reload()
    }
  }

  val list = items
  if (list == null) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
    return
  }

  var cutoverPending by remember(profile) { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(profile) {
    actions.getNfqws2CutoverStatus { st ->
      cutoverPending = st != null && st.state == "pending" && st.pendingProfiles.contains(profile)
    }
  }
  if (cutoverPending == true) {
    Nfqws2CutoverCard(
      actions = actions,
      snackHost = snackHost,
      onDone = { cutoverPending = false; reload() },
    )
  }

  Nfqws2ListsSection(profile = profile, actions = actions, snackHost = snackHost)

  // Plain Column: parent ProfileScreen already wraps content in
  // Column(verticalScroll); LazyColumn there crashes with infinite-height
  // constraints. Item count is small (tens), laziness buys nothing.
  Text(
    stringResource(R.string.nfqws2_item_sections_hint),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
  )

  Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    list.forEachIndexed { index, item ->
      ItemEditorCard(
        item = item,
        index = index,
        count = list.size,
        busy = busy,
        onToggle = { enabled -> mutate { done -> actions.setNfqws2ItemEnabled(profile, index, enabled, done) } },
        onDuplicate = { mutate { done -> actions.duplicateNfqws2Item(profile, index, done) } },
        onDelete = { mutate { done -> actions.deleteNfqws2Item(profile, index, done) } },
        onMove = { delta -> mutate { done -> actions.moveNfqws2Item(profile, index, delta, done) } },
        onPickStrategy = { strategyPickerFor = item },
        onEditMatch = { matchEditorFor = item },
        onEditRaw = { rawEditorFor = item },
      )
    }
    if (list.isEmpty()) {
      Text(stringResource(R.string.nfqws2_items_empty), style = MaterialTheme.typography.bodyMedium)
    }
  }

  if (strategyPickerFor != null) {
    val current = strategyPickerFor!!
    var pickerQuery by remember(current) { mutableStateOf("") }
    val pickerFiltered = remember(strategies, pickerQuery) {
      if (pickerQuery.isBlank()) strategies
      else strategies.mapValues { (_, options) ->
        options.filter { it.name.contains(pickerQuery.trim(), ignoreCase = true) || it.preset.contains(pickerQuery.trim(), ignoreCase = true) }
      }.filterValues { it.isNotEmpty() }
    }
    AlertDialog(
      onDismissRequest = { strategyPickerFor = null },
      title = { Text(stringResource(R.string.nfqws2_item_strategy_pick)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = pickerQuery,
            onValueChange = { pickerQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.nfqws2_list_search)) },
          )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((family, options) in pickerFiltered) {
            item {
              Text(family, style = MaterialTheme.typography.titleSmall)
            }
            items(options.size) { i ->
              val opt = options[i]
              TextButton(onClick = {
                strategyPickerFor = null
                mutate { done -> actions.setNfqws2ItemStrategy(profile, current.index, opt.lines, false, done) }
              }) {
                Column {
                  Text(opt.preset, style = MaterialTheme.typography.labelMedium)
                  Text(opt.name, style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
          }
          if (pickerFiltered.isEmpty()) {
            item { Text(stringResource(R.string.nfqws2_items_strategies_none)) }
          }
        }
        }
      },
      confirmButton = {
        TextButton(onClick = { strategyPickerFor = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }

  if (matchEditorFor != null) {
    val current = matchEditorFor!!
    var name by remember(current) { mutableStateOf(current.name ?: "") }
    var filter by remember(current) { mutableStateOf(current.ports) }
    AlertDialog(
      onDismissRequest = { matchEditorFor = null },
      title = { Text(stringResource(R.string.nfqws2_item_match_edit)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.nfqws2_item_match_name)) },
            supportingText = { Text(stringResource(R.string.nfqws2_item_match_name_hint)) },
            singleLine = true,
          )
          OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(R.string.nfqws2_item_match_filter)) },
            singleLine = true,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val item = current
          matchEditorFor = null
          mutate { done ->
            actions.setNfqws2ItemMatch(
              profile, item.index,
              name = name.ifBlank { null },
              filter = filter.ifBlank { null },
              hostlist = null,
              ipset = null,
              done,
            )
          }
        }) { Text(stringResource(R.string.common_save)) }
      },
      dismissButton = {
        TextButton(onClick = { matchEditorFor = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }

  if (rawEditorFor != null) {
    val current = rawEditorFor!!
    var raw by remember(current) { mutableStateOf(current.raw) }
    AlertDialog(
      onDismissRequest = { rawEditorFor = null },
      title = { Text(stringResource(R.string.nfqws2_item_raw_edit)) },
      text = {
        OutlinedTextField(
          value = raw,
          onValueChange = { raw = it },
          label = { Text(stringResource(R.string.nfqws2_item_raw_hint)) },
          modifier = Modifier.fillMaxWidth(),
          minLines = 6,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          val item = current
          val text = raw
          rawEditorFor = null
          mutate { done -> actions.setNfqws2ItemRaw(profile, item.index, text, done) }
        }) { Text(stringResource(R.string.common_save)) }
      },
      dismissButton = {
        TextButton(onClick = { rawEditorFor = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditorCard(
  item: Nfqws2Item,
  index: Int,
  count: Int,
  busy: Boolean,
  onToggle: (Boolean) -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onMove: (Int) -> Unit,
  onPickStrategy: () -> Unit,
  onEditMatch: () -> Unit,
  onEditRaw: () -> Unit,
) {
  SectionCard(
    title = item.displayName,
    desc = null,
    accent = if (item.enabled) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline,
    trailing = {
      Row {
        IconButton(enabled = !busy && index > 0, onClick = { onMove(-1) }) {
          Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.nfqws2_item_move_up))
        }
        IconButton(enabled = !busy && index < count - 1, onClick = { onMove(1) }) {
          Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.nfqws2_item_move_down))
        }
        IconButton(enabled = !busy, onClick = onDuplicate) {
          Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.nfqws2_item_duplicate))
        }
        IconButton(enabled = !busy, onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.nfqws2_item_delete))
        }
      }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // FlowRow: a wide ports chip ("50000-51000") must not squeeze the
      // action chips into a sliver — they wrap to the next line instead.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        AssistChip(onClick = { onToggle(!item.enabled) }, label = { Text(if (item.enabled) stringResource(R.string.nfqws2_item_enabled) else stringResource(R.string.nfqws2_item_disabled)) })
        AssistChip(onClick = {}, label = { Text(item.protocol) })
        if (item.ports.isNotBlank()) AssistChip(onClick = {}, label = { Text(stringResource(R.string.nfqws2_item_ports_fmt, item.ports)) })
        AssistChip(onClick = onPickStrategy, label = { Text(stringResource(R.string.nfqws2_item_strategy)) })
        AssistChip(onClick = onEditMatch, label = { Text(stringResource(R.string.nfqws2_item_match_title)) })
        AssistChip(onClick = onEditRaw, label = { Text(stringResource(R.string.nfqws2_item_raw_title)) })
      }
      if (item.lists.isNotEmpty()) {
        Text(stringResource(R.string.nfqws2_item_lists_title), style = MaterialTheme.typography.labelMedium)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          for (ref in item.lists) {
            AssistChip(onClick = {}, label = { Text("${ref.kind}: ${ref.name}") })
          }
        }
      } else {
        Text(stringResource(R.string.nfqws2_item_lists_none), style = MaterialTheme.typography.labelMedium)
      }
      if (item.strategy.isNotEmpty()) {
        Text(
          stringResource(R.string.nfqws2_item_strategy_lines),
          style = MaterialTheme.typography.labelMedium,
        )
        Text(
          item.strategy.joinToString("\n"),
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Text(
        stringResource(R.string.nfqws2_item_raw_lines),
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        item.raw,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun Nfqws2CutoverCard(
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onDone: () -> Unit,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  SectionCard(
    title = stringResource(R.string.nfqws2_cutover_title),
    desc = stringResource(R.string.nfqws2_cutover_hint),
    accent = Color(0xFFF59E0B),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        enabled = !busy,
        onClick = {
          if (busy) return@Button
          busy = true
          actions.runNfqws2ItemsCutover { ok, backupDir ->
            busy = false
            if (ok) {
              onDone()
              scope.launch {
                snackHost.showSnackbar(
                  ctx.getString(R.string.nfqws2_cutover_done, backupDir ?: "-")
                )
              }
            } else {
              scope.launch {
                snackHost.showSnackbar(ctx.getString(R.string.nfqws2_cutover_failed))
              }
            }
          }
        },
      ) { Text(stringResource(R.string.nfqws2_cutover_run)) }
    }
  }
}

@Composable
private fun Nfqws2ListsSection(
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var lists by remember(profile) { mutableStateOf<List<Nfqws2ListSummary>?>(null) }
  var editing by remember(profile) { mutableStateOf<Nfqws2ListSummary?>(null) }
  var creating by remember(profile) { mutableStateOf(false) }
  var deleteConfirmFor by remember(profile) { mutableStateOf<Nfqws2ListSummary?>(null) }

  fun reload() {
    actions.listNfqws2Lists(profile) { l -> lists = l }
  }
  LaunchedEffect(profile) { reload() }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  SectionCard(
    title = stringResource(R.string.nfqws2_list_editor_title),
    desc = stringResource(R.string.nfqws2_list_builtin_locked),
    accent = MaterialTheme.colorScheme.primary,
  ) {
    val current = lists
    if (current == null) {
      return@SectionCard
    }
    var searchQuery by remember(profile) { mutableStateOf("") }
    val filtered = remember(current, searchQuery) {
      if (searchQuery.isBlank()) current
      else current.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }
    if (current.size > 8) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.nfqws2_list_search)) },
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (l in filtered) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(Modifier.weight(1f)) {
            Text(l.name, style = MaterialTheme.typography.bodyMedium)
            Text(
              "${l.kind} · user ${l.userEntries} · final ${l.finalEntries}",
              style = MaterialTheme.typography.labelSmall,
            )
          }
          Row {
            IconButton(onClick = { editing = l }) {
              Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.nfqws2_list_edit))
            }
            IconButton(onClick = { deleteConfirmFor = l }) {
              Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.nfqws2_list_delete))
            }
          }
        }
      }
      TextButton(onClick = { creating = true }) { Text(stringResource(R.string.nfqws2_list_create)) }
    }
  }

  if (editing != null) {
    val l = editing!!
    var entries by remember(l) { mutableStateOf<String?>(null) }
    LaunchedEffect(l) {
      actions.readNfqws2List(profile, l.name) { triple ->
        entries = triple?.second?.joinToString("\n") ?: ""
      }
    }
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text("${stringResource(R.string.nfqws2_list_editor_title)}: ${l.name}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = entries ?: "",
            onValueChange = { entries = it },
            label = { Text(stringResource(R.string.nfqws2_list_tab_user)) },
            supportingText = { Text(stringResource(R.string.nfqws2_list_entries_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val name = l.name
          val kind = l.kind
          val text = entries.orEmpty()
          editing = null
          actions.saveNfqws2List(profile, name, text.lines().filter(String::isNotBlank), kind) { ok, err ->
            snack(if (ok) ctx.getString(R.string.common_saved) else err?.let { "${ctx.getString(R.string.common_apply_failed)}: $it" } ?: ctx.getString(R.string.common_apply_failed))
            reload()
          }
        }) { Text(stringResource(R.string.common_save)) }
      },
      dismissButton = {
        TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }

  if (creating) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("hostlist") }
    AlertDialog(
      onDismissRequest = { creating = false },
      title = { Text(stringResource(R.string.nfqws2_list_create)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.nfqws2_list_create_name)) },
            singleLine = true,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("hostlist" to R.string.nfqws2_list_kind_hostlist, "ipset" to R.string.nfqws2_list_kind_ipset).forEach { (k, res) ->
              FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(res)) })
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = name.isNotBlank(),
          onClick = {
            val n = name.trim()
            val k = kind
            creating = false
            actions.createNfqws2List(profile, n, k) { ok, err ->
              snack(if (ok) ctx.getString(R.string.common_saved) else err?.let { "${ctx.getString(R.string.common_apply_failed)}: $it" } ?: ctx.getString(R.string.common_apply_failed))
              reload()
            }
          },
        ) { Text(stringResource(R.string.common_create)) }
      },
      dismissButton = {
        TextButton(onClick = { creating = false }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }

  if (deleteConfirmFor != null) {
    val l = deleteConfirmFor!!
    AlertDialog(
      onDismissRequest = { deleteConfirmFor = null },
      title = { Text(stringResource(R.string.nfqws2_list_delete)) },
      text = { Text(stringResource(R.string.nfqws2_list_delete_confirm, l.name)) },
      confirmButton = {
        TextButton(onClick = {
          val name = l.name
          deleteConfirmFor = null
          actions.deleteNfqws2List(profile, name) { ok, err ->
            snack(if (ok) ctx.getString(R.string.common_deleted) else err?.let { "${ctx.getString(R.string.common_delete_failed)}: $it" } ?: ctx.getString(R.string.common_delete_failed))
            reload()
          }
        }) { Text(stringResource(R.string.common_confirm)) }
      },
      dismissButton = {
        TextButton(onClick = { deleteConfirmFor = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }
}
