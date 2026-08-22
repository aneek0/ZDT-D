package com.android.zdtd.service.diagnostics.blockcheck

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BlockcheckSession(
    val program: String,
    val totalStrategies: Int,
    val totalHosts: Int,
    val hosts: List<String>,
    val currentStrategy: String = "",
    val currentStrategyIndex: Int = -1,
    val phase: String = "",
    val workingStrategies: List<String> = emptyList(),
    val failedStrategies: List<String> = emptyList(),
    val skippedStrategies: List<String> = emptyList(),
    val allStrategies: List<String> = emptyList(),
    // Per-strategy gradient results (in arrival order).
    val results: List<BlockcheckStrategyResult> = emptyList(),
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val stoppedManually: Boolean = false,
)

data class BlockcheckBaselineProbe(
    val host: String,
    val httpCode: Int,
    val size: String,
)

data class BlockcheckStrategyProbe(
    val strategy: String,
    val host: String,
    val httpCode: Int,
    val baselineCode: Int,
    val works: Boolean,
)

data class BlockcheckStrategyResult(
    val strategy: String,
    val verdict: String,
    val allMatch: Boolean,
    val anyMatch: Boolean,
    val hostsTotal: Int = 0,
    val baselineBlocked: Int = 0,
    val hostsOpened: Int = 0,
    val hostsStillBlocked: Int = 0,
    // Share (0..100) of baseline-blocked hosts this strategy opened. null when
    // nothing was blocked at baseline (no measurable bypass).
    val openedPct: Double? = null,
    val score: Double? = null,
) {
    val isWorking: Boolean get() = verdict == "works" || verdict == "partial"
    val isPartial: Boolean get() = verdict == "partial"
    // "no_baseline_block" means every host already worked without a strategy,
    // so the strategy cannot be judged against a blocked baseline.
    val noBaselineBlock: Boolean get() = verdict == "no_baseline_block"
}

sealed class BlockcheckEvent {
    data class Started(val session: BlockcheckSession) : BlockcheckEvent()
    data class Phase(val phase: String, val session: BlockcheckSession) : BlockcheckEvent()
    data class BaselineProbe(val probe: BlockcheckBaselineProbe, val session: BlockcheckSession) : BlockcheckEvent()
    data class StrategyStarted(val strategy: String, val index: Int, val total: Int, val session: BlockcheckSession) : BlockcheckEvent()
    data class StrategyProbe(val probe: BlockcheckStrategyProbe, val session: BlockcheckSession) : BlockcheckEvent()
    data class StrategyResult(val result: BlockcheckStrategyResult, val session: BlockcheckSession) : BlockcheckEvent()
    data class StrategySkipped(val strategy: String, val reason: String, val session: BlockcheckSession) : BlockcheckEvent()
    data class StrategyError(val strategy: String, val error: String, val session: BlockcheckSession) : BlockcheckEvent()
    data class Finished(val working: List<String>, val failed: List<String>, val session: BlockcheckSession) : BlockcheckEvent()
    data class Error(val message: String) : BlockcheckEvent()
}

object BlockcheckStore {
    private val mutableState = MutableStateFlow(BlockcheckSession(program = "", totalStrategies = 0, totalHosts = 0, hosts = emptyList()))
    val state: StateFlow<BlockcheckSession> = mutableState

    fun update(transform: (BlockcheckSession) -> BlockcheckSession) {
        mutableState.update(transform)
    }

    fun replace(next: BlockcheckSession) {
        mutableState.value = next
    }

    fun reset() {
        mutableState.value = BlockcheckSession(program = "", totalStrategies = 0, totalHosts = 0, hosts = emptyList())
    }
}
