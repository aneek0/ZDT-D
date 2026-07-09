package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.diagnostics.blockcheck.*
import kotlinx.coroutines.launch

@Composable
fun BlockcheckScreen(
    program: String,
    hostsFile: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by BlockcheckStore.state.collectAsStateWithLifecycle()
    var selectedStrategy by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }

    // Start the blockcheck on first composition
    LaunchedEffect(program, hostsFile) {
        BlockcheckStore.reset()
        val runner = BlockcheckRunner(context)
        runner.run(program, hostsFile, qnum = 200, timeoutSecs = 6).collect { event ->
            when (event) {
                is BlockcheckEvent.Started -> BlockcheckStore.replace(event.session)
                is BlockcheckEvent.Phase -> {
                    BlockcheckStore.update { it.copy(phase = event.phase) }
                }
                is BlockcheckEvent.BaselineProbe -> {
                    BlockcheckStore.update { it.copy(baselineResults = it.baselineResults + event.probe) }
                }
                is BlockcheckEvent.StrategyStarted -> {
                    BlockcheckStore.update {
                        it.copy(
                            currentStrategy = event.strategy,
                            currentStrategyIndex = event.index,
                            probeResults = emptyList(),
                        )
                    }
                }
                is BlockcheckEvent.StrategyProbe -> {
                    BlockcheckStore.update { it.copy(probeResults = it.probeResults + event.probe) }
                }
                is BlockcheckEvent.StrategyResult -> {
                    BlockcheckStore.update {
                        val working = if (event.result.isWorking) it.workingStrategies + event.result.strategy else it.workingStrategies
                        val failed = if (!event.result.isWorking) it.failedStrategies + event.result.strategy else it.failedStrategies
                        it.copy(workingStrategies = working, failedStrategies = failed)
                    }
                }
                is BlockcheckEvent.Finished -> {
                    BlockcheckStore.update {
                        it.copy(
                            workingStrategies = event.working,
                            failedStrategies = event.failed,
                            isFinished = true,
                        )
                    }
                }
                is BlockcheckEvent.StrategyError -> {
                    BlockcheckStore.update { it.copy(failedStrategies = it.failedStrategies + event.strategy) }
                }
                is BlockcheckEvent.StrategySkipped -> {
                    BlockcheckStore.update { it.copy(failedStrategies = it.failedStrategies + event.strategy) }
                }
                is BlockcheckEvent.Error -> {
                    BlockcheckStore.update { it.copy(isError = true, errorMessage = event.message) }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (rememberIsCompactWidth()) 12.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto Blockcheck",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Program: $program • ${state.totalStrategies} strategies • ${state.totalHosts} hosts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
            OutlinedButton(onClick = onClose) {
                Text("Close")
            }
        }

        // Phase card
        AnimatedVisibility(visible = !state.isFinished && !state.isError) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = when {
                            state.phase == "strategy" -> "Testing strategies..."
                            state.phase == "baseline" -> "Collecting baseline..."
                            else -> "Preparing..."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.currentStrategy.isNotBlank() && state.phase == "strategy") {
                        Text(text = "Testing: ${state.currentStrategy}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Error
        AnimatedVisibility(visible = state.isError) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(color = MaterialTheme.colorScheme.onErrorContainer, text = "Error")
                    state.errorMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        // Results
        if (state.isFinished && state.workingStrategies.isNotEmpty()) {
            Text(
                text = "Working Strategies (${state.workingStrategies.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF22C55E),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.workingStrategies.size) { idx ->
                    val strategy = state.workingStrategies[idx]
                    StrategyResultCard(
                        strategy = strategy,
                        verdict = "works",
                        selected = selectedStrategy == strategy,
                        onClick = { selectedStrategy = strategy },
                        applying = applying,
                        onApply = {
                            applying = true
                            coroutineScope.launch {
                                applyStrategicVariant(program, strategy)
                                applying = false
                            }
                        },
                    )
                }
            }
        }

        if (state.isFinished && state.failedStrategies.isNotEmpty()) {
            Text(
                text = "Failed Strategies (${state.failedStrategies.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.failedStrategies.size) { idx ->
                    val strategy = state.failedStrategies[idx]
                    StrategyResultCard(
                        strategy = strategy,
                        verdict = "failed",
                        selected = false,
                        onClick = {},
                        applying = false,
                        onApply = {},
                    )
                }
            }
        }

        if (state.isFinished && state.workingStrategies.isEmpty() && state.failedStrategies.isEmpty()) {
            Text("No strategies tested.")
        }
    }
}

@Composable
private fun StrategyResultCard(
    strategy: String,
    verdict: String,
    selected: Boolean,
    onClick: () -> Unit,
    applying: Boolean,
    onApply: () -> Unit,
) {
    val bgColor = when (verdict) {
        "works" -> Color(0xFF22C55E).copy(alpha = 0.08f)
        "partial" -> Color(0xFFEAB308).copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        verdict == "works" && selected -> Color(0xFF22C55E)
        verdict == "works" -> Color(0xFF22C55E).copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strategy,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (verdict) {
                        "works" -> Color(0xFF22C55E)
                        "partial" -> Color(0xFFEAB308)
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
            if (verdict == "works" && selected) {
                if (applying) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

private suspend fun applyStrategicVariant(program: String, strategy: String) {
    // This would call api.applyStrategicVariant(program, profile, strategy)
    // For now, log
    android.util.Log.d("BlockcheckScreen", "Apply strategy $strategy to $program")
}
