package com.android.zdtd.service.diagnostics.blockcheck

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update


/** One atomic strategy from the shipped scan catalog (nfqws2 only). */
data class CatalogStrategy(val id: String, val name: String)

data class BlockcheckSession(
    val program: String,
    val totalStrategies: Int,
    val totalHosts: Int,
    val hosts: List<String>,
    val protocol: String = "tcp_https",
    // Catalog id -> human title (nfqws2 catalog runs only).
    val strategyTitles: Map<String, String> = emptyMap(),
    val mode: String = "full",
    // The profile the run will apply its winner to; survives screen detach.
    val profile: String = "default",
    val currentStrategy: String = "",
    val currentStrategyIndex: Int = -1,
    val phase: String = "",
    val workingStrategies: List<String> = emptyList(),
    val failedStrategies: List<String> = emptyList(),
    val unstableStrategies: List<String> = emptyList(),
    val skippedStrategies: List<String> = emptyList(),
    val allStrategies: List<String> = emptyList(),
    // Per-strategy results (in arrival order).
    val results: List<BlockcheckStrategyResult> = emptyList(),
    // Informational run: baseline/pass control said targets open without a
    // bypass, so results are not saved to history.
    val forced: Boolean = false,
    // Question key while the tester waits for a confirm answer on stdin.
    val pendingConfirm: String? = null,
    // Stop reason from the tester: declined / interrupted / null on success.
    val stopKind: String? = null,
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val stoppedManually: Boolean = false,
)

data class BlockcheckBaselineProbe(
    val host: String,
    val verdict: String,
    val reason: String,
    val httpCode: Int = 0,
    val size: String = "",
)

data class BlockcheckStrategyProbe(
    val strategy: String,
    val host: String,
    val attempt: Int,
    val attemptsOk: Int,
    val attemptsTotal: Int,
    val verdict: String,
    val reason: String,
    val works: Boolean,
    val timeMs: Long = 0L,
    val httpCode: Int = 0,
)

data class BlockcheckStrategyResult(
    val strategy: String,
    // works | unstable | failed | not_counted | no_baseline_block
    val verdict: String,
    val hostsTotal: Int = 0,
    val baselineBlocked: Int = 0,
    val hostsOpened: Int = 0,
    val hostsStillBlocked: Int = 0,
    // Share (0..100) of baseline-blocked hosts this strategy opened at least
    // once. null when nothing was blocked at baseline.
    val openedPct: Double? = null,
    val score: Double? = null,
    // Mean wall time of successful confirm probes; null when nothing opened.
    val timeMs: Double? = null,
    // Worst-case confirm count over probed targets (0..attemptsTotal).
    val attemptsOk: Int = 0,
    val attemptsTotal: Int = 0,
) {
    val isWorking: Boolean get() = verdict == "works"
    val isUnstable: Boolean get() = verdict == "unstable"
    // "no_baseline_block" means every target already worked without a strategy,
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
    // The tester blocks on stdin until answerConfirm() replies.
    data class ConfirmNeeded(val question: String) : BlockcheckEvent()
    // Unrecoverable condition (no internet, dns stub, ...); the tester exits.
    data class Fatal(val stopKind: String, val message: String) : BlockcheckEvent()
    data class Finished(
        val working: List<String>,
        val failed: List<String>,
        val forced: Boolean,
        val stopKind: String?,
        val session: BlockcheckSession,
    ) : BlockcheckEvent()
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
