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
    val probeResults: List<BlockcheckStrategyProbe> = emptyList(),
    val baselineResults: List<BlockcheckBaselineProbe> = emptyList(),
    val isFinished: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
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
    val verdict: String,  // "works", "partial", "failed"
    val allMatch: Boolean,
    val anyMatch: Boolean,
) {
    val isWorking: Boolean get() = verdict == "works"
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
