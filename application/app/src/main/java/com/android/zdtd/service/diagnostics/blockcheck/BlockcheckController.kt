package com.android.zdtd.service.diagnostics.blockcheck

import android.content.Context
import com.android.zdtd.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Everything one scan needs, captured at start so the UI can detach. */
data class BlockcheckRequest(
    val program: String,
    val profile: String,
    val protocol: String,
    val mode: String,
    val hostsFile: String?,
    /** History key: hostlist file basename, or the protocol for UDP targets. */
    val targetKey: String,
    val allStrategies: List<String>,
    val strategyTitles: Map<String, String>,
)

/**
 * Owns the running strategy scan for the whole process.
 *
 * The scan must outlive the screen that starts it. Before, the run job was
 * launched from `rememberCoroutineScope()` and the events were folded into the
 * store inside the screen: switching tabs or leaving the app disposed the
 * composable, cancelled the job, and the runner's `awaitClose` force-destroyed
 * the `su` child — the run died silently and could neither be observed nor
 * stopped. Now the job lives here (process scope), folds events into
 * [BlockcheckStore] itself, and the UI is a pure observer that can attach,
 * detach and re-attach at any time.
 *
 * The foreground service ([BlockcheckScanService]) keeps the process alive
 * while a scan runs; this object keeps the work alive across UI changes.
 */
object BlockcheckController {
    /** Process lifetime: survives screen disposal, dies with the app process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var impl: BlockcheckRunner? = null
    private var runJob: Job? = null
    /** Application context of the current run, for service teardown. */
    private var appContext: Context? = null

    /** True while a scan is owned by this controller (survives UI detach). */
    val isActive: Boolean get() = runJob?.isActive == true

    /** Stops a running scan (the tester cleans up its own engine and rules). */
    fun stop() {
        runJob?.cancel()
        runJob = null
        impl = null
        BlockcheckScanService.stop(appContext)
        BlockcheckStore.update {
            it.copy(
                isRunning = false,
                isFinished = true,
                phase = "stopped",
                stoppedManually = true,
            )
        }
    }

    /**
     * Starts a scan. No-op when one is already active, so re-entering the
     * screen cannot restart or clobber a run that is still going.
     */
    fun start(context: Context, request: BlockcheckRequest) {
        if (isActive) return
        val appContext = context.applicationContext
        val runner = BlockcheckRunner(appContext)
        impl = runner
        this.appContext = appContext
        BlockcheckStore.reset()
        BlockcheckStore.update {
            it.copy(
                program = request.program,
                protocol = request.protocol,
                mode = request.mode,
                profile = request.profile,
                allStrategies = request.allStrategies,
                strategyTitles = request.strategyTitles,
                isRunning = true,
            )
        }
        // Anchor the process: without a foreground service the OS may kill the
        // app (and the root `su` child running the tester) once the UI is gone.
        BlockcheckScanService.start(appContext)
        // LAZY so `runJob` is always assigned before the body can reach its
        // finally; with the immediate dispatcher an eagerly started body could
        // finish first and leave the identity guard comparing against null.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runner.run(
                    program = request.program,
                    hostsFile = request.hostsFile,
                    protocol = request.protocol,
                    mode = request.mode,
                    historyPath = BlockcheckHistory(appContext).path(),
                ).collect { event -> apply(appContext, request, event) }
            } finally {
                // Only the current run may clear state or drop the service: a
                // stale job's finally (e.g. one cancelled by stop() right
                // before a new start) must not detach the run that replaced it.
                if (runJob === coroutineContext[Job]) {
                    impl = null
                    runJob = null
                    BlockcheckScanService.stop(appContext)
                }
            }
        }
        runJob = job
        job.start()
    }

    /** Folds one tester event into the shared store. */
    private fun apply(context: Context, request: BlockcheckRequest, event: BlockcheckEvent) {
        val store = BlockcheckStore
        when (event) {
            is BlockcheckEvent.Started -> store.replace(
                event.session.copy(
                    program = request.program,
                    allStrategies = event.session.allStrategies.ifEmpty { request.allStrategies },
                    isRunning = true,
                )
            )
            is BlockcheckEvent.Phase -> store.update { it.copy(phase = event.phase) }
            is BlockcheckEvent.StrategyStarted -> store.update {
                it.copy(currentStrategy = event.strategy, currentStrategyIndex = event.index)
            }
            is BlockcheckEvent.StrategyResult -> store.update {
                val r = event.result
                val w = if (r.isWorking) it.workingStrategies + r.strategy else it.workingStrategies
                val u = if (r.isUnstable || r.noBaselineBlock || r.verdict == "not_counted")
                    it.unstableStrategies + r.strategy else it.unstableStrategies
                val f = if (!r.isWorking && !r.isUnstable && !r.noBaselineBlock && r.verdict != "not_counted")
                    it.failedStrategies + r.strategy else it.failedStrategies
                it.copy(
                    workingStrategies = w,
                    failedStrategies = f,
                    unstableStrategies = u,
                    results = it.results + r,
                )
            }
            is BlockcheckEvent.StrategySkipped -> store.update {
                it.copy(skippedStrategies = it.skippedStrategies + event.strategy)
            }
            is BlockcheckEvent.StrategyError -> store.update {
                it.copy(failedStrategies = it.failedStrategies + event.strategy)
            }
            is BlockcheckEvent.ConfirmNeeded -> store.update { it.copy(pendingConfirm = event.question) }
            is BlockcheckEvent.Fatal -> {
                val resId = when (event.stopKind) {
                    "no_internet" -> R.string.blockcheck_fatal_no_internet
                    "dns_stub" -> R.string.blockcheck_fatal_dns_stub
                    "address_block" -> R.string.blockcheck_fatal_address_block
                    "network_lost" -> R.string.blockcheck_fatal_network_lost
                    else -> null
                }
                val msg = resId?.let { context.getString(it) } ?: event.message
                store.update { it.copy(isError = true, errorMessage = msg, isRunning = false) }
            }
            is BlockcheckEvent.Finished -> {
                val unstableNow = store.state.value.results
                    .filter { it.isUnstable || it.noBaselineBlock || it.verdict == "not_counted" }
                    .map { it.strategy }
                store.update {
                    it.copy(
                        workingStrategies = event.working,
                        failedStrategies = event.failed.filter { s -> s !in unstableNow },
                        unstableStrategies = unstableNow,
                        isFinished = true,
                        isRunning = false,
                        forced = event.forced,
                        stopKind = event.stopKind,
                        stoppedManually = event.stopKind == "declined",
                        pendingConfirm = null,
                    )
                }
                // Informational (forced) runs never touch history.
                if (!event.forced && event.stopKind == null) {
                    BlockcheckHistory(context).record(
                        program = request.program,
                        protocol = request.protocol,
                        targetKey = request.targetKey,
                        working = event.working,
                        failed = event.failed,
                    )
                }
            }
            is BlockcheckEvent.Error -> store.update {
                it.copy(isError = true, errorMessage = event.message, isRunning = false)
            }
            else -> {}
        }
    }
}