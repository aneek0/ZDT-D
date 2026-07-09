package com.android.zdtd.service.diagnostics.blockcheck

import android.content.Context
import android.util.Log
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs the `nfqws-tester auto` command (automatic blockcheck) and emits
 * events the UI can observe.
 *
 * For each strategy, the binary launches nfqws, pings a set of hosts through
 * curl, and compares results against a baseline (no-funnel) run. The emitted
 * NDJSON tells the UI whether a strategy works, partially works, or fails.
 */
class BlockcheckRunner(
    private val context: Context,
    private val rootConfigManager: RootConfigManager = RootConfigManager(context),
) {

    fun run(
        program: String,
        hostsFile: String,
        qnum: Int = 200,
        timeoutSecs: Int = 10,
    ): Flow<BlockcheckEvent> = channelFlow {
        val binary = NfqwsTesterBinary(context).ensureInstalled()
        val args = listOf(
            "auto",
            "--program", program,
            "--hosts", hostsFile,
            "--qnum", qnum.toString(),
            "--timeout", timeoutSecs.toString()
        )

        Log.d(TAG, "blockcheck: starting ${binary.absolutePath} ${args.joinToString(" ")}")

        val (code, out) = withContext(Dispatchers.IO) {
            val cmd = buildShellCommand(binary.absolutePath, args)
            rootConfigManager.execRootSh(cmd)
        }

        if (code != 0 && out.isBlank()) {
            trySend(BlockcheckEvent.Error("nfqws-tester auto exited with code $code"))
            return@channelFlow
        }

        var session: BlockcheckSession? = null

        withContext(Dispatchers.IO) {
            for (line in out.lines()) {
                if (!isActive) break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue

                when (json.optString("type", "")) {
                    "auto_started" -> {
                        session = BlockcheckSession(
                            program = json.optString("program", program),
                            totalStrategies = json.optInt("total_strategies", 0),
                            totalHosts = json.optInt("total_hosts", 0),
                            hosts = json.optJSONArray("hosts")?.let { arr ->
                                buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
                            } ?: emptyList()
                        )
                        trySend(BlockcheckEvent.Started(session!!))
                    }
                    "auto_phase" -> {
                        trySend(BlockcheckEvent.Phase(json.optString("phase", ""), session!!))
                    }
                    "auto_baseline_probe" -> {
                        val probe = BaselineProbe(
                            host = json.optString("host", ""),
                            httpCode = json.optInt("http_code", 0),
                            size = json.optString("size", ""),
                        )
                        trySend(BlockcheckEvent.BaselineProbe(probe, session!!))
                    }
                    "auto_strategy_start" -> {
                        val strategy = json.optString("strategy", "")
                        val index = json.optInt("index", 0)
                        val total = json.optInt("total", 0)
                        trySend(BlockcheckEvent.StrategyStarted(strategy, index, total, session!!))
                    }
                    "auto_strategy_probe" -> {
                        val probe = StrategyProbe(
                            strategy = json.optString("strategy", ""),
                            host = json.optString("host", ""),
                            httpCode = json.optInt("http_code", 0),
                            baselineCode = json.optInt("baseline_code", 0),
                            works = json.optBoolean("works", false),
                        )
                        trySend(BlockcheckEvent.StrategyProbe(probe, session!!))
                    }
                    "auto_strategy_result" -> {
                        val result = StrategyResult(
                            strategy = json.optString("strategy", ""),
                            verdict = json.optString("verdict", "unknown"),
                            allMatch = json.optBoolean("all_match", false),
                            anyMatch = json.optBoolean("any_match", false),
                        )
                        trySend(BlockcheckEvent.StrategyResult(result, session!!))
                    }
                    "auto_finished" -> {
                        val working = mutableListOf<String>()
                        val failed = mutableListOf<String>()
                        json.optJSONArray("working")?.let { arr ->
                            for (i in 0 until arr.length()) working.add(arr.getString(i))
                        }
                        json.optJSONArray("failed")?.let { arr ->
                            for (i in 0 until arr.length()) failed.add(arr.getString(i))
                        }
                        trySend(BlockcheckEvent.Finished(working, failed, session!!))
                    }
                    "auto_strategy_skip" -> {
                        val strategy = json.optString("strategy", "")
                        val reason = json.optString("reason", "")
                        trySend(BlockcheckEvent.StrategySkipped(strategy, reason, session!!))
                    }
                    "auto_strategy_error" -> {
                        val strategy = json.optString("strategy", "")
                        val error = json.optString("error", "")
                        trySend(BlockcheckEvent.StrategyError(strategy, error, session!!))
                    }
                }
            }
        }

        awaitClose { }
    }

    private fun buildShellCommand(bin: String, args: List<String>): String {
        val quoted = args.joinToString(" ") {
            "'" + it.replace("'", "'\\''") + "'"
        }
        return buildString {
            append("chmod 700 '")
            append(bin.replace("'", "'\\''"))
            append("' 2>/dev/null || true\nexec ")
            append(quoted)
        }
    }

    companion object {
        private const val TAG = "BlockcheckRunner"
    }
}
