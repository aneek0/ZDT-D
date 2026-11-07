package com.android.zdtd.service.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
  screenScroll: ScrollState,
  viewport: Rect,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()

  var items by remember(profile) { mutableStateOf<List<Nfqws2Item>?>(null) }
  var strategies by remember { mutableStateOf<Map<String, List<Nfqws2StrategyOption>>>(emptyMap()) }
  var busy by remember(profile) { mutableStateOf(false) }
  var strategyPickerFor by remember { mutableStateOf<Nfqws2Item?>(null) }
  var searchQuery by remember(profile) { mutableStateOf("") }

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

  // One filter for the whole tab: it matches element names and hostlist/ipset
  // names. A query that names the profile itself (e.g. "default") is not a
  // section filter, so it keeps everything visible.
  val query = searchQuery.trim()
  val activeQuery =
    if (query.isNotEmpty() && !profile.contains(query, ignoreCase = true)) query else ""
  OutlinedTextField(
    value = searchQuery,
    onValueChange = { searchQuery = it },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text(stringResource(R.string.nfqws2_list_search)) },
  )

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

  Nfqws2ListsSection(
    profile = profile,
    actions = actions,
    snackHost = snackHost,
    query = activeQuery,
  )

  // Plain Column: parent ProfileScreen already wraps content in
  // Column(verticalScroll); LazyColumn there crashes with infinite-height
  // constraints. Item count is small (tens), laziness buys nothing.
  Text(
    stringResource(R.string.nfqws2_item_sections_hint),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
  )

  val visibleItems = remember(list, activeQuery) {
    if (activeQuery.isEmpty()) list
    else list.filter { it.displayName.contains(activeQuery, ignoreCase = true) }
  }

  // ---- drag-to-reorder (handle only) --------------------------------------
  val density = LocalDensity.current
  val itemSpacing = 10.dp
  val itemSpacingPx = with(density) { itemSpacing.toPx() }
  val cardShape = RoundedCornerShape(if (rememberIsCompactWidth()) 20.dp else 24.dp)
  // Autoscroll begins this close to a viewport edge and reaches full speed at
  // the edge itself.
  val edgeZonePx = with(density) { 96.dp.toPx() }
  val autoScrollPxPerSec = with(density) { 700.dp.toPx() }

  val cardHeights = remember { mutableStateMapOf<Nfqws2Item, Float>() }
  // Window tops feed gesture math only; keeping them out of snapshot state
  // avoids a recomposition on every scroll frame.
  val cardWindowTops = remember { HashMap<Nfqws2Item, Float>() }

  var dragItem by remember { mutableStateOf<Nfqws2Item?>(null) }
  var dragTarget by remember { mutableStateOf(-1) }
  var dragOffset by remember { mutableStateOf(0f) }
  var dragPointerY by remember { mutableStateOf(0f) }

  fun dragIndex(): Int =
    dragItem?.let { dragged -> visibleItems.indexOfFirst { it === dragged } } ?: -1

  fun slotTop(pos: Int): Float {
    var y = 0f
    for (i in 0 until pos) y += (cardHeights[visibleItems[i]] ?: 0f) + itemSpacingPx
    return y
  }

  // The card floats over the list; the slot whose midpoint its centre passed
  // becomes the drop target, and the cards in between step aside.
  fun updateTarget() {
    val from = dragIndex()
    if (from < 0) return
    val draggedCenter =
      slotTop(from) + (cardHeights[visibleItems[from]] ?: 0f) / 2f + dragOffset
    var target = from
    for (t in from + 1 until visibleItems.size) {
      val center = slotTop(t) + (cardHeights[visibleItems[t]] ?: 0f) / 2f
      if (draggedCenter > center) target = t else break
    }
    for (t in from - 1 downTo 0) {
      val center = slotTop(t) + (cardHeights[visibleItems[t]] ?: 0f) / 2f
      if (draggedCenter < center) target = t else break
    }
    dragTarget = target
  }

  fun resetDrag() {
    dragItem = null
    dragTarget = -1
    dragOffset = 0f
  }

  fun dropDrag() {
    val from = dragIndex()
    val to = dragTarget
    resetDrag()
    if (from < 0 || to < 0 || to == from || busy) return
    val fromIndex = list.indexOfFirst { it === visibleItems[from] }
    val toIndex = list.indexOfFirst { it === visibleItems[to] }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
    // Optimistic local reorder, exactly one daemon call, rollback on failure.
    val snapshot = list
    items = list.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    mutate { done ->
      actions.moveNfqws2Item(profile, fromIndex, toIndex - fromIndex) { ok ->
        if (!ok) {
          items = snapshot
          reload()
        }
        done(ok)
      }
    }
  }

  // Edge autoscroll: speed grows with how deep the pointer is inside the edge
  // zone, and every scrolled pixel is folded into dragOffset so the card stays
  // under the finger and the drop target keeps tracking it.
  LaunchedEffect(dragItem) {
    if (dragItem == null) return@LaunchedEffect
    if (viewport.width <= 0f || viewport.height <= 0f) return@LaunchedEffect
    var previousFrame = withFrameNanos { it }
    while (true) {
      val frame = withFrameNanos { it }
      val dt = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
      previousFrame = frame
      val topEdge = viewport.top + edgeZonePx
      val bottomEdge = viewport.bottom - edgeZonePx
      val speed = when {
        dragPointerY < topEdge ->
          -autoScrollPxPerSec * ((topEdge - dragPointerY) / edgeZonePx).coerceIn(0f, 1f)
        dragPointerY > bottomEdge ->
          autoScrollPxPerSec * ((dragPointerY - bottomEdge) / edgeZonePx).coerceIn(0f, 1f)
        else -> 0f
      }
      if (speed == 0f) continue
      val scrolled = screenScroll.dispatchRawDelta(speed * dt)
      if (scrolled != 0f) {
        dragOffset += scrolled
        updateTarget()
      }
    }
  }

  Column(
    verticalArrangement = Arrangement.spacedBy(itemSpacing),
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (visibleItems.isEmpty()) {
      Text(
        stringResource(
          if (list.isEmpty()) R.string.nfqws2_items_empty else R.string.nfqws2_search_no_matches
        ),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    visibleItems.forEachIndexed { pos, item ->
      val index = list.indexOfFirst { it === item }
      val dragging = dragItem === item
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .onGloballyPositioned { coords ->
              val height = coords.size.height.toFloat()
              if (cardHeights[item] != height) cardHeights[item] = height
              cardWindowTops[item] = coords.boundsInWindow().top
            }
            .graphicsLayer {
              val from = visibleItems.indexOfFirst { it === dragItem }
              translationY =
                when {
                  dragging -> dragOffset
                  from < 0 -> 0f
                  dragTarget > from && pos in (from + 1)..dragTarget ->
                    -((cardHeights[visibleItems[from]] ?: 0f) + itemSpacingPx)
                  dragTarget in 0 until from && pos in dragTarget until from ->
                    (cardHeights[visibleItems[from]] ?: 0f) + itemSpacingPx
                  else -> 0f
                }
              if (dragging) {
                shape = cardShape
                shadowElevation = 12.dp.toPx()
              }
            },
      ) {
        ItemEditorCard(
          item = item,
          index = index,
          count = list.size,
          busy = busy,
          dragModifier =
            Modifier.pointerInput(item, busy) {
              if (busy) return@pointerInput
              detectDragGestures(
                onDragStart = { offset ->
                  dragItem = item
                  dragTarget = pos
                  dragOffset = 0f
                  dragPointerY = (cardWindowTops[item] ?: 0f) + offset.y
                  updateTarget()
                },
                onDragEnd = { dropDrag() },
                onDragCancel = { resetDrag() },
              ) { change, dragAmount ->
                change.consume()
                dragOffset += dragAmount.y
                dragPointerY += dragAmount.y
                updateTarget()
              }
            },
          onToggle = { enabled -> mutate { done -> actions.setNfqws2ItemEnabled(profile, index, enabled, done) } },
          onDuplicate = { mutate { done -> actions.duplicateNfqws2Item(profile, index, done) } },
          onDelete = { mutate { done -> actions.deleteNfqws2Item(profile, index, done) } },
          onMove = { delta -> mutate { done -> actions.moveNfqws2Item(profile, index, delta, done) } },
          onPickStrategy = { strategyPickerFor = item },
          onEditMatch = { matchEditorFor = item },
          onEditRaw = { rawEditorFor = item },
        )
      }
    }
  }

  if (strategyPickerFor != null) {
    val current = strategyPickerFor!!
    val shortHeight = rememberIsShortHeight()
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
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = pickerQuery,
            onValueChange = { pickerQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.nfqws2_list_search)) },
          )
          // Bounded viewport: the list itself owns the vertical drag, so a
          // fling started anywhere in the list scrolls it. Each option is a
          // full-width clickable row, so the whole row is the tap target
          // instead of only the text block.
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(
                min = if (shortHeight) 200.dp else 240.dp,
                max = if (shortHeight) 360.dp else 480.dp,
              ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
          ) {
            for ((family, options) in pickerFiltered) {
              item(key = "family:$family") {
                Text(
                  family,
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              items(options.size, key = { "option:$family/$it" }) { i ->
                val opt = options[i]
                StrategyPickRow(
                  preset = opt.preset,
                  name = opt.name,
                  onClick = {
                    strategyPickerFor = null
                    mutate { done -> actions.setNfqws2ItemStrategy(profile, current.index, opt.lines, false, done) }
                  },
                )
              }
            }
            if (pickerFiltered.isEmpty()) {
              item(key = "none") { Text(stringResource(R.string.nfqws2_items_strategies_none)) }
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

/**
 * One strategy choice: a full-width row so the whole line — not only the two
 * text lines — is the tap target and part of the list's drag surface.
 * `name` is primary content and must read as normal on-surface text; only the
 * preset caption is de-emphasised.
 */
@Composable
private fun StrategyPickRow(
  preset: String,
  name: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        preset,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditorCard(
  item: Nfqws2Item,
  index: Int,
  count: Int,
  busy: Boolean,
  dragModifier: Modifier,
  onToggle: (Boolean) -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onMove: (Int) -> Unit,
  onPickStrategy: () -> Unit,
  onEditMatch: () -> Unit,
  onEditRaw: () -> Unit,
) {
  // Collapsed by default. Keyed on the element identity so the open/closed
  // state survives recomposition and configuration changes.
  var expanded by rememberSaveable(item.name ?: "item ${item.index}") { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  val accent = if (item.enabled) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline

  SectionCard(
    title = item.displayName,
    desc = null,
    accent = accent,
    modifier = if (expanded) Modifier else Modifier.clickable(enabled = !busy) { expanded = true },
    icon = {
      // The only drag surface of the card: dragging anywhere else still
      // scrolls the screen.
      Box(
        modifier = Modifier.fillMaxSize().then(if (busy) Modifier else dragModifier),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.DragHandle,
          contentDescription = stringResource(R.string.nfqws2_item_drag),
        )
      }
    },
    trailing = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(
            if (item.enabled) R.string.nfqws2_item_enabled else R.string.nfqws2_item_disabled
          ),
          style = MaterialTheme.typography.labelSmall,
          color = if (item.enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
              if (expanded) R.string.nfqws2_item_collapse else R.string.nfqws2_item_expand
            ),
          )
        }
        Box {
          IconButton(enabled = !busy, onClick = { menuOpen = true }) {
            Icon(
              Icons.Filled.MoreVert,
              contentDescription = stringResource(R.string.nfqws2_item_more),
            )
          }
          // Reordering is drag-first; these entries stay as an accessible
          // fallback for the collapsed row.
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.nfqws2_item_move_up)) },
              leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
              enabled = !busy && index > 0,
              onClick = {
                menuOpen = false
                onMove(-1)
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.nfqws2_item_move_down)) },
              leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
              enabled = !busy && index < count - 1,
              onClick = {
                menuOpen = false
                onMove(1)
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.nfqws2_item_duplicate)) },
              leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
              enabled = !busy,
              onClick = {
                menuOpen = false
                onDuplicate()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.nfqws2_item_delete)) },
              leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
              enabled = !busy,
              onClick = {
                menuOpen = false
                onDelete()
              },
            )
          }
        }
      }
    },
    content =
      if (expanded) {
        {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // FlowRow: a wide ports chip ("50000-51000") must not squeeze the
            // action chips into a sliver — they wrap to the next line instead.
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              AssistChip(onClick = { onToggle(!item.enabled) }, label = { Text(stringResource(if (item.enabled) R.string.nfqws2_item_enabled else R.string.nfqws2_item_disabled)) })
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
      } else {
        null
      },
  )
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

