package com.android.zdtd.service.diagnostics.blockcheck

import android.content.Context
import android.util.Log
import com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class BlockcheckRunner(
    private val context: Context,
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
        val cmd = buildShellCommand(binary.absolutePath, args)

        Log.d(TAG, "starting: ${binary.absolutePath} ${args.joinToString(" ")}")

        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()

            process.outputStream.write(cmd.toByteArray())
            process.outputStream.close()

            var session: BlockcheckSession? = null
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            try {
                var line = reader.readLine()
                while (line != null) {
                    if (!isActive) {
                        process.destroy()
                        break
                    }
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val json = runCatching { JSONObject(trimmed) }.getOrNull()
                        if (json != null) {
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
                                    trySend(BlockcheckEvent.BaselineProbe(probe = BlockcheckBaselineProbe(
                                        host = json.optString("host", ""),
                                        httpCode = json.optInt("http_code", 0),
                                        size = json.optString("size", ""),
                                    ), session = session!!))
                                }
                                "auto_strategy_start" -> {
                                    trySend(BlockcheckEvent.StrategyStarted(
                                        json.optString("strategy", ""),
                                        json.optInt("index", 0),
                                        json.optInt("total", 0),
                                        session!!
                                    ))
                                }
                                "auto_strategy_probe" -> {
                                    trySend(BlockcheckEvent.StrategyProbe(probe = BlockcheckStrategyProbe(
                                        strategy = json.optString("strategy", ""),
                                        host = json.optString("host", ""),
                                        httpCode = json.optInt("http_code", 0),
                                        baselineCode = json.optInt("baseline_code", 0),
                                        works = json.optBoolean("works", false),
                                    ), session = session!!))
                                }
                                "auto_strategy_result" -> {
                                    trySend(BlockcheckEvent.StrategyResult(result = BlockcheckStrategyResult(
                                        strategy = json.optString("strategy", ""),
                                        verdict = json.optString("verdict", "unknown"),
                                        allMatch = json.optBoolean("all_match", false),
                                        anyMatch = json.optBoolean("any_match", false),
                                    ), session = session!!))
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
                                    trySend(BlockcheckEvent.StrategySkipped(
                                        json.optString("strategy", ""),
                                        json.optString("reason", ""),
                                        session!!
                                    ))
                                }
                                "auto_strategy_error" -> {
                                    trySend(BlockcheckEvent.StrategyError(
                                        json.optString("strategy", ""),
                                        json.optString("error", ""),
                                        session!!
                                    ))
                                }
                            }
                        } else if (session == null) {
                            trySend(BlockcheckEvent.Error(trimmed))
                            process.destroy()
                            break
                        }
                    }
                    line = reader.readLine()
                }
            } finally {
                reader.close()
                process.destroy()
            }
        }

        awaitClose { }
    }

    companion object {
        private const val TAG = "BlockcheckRunner"
    }

    private fun buildShellCommand(bin: String, args: List<String>): String {
        val quoted = args.joinToString(" ") {
            "'" + it.replace("'", "'\\''") + "'"
        }
        val binQuoted = "'" + bin.replace("'", "'\\''") + "'"
        return buildString {
            append("chmod 700 ")
            append(binQuoted)
            append(" 2>/dev/null || true\n")
            append("exec ")
            append(binQuoted)
            if (quoted.isNotBlank()) {
                append(' ')
                append(quoted)
            }
        }
    }
}
