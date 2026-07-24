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
import java.io.File

@Composable
fun BlockcheckScreen(
    program: String,
    hostsFile: String,
    onClose: () -> Unit,
    actions: ZdtdActions? = null,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by BlockcheckStore.state.collectAsStateWithLifecycle()
    val runner = remember { BlockcheckRunner(context) }

    var selectedProgram by remember { mutableStateOf(program) }
    var allStrategies by remember { mutableStateOf<List<String>>(emptyList()) }
    var hostFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedHostFile by remember { mutableStateOf(hostsFile) }
    var customDomain by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var stoppedManually by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProgram) {
        runJob?.cancel()
        runJob = null
        stoppedManually = false
        BlockcheckStore.reset()
        hostFiles = runner.listHostFiles()
        allStrategies = runner.listStrategies(selectedProgram)
        BlockcheckStore.update { it.copy(allStrategies = allStrategies) }
    }

    val compact = rememberIsCompactWidth()
    val shortHeight = rememberIsShortHeight()

    fun startRun() {
        val hostInput = if (showCustom && customDomain.isNotBlank()) {
            val tmp = File(context.cacheDir, "blockcheck_custom_host.txt")
            tmp.writeText(customDomain.trim())
            tmp.absolutePath
        } else selectedHostFile
        stoppedManually = false
        BlockcheckStore.reset()
        BlockcheckStore.update { it.copy(program = selectedProgram, allStrategies = allStrategies, isRunning = true) }
        runJob = coroutineScope.launch {
            runner.run(selectedProgram, hostInput).collect { event ->
                when (event) {
                    is BlockcheckEvent.Started -> BlockcheckStore.replace(event.session.copy(program = selectedProgram, allStrategies = allStrategies, isRunning = true))
                    is BlockcheckEvent.Phase -> BlockcheckStore.update { it.copy(phase = event.phase) }
                    is BlockcheckEvent.StrategyStarted -> BlockcheckStore.update { it.copy(currentStrategy = event.strategy, currentStrategyIndex = event.index) }
                    is BlockcheckEvent.StrategyResult -> {
                        BlockcheckStore.update {
                            val w = if (event.result.isWorking) it.workingStrategies + event.result.strategy else it.workingStrategies
                            val f = if (!event.result.isWorking) it.failedStrategies + event.result.strategy else it.failedStrategies
                            it.copy(workingStrategies = w, failedStrategies = f)
                        }
                    }
                    is BlockcheckEvent.StrategySkipped -> BlockcheckStore.update { it.copy(skippedStrategies = it.skippedStrategies + event.strategy) }
                    is BlockcheckEvent.StrategyError -> BlockcheckStore.update { it.copy(failedStrategies = it.failedStrategies + event.strategy) }
                    is BlockcheckEvent.Finished -> BlockcheckStore.update { it.copy(workingStrategies = event.working, failedStrategies = event.failed, isFinished = true, isRunning = false) }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { startRun() },
                            enabled = !state.isRunning && (!showCustom || customDomain.isNotBlank()),
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
                                state.phase == "baseline" -> context.getString(R.string.blockcheck_baseline)
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
                        allStrategies.forEach { s ->
                            val status = when {
                                state.isRunning && state.currentStrategy == s -> if (state.phase == "strategies") "testing" else "queued"
                                state.workingStrategies.contains(s) -> "works"
                                state.failedStrategies.contains(s) -> "failed"
                                state.skippedStrategies.contains(s) -> "skipped"
                                else -> if (state.isRunning || state.isFinished) "queued" else ""
                            }
                            val shape = RoundedCornerShape(12.dp)
                            val bgColor = when (status) {
                                "testing" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                "works" -> Color(0xFF22C55E).copy(alpha = 0.08f)
                                "failed" -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                "skipped" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else -> Color.Transparent
                            }
                            if (bgColor != Color.Transparent) {
                                Surface(shape = shape, color = bgColor) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            s, modifier = Modifier.weight(1f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (status == "testing") FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (status == "testing") {
                                            Spacer(Modifier.width(8.dp))
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        }
                                        if (status == "works" && actions != null) {
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(
                                                onClick = { actions.applyStrategicVariant(selectedProgram, "default", s) { } },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) { Text(context.getString(R.string.blockcheck_apply), style = MaterialTheme.typography.labelSmall) }
                                        }
                                        if (status.isNotEmpty() && status != "queued" && status != "testing") {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                when (status) {
                                                    "works" -> context.getString(R.string.blockcheck_works)
                                                    "failed" -> context.getString(R.string.blockcheck_failed)
                                                    "skipped" -> context.getString(R.string.blockcheck_skipped)
                                                    else -> ""
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (status) {
                                                    "works" -> Color(0xFF22C55E)
                                                    "failed" -> MaterialTheme.colorScheme.error
                                                    "skipped" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
                    if (state.workingStrategies.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.blockcheck_working_count_fmt, state.workingStrategies.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF22C55E))
                                state.workingStrategies.forEach { s ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(s, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (actions != null) {
                                                Spacer(Modifier.width(8.dp))
                                                TextButton(onClick = { actions.applyStrategicVariant(selectedProgram, "default", s) { } }) {
                                                    Text(context.getString(R.string.blockcheck_apply), style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
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
                    if (state.workingStrategies.isEmpty() && state.failedStrategies.isEmpty()) {
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
}