/**
 * Lists shown in the Items screen block before it collapses. Counted over the
 * whole block (hostlists and ipset lists together), because that is what the
 * block renders.
 */
private const val NFQWS2_LISTS_COLLAPSE_THRESHOLD = 10

@Composable
private fun Nfqws2ListsSection(
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  query: String,
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var lists by remember(profile) { mutableStateOf<List<Nfqws2ListSummary>?>(null) }
  var editing by remember(profile) { mutableStateOf<Nfqws2ListSummary?>(null) }
  var creating by remember(profile) { mutableStateOf(false) }
  var deleteConfirmFor by remember(profile) { mutableStateOf<Nfqws2ListSummary?>(null) }
  // Long list blocks start collapsed; an explicit expand is remembered per
  // profile so it survives tab switches and rotation.
  var listsExpanded by rememberSaveable(profile) { mutableStateOf(false) }

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
    // Search runs over the whole block, never over the collapsed slice.
    val filtered =
      remember(current, query) {
        if (query.isEmpty()) current
        else current.filter { it.name.contains(query, ignoreCase = true) }
      }
    val searching = query.isNotEmpty()
    val collapsible = current.size > NFQWS2_LISTS_COLLAPSE_THRESHOLD
    // A search always looks at the whole block, so a match that sits below the
    // collapse cut still shows up without expanding first.
    val visible =
      if (searching || !collapsible || listsExpanded) {
        filtered
      } else {
        filtered.take(NFQWS2_LISTS_COLLAPSE_THRESHOLD)
      }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (l in visible) {
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
      if (current.isNotEmpty() && filtered.isEmpty()) {
        Text(
          stringResource(R.string.nfqws2_search_no_matches),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      // No toggle while searching: matches are already shown in full, so the
      // expand choice only makes sense over the unfiltered block.
      if (collapsible && !searching) {
        TextButton(onClick = { listsExpanded = !listsExpanded }) {
          Icon(
            if (listsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
          )
          Spacer(Modifier.width(6.dp))
          if (listsExpanded) {
            Text(stringResource(R.string.nfqws2_lists_show_less))
          } else {
            Text(stringResource(R.string.nfqws2_lists_show_all, current.size))
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
