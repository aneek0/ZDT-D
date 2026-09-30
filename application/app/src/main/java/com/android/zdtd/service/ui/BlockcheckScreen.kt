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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

@Composable
fun BlockcheckScreen(
    program: String,
    profile: String = "default",
    hostsFile: String,
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

    var selectedProgram by remember { mutableStateOf(program) }
    var selectedProfile by remember { mutableStateOf(profile) }
    var selectedProtocol by remember { mutableStateOf("tcp_https") }
    var selectedMode by remember { mutableStateOf("full") }
    var allStrategies by remember { mutableStateOf<List<String>>(emptyList()) }
    var hostFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedHostFile by remember { mutableStateOf(hostsFile) }
    var customDomain by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var stoppedManually by remember { mutableStateOf(false) }
    var runTargetKey by remember { mutableStateOf("") }

    LaunchedEffect(selectedProgram) {
        runJob?.cancel()
        runJob = null
        stoppedManually = false
        BlockcheckStore.reset()
        selectedProfile = "default"
        hostFiles = runner.listHostFiles()
        allStrategies = runner.listStrategies(selectedProgram)
        BlockcheckStore.update { it.copy(allStrategies = allStrategies) }
    }

    val compact = rememberIsCompactWidth()
    val shortHeight = rememberIsShortHeight()

    fun startRun() {
        val isTcp = selectedProtocol == "tcp_https"
        var hostInput: String? = null
        if (isTcp) {
            hostInput = if (showCustom && customDomain.isNotBlank()) {
                // Temp file name doubles as the history target key on both
                // sides (app + tester derive basename from the --hosts path).
                val safe = customDomain.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
                val tmp = File(context.cacheDir, "custom_$safe")
                tmp.writeText(customDomain.trim())
                tmp.absolutePath
            } else selectedHostFile
        }
        runTargetKey = if (isTcp) {
            hostInput?.let { File(it).name } ?: ""
        } else {
            selectedProtocol
        }
        stoppedManually = false
        BlockcheckStore.reset()
        BlockcheckStore.update {
            it.copy(
                program = selectedProgram,
                protocol = selectedProtocol,
                mode = selectedMode,
                allStrategies = allStrategies,
                isRunning = true,
            )
        }
        runJob = coroutineScope.launch {
            runner.run(
                program = selectedProgram,
                hostsFile = hostInput,
                protocol = selectedProtocol,
                mode = selectedMode,
                historyPath = BlockcheckHistory(context).path(),
            ).collect { event ->
                when (event) {
                    is BlockcheckEvent.Started -> BlockcheckStore.replace(
                        event.session.copy(
                            program = selectedProgram,
                            allStrategies = event.session.allStrategies.ifEmpty { allStrategies },
                            isRunning = true,
                        )
                    )
                    is BlockcheckEvent.Phase -> BlockcheckStore.update { it.copy(phase = event.phase) }
                    is BlockcheckEvent.StrategyStarted -> BlockcheckStore.update { it.copy(currentStrategy = event.strategy, currentStrategyIndex = event.index) }
                    is BlockcheckEvent.StrategyResult -> {
                        BlockcheckStore.update {
                            val r = event.result
                            val w = if (r.isWorking) it.workingStrategies + r.strategy else it.workingStrategies
                            val u = if (r.isUnstable || r.noBaselineBlock || r.verdict == "not_counted") it.unstableStrategies + r.strategy else it.unstableStrategies
                            val f = if (!r.isWorking && !r.isUnstable && !r.noBaselineBlock && r.verdict != "not_counted") it.failedStrategies + r.strategy else it.failedStrategies
                            it.copy(workingStrategies = w, failedStrategies = f, unstableStrategies = u, results = it.results + r)
                        }
                    }
                    is BlockcheckEvent.StrategySkipped -> BlockcheckStore.update { it.copy(skippedStrategies = it.skippedStrategies + event.strategy) }
                    is BlockcheckEvent.StrategyError -> BlockcheckStore.update { it.copy(failedStrategies = it.failedStrategies + event.strategy) }
                    is BlockcheckEvent.ConfirmNeeded -> BlockcheckStore.update { it.copy(pendingConfirm = event.question) }
                    is BlockcheckEvent.Fatal -> {
                        val resId = when (event.stopKind) {
                            "no_internet" -> R.string.blockcheck_fatal_no_internet
                            "dns_stub" -> R.string.blockcheck_fatal_dns_stub
                            "address_block" -> R.string.blockcheck_fatal_address_block
                            "network_lost" -> R.string.blockcheck_fatal_network_lost
                            else -> null
                        }
                        val msg = resId?.let { context.getString(it) } ?: event.message
                        BlockcheckStore.update { it.copy(isError = true, errorMessage = msg, isRunning = false) }
                    }
                    is BlockcheckEvent.Finished -> {
                        val unstableNow = BlockcheckStore.state.value.results
                            .filter { it.isUnstable || it.noBaselineBlock || it.verdict == "not_counted" }
                            .map { it.strategy }
                        BlockcheckStore.update {
                            it.copy(
                                workingStrategies = event.working,
                                failedStrategies = event.failed.filter { s -> s !in unstableNow },
                                unstableStrategies = unstableNow,
                                isFinished = true,
                                isRunning = false,
                                forced = event.forced,
                                stopKind = event.stopKind,
                                pendingConfirm = null,
                            )
                        }
                        if (event.stopKind == "declined") stoppedManually = true
                        // Informational (forced) runs never touch history.
                        if (!event.forced && event.stopKind == null) {
                            BlockcheckHistory(context).record(
                                program = selectedProgram,
                                protocol = selectedProtocol,
                                targetKey = runTargetKey,
                                working = event.working,
                                failed = event.failed,
                            )
                        }
                    }
                    is BlockcheckEvent.Error -> BlockcheckStore.update { it.copy(isError = true, errorMessage = event.message, isRunning = false) }
                    else -> {}
                }
            }
        }
    }

    fun stopRun() {
        runJob?.cancel()
        runJob = null
        stoppedManually = true
        BlockcheckStore.update {
            it.copy(isRunning = false, isFinished = true, phase = "stopped")
        }
    }

    fun applyStrategy(strategy: String) {
        val a = actions ?: return
        coroutineScope.launch {
            // No hostlists passed: the daemon reuses the hostlists already
            // selected on this profile's config, so applying from blockcheck
            // keeps them intact.
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                a.applyStrategicVariant(selectedProgram, selectedProfile, strategy) { ok ->
                    if (cont.isActive) cont.resumeWith(Result.success(ok))
                }
            }
            snackHost?.showSnackbar(
                if (ok) context.getString(R.string.common_applied_with_value, strategy.removeSuffix(".txt"))
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Column(Modifier.padding(if (compact) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(context.getString(R.string.blockcheck_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = selectedProgram == "nfqws", onClick = { selectedProgram = "nfqws" }, label = { Text("nfqws") })
                        FilterChip(selected = selectedProgram == "nfqws2", onClick = { selectedProgram = "nfqws2" }, label = { Text("nfqws2") })
                    }
                    Text(
                        context.getString(R.string.blockcheck_protocol_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = selectedProtocol == "tcp_https", onClick = { selectedProtocol = "tcp_https" }, label = { Text(context.getString(R.string.blockcheck_protocol_tcp_https)) })
                        FilterChip(selected = selectedProtocol == "stun_voice", onClick = { selectedProtocol = "stun_voice" }, label = { Text(context.getString(R.string.blockcheck_protocol_stun_voice)) })
                        FilterChip(selected = selectedProtocol == "udp_games", onClick = { selectedProtocol = "udp_games" }, label = { Text(context.getString(R.string.blockcheck_protocol_udp_games)) })
                    }
                    Text(
                        context.getString(R.string.blockcheck_mode_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = selectedMode == "quick", onClick = { selectedMode = "quick" }, label = { Text(context.getString(R.string.blockcheck_mode_quick)) })
                        FilterChip(selected = selectedMode == "standard", onClick = { selectedMode = "standard" }, label = { Text(context.getString(R.string.blockcheck_mode_standard)) })
                        FilterChip(selected = selectedMode == "full", onClick = { selectedMode = "full" }, label = { Text(context.getString(R.string.blockcheck_mode_full)) })
                    }
                    Text(
                        context.getString(R.string.blockcheck_target_profile, selectedProgram, selectedProfile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selectedProtocol == "tcp_https") {
                        Text(context.getString(R.string.blockcheck_hosts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = !showCustom, onClick = { showCustom = false }, label = { Text(context.getString(R.string.blockcheck_from_list)) })
                            FilterChip(selected = showCustom, onClick = { showCustom = true }, label = { Text(context.getString(R.string.blockcheck_custom_domain)) })
                        }
                        if (showCustom) {
                            OutlinedTextField(
                                value = customDomain, onValueChange = { customDomain = it },
                                label = { Text(context.getString(R.string.blockcheck_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { startRun() },
                            enabled = !state.isRunning && (selectedProtocol != "tcp_https" || !showCustom || customDomain.isNotBlank()),
                        ) { Text(context.getString(R.string.blockcheck_start)) }
                        if (state.isRunning || state.isFinished || state.isError) {
                            OutlinedButton(onClick = {
                                BlockcheckStore.reset()
                                stoppedManually = false
                            }) { Text(context.getString(R.string.blockcheck_reset)) }
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
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

        if (state.isFinished && stoppedManually) {
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

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.blockcheck_strategies_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (allStrategies.isEmpty()) {
                        Text(context.getString(R.string.blockcheck_no_strategies), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    } else {
                        // During/after a run show the tester's actual order
                        // (history-ordered, mode-capped); idle shows all.
                        val displayStrategies = if ((state.isRunning || state.isFinished) && state.allStrategies.isNotEmpty()) state.allStrategies else allStrategies
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
                                                s,
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
                                    s, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.isFinished && !stoppedManually) {
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
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
                                                Text(r.strategy, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_unstable_count_fmt, state.unstableStrategies.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
                                state.unstableStrategies.forEach { s ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                        Text(s, modifier = Modifier.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (state.failedStrategies.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_failed_count_fmt, state.failedStrategies.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                state.failedStrategies.forEach { s ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                        Text(s, modifier = Modifier.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (workingResults.isEmpty() && state.unstableStrategies.isEmpty() && state.failedStrategies.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
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
                    runner.answerConfirm(true)
                    BlockcheckStore.update { it.copy(pendingConfirm = null) }
                }) { Text(context.getString(R.string.blockcheck_confirm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    runner.answerConfirm(false)
                    BlockcheckStore.update { it.copy(pendingConfirm = null) }
                }) { Text(context.getString(R.string.blockcheck_stop)) }
            },
        )
    }
}
