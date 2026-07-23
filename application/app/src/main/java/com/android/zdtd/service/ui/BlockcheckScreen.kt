package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.diagnostics.blockcheck.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BlockcheckScreen(
    program: String,
    hostsFile: String,
    onClose: () -> Unit,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by BlockcheckStore.state.collectAsStateWithLifecycle()
    val runner = remember { BlockcheckRunner(context) }

    var allStrategies by remember { mutableStateOf<List<String>>(emptyList()) }
    var hostFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedHostFile by remember { mutableStateOf(hostsFile) }
    var customDomain by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        hostFiles = runner.listHostFiles()
        allStrategies = runner.listStrategies(program)
        BlockcheckStore.update { it.copy(allStrategies = allStrategies) }
    }

    val compact = rememberIsCompactWidth()
    val shortHeight = rememberIsShortHeight()

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
                Column(Modifier.padding(if (compact) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Auto Blockcheck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Program: $program", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
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
                    Text("Hosts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showCustom, onClick = { showCustom = false }, label = { Text("From list") })
                        FilterChip(selected = showCustom, onClick = { showCustom = true }, label = { Text("Custom domain") })
                    }
                    if (showCustom) {
                        OutlinedTextField(
                            value = customDomain, onValueChange = { customDomain = it },
                            label = { Text("Domain") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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
                }
            }
        }

        if (!state.isRunning && !state.isFinished && !state.isError) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val hostInput = if (showCustom && customDomain.isNotBlank()) {
                                val tmp = File(context.cacheDir, "blockcheck_custom_host.txt")
                                tmp.writeText(customDomain.trim())
                                tmp.absolutePath
                            } else selectedHostFile
                            BlockcheckStore.reset()
                            BlockcheckStore.update { it.copy(allStrategies = allStrategies, isRunning = true) }
                            runJob = coroutineScope.launch {
                                runner.run(program, hostInput).collect { event ->
                                    when (event) {
                                        is BlockcheckEvent.Started -> BlockcheckStore.replace(event.session.copy(allStrategies = allStrategies, isRunning = true))
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
                        },
                        enabled = !showCustom || customDomain.isNotBlank(),
                    ) { Text("Start") }
                    OutlinedButton(onClick = onClose) { Text("Close") }
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
                                state.phase == "baseline" -> "Collecting baseline..."
                                state.phase == "strategies" -> "Testing strategies..."
                                else -> "Starting..."
                            }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(
                            onClick = { runJob?.cancel(); runJob = null; BlockcheckStore.update { it.copy(isRunning = false, isError = true, errorMessage = "Cancelled") } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Stop") }
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
                    Text("Strategies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    allStrategies.forEach { s ->
                        val status = when {
                            state.isRunning && state.currentStrategy == s -> if (state.phase == "strategies") "testing" else "queued"
                            state.workingStrategies.contains(s) -> "works"
                            state.failedStrategies.contains(s) -> "failed"
                            state.skippedStrategies.contains(s) -> "skipped"
                            else -> if (state.isRunning || state.isFinished) "queued" else ""
                        }
                        val dotColor = when (status) {
                            "works" -> Color(0xFF22C55E)
                            "failed" -> MaterialTheme.colorScheme.error
                            "skipped" -> Color(0xFF9CA3AF)
                            "testing" -> MaterialTheme.colorScheme.primary
                            else -> Color.Transparent
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (dotColor != Color.Transparent) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                            } else {
                                Spacer(Modifier.size(9.dp))
                            }
                            Text(s, style = MaterialTheme.typography.bodyMedium, fontWeight = if (status == "testing") FontWeight.Bold else FontWeight.Normal)
                            if (status == "testing") {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                            if (status.isNotEmpty() && status != "queued" && status != "testing") {
                                Spacer(Modifier.weight(1f))
                                Text(status, style = MaterialTheme.typography.labelSmall, color = dotColor)
                            }
                        }
                    }
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
                    Column(Modifier.padding(16.dp)) {
                        Text("Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        state.errorMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }

        if (state.isFinished) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.workingStrategies.isNotEmpty()) {
                        Text("Working (${state.workingStrategies.size})", fontWeight = FontWeight.SemiBold, color = Color(0xFF22C55E))
                        state.workingStrategies.forEach { s ->
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF22C55E).copy(alpha = 0.08f)) {
                                Text(s, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    if (state.failedStrategies.isNotEmpty()) {
                        Text("Failed (${state.failedStrategies.size})", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        state.failedStrategies.forEach { s ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
                                Text(s, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    if (state.workingStrategies.isEmpty() && state.failedStrategies.isEmpty()) {
                        Text("No strategies tested.")
                    }
                }
            }
        }
    }
}
