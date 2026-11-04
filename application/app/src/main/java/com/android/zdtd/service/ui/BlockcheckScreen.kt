package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.diagnostics.blockcheck.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockcheckScreen(
    program: String,
    profile: String = "default",
    hostsFile: String,
    // Profile names that actually exist for `program`, enabled first. The
    // remembered profile can be stale (or the "default" placeholder, which is
    // not a real nfqws2 profile directory), and scanning a missing profile
    // fails with "Не удалось загрузить конфиг активного профиля".
    availableProfiles: List<String> = emptyList(),
    onClose: () -> Unit,
    actions: ZdtdActions? = null,
    snackHost: SnackbarHostState? = null,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by BlockcheckStore.state.collectAsStateWithLifecycle()
    val runner = remember { BlockcheckRunner(context) }

    // Re-entering the screen must resume whatever the store already holds: a
    // run that is still going, and the finished/stopped results of one that
    // completed while the user was elsewhere. The store is the source of
    // truth; the UI never owns the scan lifecycle.
    val resumed = remember { BlockcheckStore.state.value }
    val entryProgram = remember { resumed.program.ifBlank { program } }
    val entryProtocol = remember { resumed.protocol }


    // A remembered profile may no longer exist (profiles renamed/deleted) and
    // "default" is only a placeholder: fall back to a profile the daemon has.
    fun resolveProfile(candidate: String): String {
        if (availableProfiles.isEmpty()) return candidate
        return candidate.takeIf { it in availableProfiles }
            ?: availableProfiles.firstOrNull { it != "default" }
            ?: candidate
    }

    var selectedProgram by remember { mutableStateOf(resumed.program.ifBlank { program }) }
    var selectedProfile by remember { mutableStateOf(resolveProfile(resumed.profile.ifBlank { profile })) }
    var selectedProtocol by remember { mutableStateOf(resumed.protocol) }
    var selectedMode by remember { mutableStateOf(resumed.mode) }
    var allStrategies by remember { mutableStateOf<List<String>>(resumed.allStrategies) }
    // nfqws2 scans the shipped atomic catalog; nfqws v1 scans preset files.
    var catalogStrategies by remember { mutableStateOf<List<CatalogStrategy>>(emptyList()) }
    var hostFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedHostFile by remember { mutableStateOf(hostsFile) }
    var customDomain by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    // Re-scan mode: drop history-confirmed strategies from the batch.
    var skipTested by remember { mutableStateOf(false) }
    // Zapret22-style scan knobs (opt-in; a full ranked batch stays the
    // default): stop on the first confirmed strategy (its neighbors get
    // probed, the rest stays fresh), browser-like HTTP/2 probe.
    var stopFirst by remember { mutableStateOf(false) }
    var http2 by remember { mutableStateOf(false) }
    // nfqws2 always scans the shipped strategy catalog; the tester runs
    // without --config, exactly like the reference (zapretgui) scan engine.
    val targetChanged = selectedProgram != entryProgram || selectedProtocol != entryProtocol

    LaunchedEffect(selectedProgram) {
        // Only an explicit program change starts over. The first composition
        // keeps whatever the store holds: a live run, or the results of a run
        // that finished while the user was on another tab.
        if (selectedProgram != entryProgram) {
            if (BlockcheckController.isActive) return@LaunchedEffect
            selectedProfile = resolveProfile("default")
        }
        hostFiles = runner.listHostFiles()
    }

    // The nfqws2 candidate list comes from the protocol catalog, so it must be
    // reloaded whenever either the program or the protocol changes.
    LaunchedEffect(selectedProgram, selectedProtocol) {
        // nfqws2 scans the shipped atomic catalog. An older tester binary
        // without `catalog` (or a module that ships no catalog) returns
        // nothing: fall back to the installed preset files, which is what the
        // tester itself scans in that case.
        val catalog = if (selectedProgram == "nfqws2") runner.listCatalog(selectedProtocol) else emptyList()
        catalogStrategies = catalog
        allStrategies = if (catalog.isNotEmpty()) catalog.map { it.id } else runner.listStrategies(selectedProgram)
        // The store's candidate list belongs to a scan and must not be replaced
        // by the full idle catalog list: the run's order and mode cap matter,
        // and a finished run's list is what the results table renders against.
        if (targetChanged && !BlockcheckController.isActive) {
            // A different target invalidates the previous run's results.
            BlockcheckStore.reset()
            BlockcheckStore.update {
                it.copy(
                    allStrategies = allStrategies,
                    strategyTitles = catalogStrategies.associate { c -> c.id to c.name },
                )
            }
        }
    }

    val compact = rememberIsCompactWidth()
    val shortHeight = rememberIsShortHeight()

    fun startRun() {
        val isTcp = selectedProtocol == "tcp_https"
        var hostInput: String? = null
        if (isTcp) {
            hostInput = if (showCustom && customDomain.isNotBlank()) {
                val safe = customDomain.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
                val tmp = File(context.cacheDir, "custom_$safe")
                tmp.writeText(customDomain.trim())
                tmp.absolutePath
            } else selectedHostFile
        }
        val targetKey = if (isTcp) hostInput?.let { File(it).name } ?: "" else selectedProtocol
        coroutineScope.launch {
            BlockcheckController.start(
                context,
                BlockcheckRequest(
                    program = selectedProgram,
                    profile = selectedProfile,
                    protocol = selectedProtocol,
                    mode = selectedMode,
                    hostsFile = hostInput,
                    targetKey = targetKey,
                    allStrategies = allStrategies,
                    strategyTitles = catalogStrategies.associate { c -> c.id to c.name },
                    skipConfirmed = skipTested,
                    stopOnFirst = stopFirst,
                    http2 = http2,
                ),
            )
        }
    }

    fun stopRun() {
        BlockcheckController.stop()
    }

    // Catalog ids are opaque; show the human title from the catalog when known.
    fun strategyLabel(id: String): String =
        state.strategyTitles[id]
            ?: catalogStrategies.firstOrNull { it.id == id }?.name
            ?: id

    // Idempotent per-site apply on the existing daemon item API: find the
    // item already bound to this hostlist and swap its strategy, or append a
    // new item named after the site. Falls back to the global apply when the
    // profile preamble is missing a blob the strategy needs (the daemon
    // cannot add preamble blobs through the item editor).
    suspend fun applyAsSiteItem(
        a: ZdtdActions,
        exported: ExportedStrategy,
        hostsPath: String,
    ): Boolean {
        val profile = selectedProfile
        val items = suspendCancellableCoroutine<List<com.android.zdtd.service.MainViewModel.Nfqws2Item>?> { cont ->
            a.listNfqws2Items(profile) { cont.resume(it) }
        } ?: return false
        val listName = File(hostsPath).name
        val existing = items.firstOrNull { item ->
            item.lists.any { it.value == hostsPath || it.value == listName }
        }
        return if (existing != null) {
            suspendCancellableCoroutine { cont ->
                a.setNfqws2ItemStrategy(profile, existing.index, exported.itemLines, false) { cont.resume(it) }
            }
        } else {
            val template = buildList {
                // Zapret22 names the created profile after the site.
                val siteName = File(hostsPath).name
                    .removeSuffix(".txt").removePrefix("blockcheck_")
                add("--name=$siteName")
                addAll(exported.itemLines)
            }
            suspendCancellableCoroutine { cont ->
                a.appendNfqws2Item(profile, template, null) { cont.resume(it) }
            }
        }
    }

    // Apply a scan result. For nfqws2 catalog strategies this creates or
    // updates a per-site item in the active profile (zapret22 semantics:
    // "Применить" builds a profile for this site; re-apply updates it, no
    // copies). Other programs and preset-file candidates keep the global
    // strategicvar apply.
    fun applyStrategy(strategy: String) {
        val a = actions ?: return
        coroutineScope.launch {
            val isCatalog = catalogStrategies.any { it.id == strategy }
            // Custom-domain scans run against a cache temp file; a per-site
            // item must reference a persistent module list, so persist the
            // domain first and apply against that file.
            val customTarget =
                showCustom && customDomain.isNotBlank() && selectedProtocol == "tcp_https"
            var hostsPath =
                if (selectedProtocol == "tcp_https") selectedHostFile else null
            if (customTarget) {
                hostsPath = runner.ensureDomainList(customDomain)
            }
            val exported = if (isCatalog) {
                runner.exportStrategy(selectedProgram, selectedProtocol, strategy, hostsPath)
            } else {
                null
            }
            if (isCatalog && exported == null) {
                snackHost?.showSnackbar(context.getString(R.string.common_apply_failed))
                return@launch
            }
            val blobsOk = exported != null &&
                runner.profileHasBlobs(selectedProfile, exported.blobNames)
            val perItem = isCatalog && exported != null && blobsOk &&
                selectedProgram == "nfqws2" && hostsPath != null
            val ok = if (perItem && exported != null && hostsPath != null) {
                applyAsSiteItem(a, exported, hostsPath)
            } else {
                val fileName = exported?.file ?: strategy
                suspendCancellableCoroutine<Boolean> { cont ->
                    a.applyStrategicVariant(selectedProgram, selectedProfile, fileName) { ok ->
                        if (cont.isActive) cont.resumeWith(Result.success(ok))
                    }
                }
            }
            snackHost?.showSnackbar(
                if (ok) context.getString(R.string.common_applied_with_value, strategyLabel(strategy))
                else context.getString(R.string.common_apply_failed)
            )
        }
    }


    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (compact) 10.dp else 12.dp,
            end = if (compact) 10.dp else 12.dp,
            top = topContentPadding + if (shortHeight) 6.dp else 10.dp,
            bottom = bottomContentPadding + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (shortHeight) 8.dp else 10.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Column(Modifier.padding(if (compact) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(context.getString(R.string.blockcheck_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(selected = selectedProgram == "nfqws2", onClick = { selectedProgram = "nfqws2" }, label = { Text("nfqws2") })
                    }
                    Text(
                        context.getString(R.string.blockcheck_protocol_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(selected = selectedProtocol == "tcp_https", onClick = { selectedProtocol = "tcp_https" }, label = { Text(context.getString(R.string.blockcheck_protocol_tcp_https)) })
                        FilterChip(selected = selectedProtocol == "stun_voice", onClick = { selectedProtocol = "stun_voice" }, label = { Text(context.getString(R.string.blockcheck_protocol_stun_voice)) })
                        FilterChip(selected = selectedProtocol == "udp_games", onClick = { selectedProtocol = "udp_games" }, label = { Text(context.getString(R.string.blockcheck_protocol_udp_games)) })
                    }
                    Text(
                        context.getString(R.string.blockcheck_mode_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(selected = selectedMode == "quick", onClick = { selectedMode = "quick" }, label = { Text(context.getString(R.string.blockcheck_mode_quick)) })
                        FilterChip(selected = selectedMode == "standard", onClick = { selectedMode = "standard" }, label = { Text(context.getString(R.string.blockcheck_mode_standard)) })
                        FilterChip(selected = selectedMode == "full", onClick = { selectedMode = "full" }, label = { Text(context.getString(R.string.blockcheck_mode_full)) })
                        FilterChip(selected = skipTested, onClick = { skipTested = !skipTested }, label = { Text(context.getString(R.string.blockcheck_skip_tested)) })
                        FilterChip(selected = stopFirst, onClick = { stopFirst = !stopFirst }, label = { Text(context.getString(R.string.blockcheck_stop_first)) })
                        FilterChip(selected = http2, onClick = { http2 = !http2 }, label = { Text(context.getString(R.string.blockcheck_http2)) })
                    }
                    Text(
                        context.getString(R.string.blockcheck_target_profile, selectedProgram, selectedProfile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    // Start sits in the FIRST card, above the (possibly very
                    // long) host-chip list: scrolling past hundreds of chips to
                    // reach the run button is unusable on a phone.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { startRun() },
                            enabled = !state.isRunning && (selectedProtocol != "tcp_https" || !showCustom || customDomain.isNotBlank()),
                        ) { Text(context.getString(R.string.blockcheck_start)) }
                        if (state.isRunning || state.isFinished || state.isError) {
                            OutlinedButton(onClick = { BlockcheckStore.reset() }) {
                                Text(context.getString(R.string.blockcheck_reset))
                            }
                        }
                    }
                }
            }
        }

        if (state.isRunning) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = when {
                                state.phase == "network" -> context.getString(R.string.blockcheck_phase_network)
                                state.phase == "baseline" -> context.getString(R.string.blockcheck_baseline)
                                state.phase == "pass_control" -> context.getString(R.string.blockcheck_phase_pass_control)
                                state.phase == "strategies" && state.currentStrategyIndex >= 0 ->
                                    context.getString(R.string.blockcheck_testing_fmt, state.currentStrategyIndex + 1, state.totalStrategies)
                                else -> context.getString(R.string.blockcheck_starting)
                            },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        )
                        val progress = if (state.totalStrategies > 0 && state.currentStrategyIndex >= 0)
                            (state.currentStrategyIndex + 1).toFloat() / state.totalStrategies else 0f
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { stopRun() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text(context.getString(R.string.blockcheck_stop)) }
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selectedProtocol == "tcp_https") {
                        Text(context.getString(R.string.blockcheck_hosts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilterChip(selected = !showCustom, onClick = { showCustom = false }, label = { Text(context.getString(R.string.blockcheck_from_list)) })
                            FilterChip(selected = showCustom, onClick = { showCustom = true }, label = { Text(context.getString(R.string.blockcheck_custom_domain)) })
                        }
                        if (showCustom) {
                            OutlinedTextField(
                                value = customDomain, onValueChange = { customDomain = it },
                                label = { Text(context.getString(R.string.blockcheck_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (!state.isRunning) {
                            // Mid-run the list is not selectable anyway, and a
                            // few hundred chips would bury the progress card
                            // (with Stop) far below the fold.
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                hostFiles.forEach { file ->
                                    val path = "/data/adb/modules/ZDT-D/strategic/list/$file"
                                    FilterChip(
                                        selected = selectedHostFile == path,
                                        onClick = { selectedHostFile = path },
                                        label = { Text(file.removeSuffix(".txt")) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    } else {
                        // UDP protocols probe a fixed target set (mirrors the
                        // tester consts in rust/nfqws-tester/src/main.rs).
                        val targetNames = when (selectedProtocol) {
                            "stun_voice" -> listOf(
                                "stun.l.google.com:19302",
                                "stun.cloudflare.com:3478",
                                "global.stun.twilio.com:3478",
                                "stun.telegram.org:3478",
                                "stun.voip.telegram.org:3478",
                            )
                            else -> listOf(
                                "Rust A2S (205.178.168.170:28015)",
                                "CS A2S (46.174.55.234:27015)",
                                "Bedrock CubeCraft (play.cubecraft.net:19132)",
                            )
                        }
                        Text(
                            context.getString(R.string.blockcheck_udp_targets_fmt, targetNames.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }


        if (state.isFinished && state.stoppedManually) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        context.getString(R.string.blockcheck_stopped),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        if (state.isError) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(context.getString(R.string.blockcheck_error_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        state.errorMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }

        // During a run this is live progress; after finish the summary below
        // is the single result source (no repeated strategy list).
        if (!state.isFinished || state.stoppedManually) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.blockcheck_strategies_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (allStrategies.isEmpty()) {
                        Text(context.getString(R.string.blockcheck_no_strategies), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    } else {
                        // During/after a run show the tester's actual order
                        // (history-ordered, mode-capped); idle shows all.
                        val displayStrategies = if (state.isRunning) {
                            state.allStrategies.filter { s ->
                                s == state.currentStrategy ||
                                    state.workingStrategies.contains(s) ||
                                    state.unstableStrategies.contains(s) ||
                                    state.skippedStrategies.contains(s)
                            }
                        } else {
                            allStrategies
                        }
                        displayStrategies.forEach { s ->
                            val res = state.results.firstOrNull { it.strategy == s }
                            val status = when {
                                state.isRunning && state.currentStrategy == s -> if (state.phase == "strategies") "testing" else "queued"
                                state.workingStrategies.contains(s) -> "works"
                                state.unstableStrategies.contains(s) -> when {
                                    res?.verdict == "not_counted" -> "not_counted"
                                    res?.noBaselineBlock == true -> "no_baseline_block"
                                    else -> "unstable"
                                }
                                state.failedStrategies.contains(s) -> "failed"
                                state.skippedStrategies.contains(s) -> "skipped"
                                else -> if (state.isRunning || state.isFinished) "queued" else ""
                            }
                            val shape = RoundedCornerShape(12.dp)
                            val bgColor = when (status) {
                                "testing" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                "works" -> Color(0xFF22C55E).copy(alpha = 0.08f)
                                "unstable" -> Color(0xFFF59E0B).copy(alpha = 0.10f)
                                "failed" -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                "not_counted", "no_baseline_block", "skipped" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else -> Color.Transparent
                            }
                            if (bgColor != Color.Transparent) {
                                Surface(shape = shape, color = bgColor) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                strategyLabel(s),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (status == "testing") FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                            // Gradient breakdown: how many baseline-blocked hosts
                                            // this strategy opened vs failed to open.
                                            if (res != null && res.baselineBlocked > 0 && status != "testing") {
                                                Spacer(Modifier.height(3.dp))
                                                LinearProgressIndicator(
                                                    progress = (res.openedPct ?: 0.0).toFloat() / 100f,
                                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                                )
                                            }
                                        }
                                        if (status == "testing") {
                                            Spacer(Modifier.width(8.dp))
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        }
                                        if (status == "works" && actions != null) {
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(
                                                onClick = { applyStrategy(s) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) { Text(context.getString(R.string.blockcheck_apply), style = MaterialTheme.typography.labelSmall) }
                                            var verifying by remember(s) { mutableStateOf(false) }
                                            var browserOk by remember(s) { mutableStateOf<Boolean?>(null) }
                                            TextButton(
                                                enabled = !verifying,
                                                onClick = {
                                                    verifying = true
                                                    coroutineScope.launch {
                                                        // Real-browser check: apply first
                                                        // (WebView rides the live engine), then
                                                        // load the first scanned host headless.
                                                        applyStrategy(s)
                                                        val host = state.hosts.firstOrNull() ?: ""
                                                        browserOk = if (host.isBlank()) {
                                                            null
                                                        } else {
                                                            BlockcheckBrowserVerify.verify(context, host, s).ok
                                                        }
                                                        verifying = false
                                                        snackHost?.showSnackbar(
                                                            when (browserOk) {
                                                                true -> context.getString(R.string.blockcheck_browser_ok)
                                                                false -> context.getString(R.string.blockcheck_browser_fail)
                                                                null -> context.getString(R.string.blockcheck_browser_skip)
                                                            }
                                                        )
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                if (verifying) {
                                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Text(
                                                        context.getString(
                                                            when (browserOk) {
                                                                true -> R.string.blockcheck_browser_ok
                                                                false -> R.string.blockcheck_browser_fail
                                                                else -> R.string.blockcheck_browser_verify
                                                            }
                                                        ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            }
                                        }
                                        if (status.isNotEmpty() && status != "queued" && status != "testing") {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                when (status) {
                                                    "works" -> res?.takeIf { it.attemptsTotal > 0 }
                                                        ?.let { context.getString(R.string.blockcheck_works_fmt, it.attemptsOk, it.attemptsTotal) }
                                                        ?: context.getString(R.string.blockcheck_works)
                                                    "unstable" -> context.getString(R.string.blockcheck_unstable)
                                                    "not_counted" -> context.getString(R.string.blockcheck_not_counted)
                                                    "no_baseline_block" -> context.getString(R.string.blockcheck_no_baseline_block)
                                                    "failed" -> context.getString(R.string.blockcheck_failed)
                                                    "skipped" -> context.getString(R.string.blockcheck_skipped)
                                                    else -> ""
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (status) {
                                                    "works" -> Color(0xFF22C55E)
                                                    "unstable" -> Color(0xFFF59E0B)
                                                    "failed" -> MaterialTheme.colorScheme.error
                                                    "not_counted", "no_baseline_block", "skipped" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    strategyLabel(s), modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (state.isFinished && !state.stoppedManually) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.forced) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                context.getString(R.string.blockcheck_forced_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    // Fastest confirmed strategy first.
                    val workingResults = state.results
                        .filter { it.isWorking }
                        .sortedBy { it.timeMs ?: Double.MAX_VALUE }
                    if (workingResults.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_working_count_fmt, workingResults.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF22C55E))
                                if (actions != null) {
                                    Button(
                                        onClick = { applyStrategy(workingResults.first().strategy) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(context.getString(R.string.blockcheck_apply_best)) }
                                }
                                workingResults.forEach { r ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(strategyLabel(r.strategy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                r.timeMs?.let {
                                                    Text("%.0f ms".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                                }
                                            }
                                            if (actions != null) {
                                                Spacer(Modifier.width(8.dp))
                                                TextButton(onClick = { applyStrategy(r.strategy) }) {
                                                    Text(context.getString(R.string.blockcheck_apply), style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.unstableStrategies.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_unstable_count_fmt, state.unstableStrategies.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
                                state.unstableStrategies.forEach { s ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                        Text(strategyLabel(s), modifier = Modifier.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (state.failedStrategies.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_failed_count_fmt, state.failedStrategies.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (workingResults.isEmpty() && state.unstableStrategies.isEmpty() && state.failedStrategies.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(context.getString(R.string.blockcheck_no_strategies_tested), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            }
                        }
                    }
                }
            }
        }
    }

    // The tester blocks on stdin until one of these is pressed.
    state.pendingConfirm?.let { question ->
        AlertDialog(
            onDismissRequest = { /* an explicit choice is required */ },
            text = {
                Text(
                    context.getString(
                        if (question == "baseline_open") R.string.blockcheck_confirm_baseline_open
                        else R.string.blockcheck_confirm_pass_opened
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    BlockcheckController.answerConfirm(true)
                    BlockcheckStore.update { it.copy(pendingConfirm = null) }
                }) { Text(context.getString(R.string.blockcheck_confirm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    BlockcheckController.answerConfirm(false)
                    BlockcheckStore.update { it.copy(pendingConfirm = null) }
                }) { Text(context.getString(R.string.blockcheck_stop)) }
            },
        )
    }
}
