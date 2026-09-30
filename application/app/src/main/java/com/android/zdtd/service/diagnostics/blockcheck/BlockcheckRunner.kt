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
import java.io.File
import java.io.InputStreamReader

class BlockcheckRunner(
    private val context: Context,
) {
    /** su stdin pipe while a run is active; the tester reads confirm answers from it. */
    @Volatile
    private var confirmWriter: java.io.OutputStream? = null

    suspend fun listStrategies(program: String): List<String> = withContext(Dispatchers.IO) {
        val fromBinary = runCatching {
            val binary = NfqwsTesterBinary(context).ensureInstalled()
            val cmd = buildShellCommand(binary.absolutePath, listOf("list", "--program", program))
            val result = runRoot(cmd)
            val json = runCatching { JSONObject(result) }.getOrNull() ?: return@runCatching emptyList<String>()
            val arr = json.optJSONArray("strategies") ?: return@runCatching emptyList<String>()
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) add(v)
                }
            }
        }.getOrDefault(emptyList())
        // Old/missing tester binaries (or empty stubs from fast-build fallback) return nothing
        // for the "list" command. Fall back to reading the installed module's strategy dir,
        // which is what the tester itself scans at runtime.
        if (fromBinary.isNotEmpty()) fromBinary else listStrategiesFromModuleDir(program)
    }

    private suspend fun listStrategiesFromModuleDir(program: String): List<String> = withContext(Dispatchers.IO) {
        val dir = "/data/adb/modules/ZDT-D/strategic/strategicvar/$program"
        val result = runRoot("ls -1 '$dir' 2>&1 || true")
        result.lines().mapNotNull { line ->
            val name = line.trim()
            if (name.endsWith(".txt") && !name.contains('/')) name else null
        }.sorted()
    }

    suspend fun listHostFiles(): List<String> = withContext(Dispatchers.IO) {
        val dir = "/data/adb/modules/ZDT-D/strategic/list"
        val result = runRoot("ls -1 '$dir' 2>/dev/null || true")
        // Exclude ipset-* files: they are IP sets consumed by --ipset=, not
        // domain hostlists the curl probe can test against.
        result.lines().filter { it.isNotBlank() && it.endsWith(".txt") && !it.startsWith("ipset-") }.sorted()
    }

    /**
     * Atomic strategies of a scan catalog for nfqws2: stable id + display
     * title, already filtered by the tester to entries whose blobs resolve.
     */
    suspend fun listCatalog(protocol: String): List<CatalogStrategy> = withContext(Dispatchers.IO) {
        runCatching {
            val binary = NfqwsTesterBinary(context).ensureInstalled()
            val cmd = buildShellCommand(binary.absolutePath, listOf("catalog", "--protocol", protocol))
            val json = runCatching { JSONObject(runRoot(cmd)) }.getOrNull() ?: return@runCatching emptyList()
            val arr = json.optJSONArray("strategies") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isNotBlank()) add(CatalogStrategy(id, obj.optString("name", id)))
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Turn a catalog strategy into a preset file the daemon can apply, and
     * return that file's name (module strategicvar dir). Null on failure.
     */
    suspend fun exportStrategy(program: String, protocol: String, id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val binary = NfqwsTesterBinary(context).ensureInstalled()
            val cmd = buildShellCommand(
                binary.absolutePath,
                listOf("export", "--program", program, "--protocol", protocol, "--id", id),
            )
            val json = runCatching { JSONObject(runRoot(cmd)) }.getOrNull() ?: return@runCatching null
            json.optString("file", "").ifBlank { null }
        }.getOrNull()
    }

    fun run(
        program: String,
        // Only used for tcp_https; omitted for the fixed-target UDP protocols.
        hostsFile: String?,
        protocol: String = "tcp_https",
        mode: String = "full",
        historyPath: String? = null,
        // Dedicated tester queue: the daemon's nfqws/nfqws2 profiles own 200
        // (ports.rs program_base). Sharing 200 made blockcheck steal packets
        // from (and kill) a running daemon session. Keep in sync with
        // DEFAULT_QNUM in rust/nfqws-tester/src/main.rs.
        qnum: Int = 300,
        timeoutSecs: Int = 4,
    ): Flow<BlockcheckEvent> = channelFlow {
        val binary = NfqwsTesterBinary(context).ensureInstalled()
        val args = buildList {
            add("auto")
            add("--program"); add(program)
            if (protocol == "tcp_https" && hostsFile != null) {
                add("--hosts"); add(hostsFile)
            }
            add("--protocol"); add(protocol)
            add("--mode"); add(mode)
            if (historyPath != null) {
                add("--history"); add(historyPath)
            }
            add("--qnum"); add(qnum.toString())
            add("--timeout"); add(timeoutSecs.toString())
        }
        val cmd = buildShellCommand(binary.absolutePath, args)

        Log.d(TAG, "starting: ${binary.absolutePath} ${args.joinToString(" ")}")

        val process = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()

        // Keep the su stdin pipe open: the shell consumes the command lines
        // before `exec`, and the tester later reads confirm answers from fd 0.
        val writer = process.outputStream
        confirmWriter = writer
        writer.write(cmd.toByteArray())
        writer.flush()

        var session: BlockcheckSession? = null
        var sentError = false
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        try {
            withContext(Dispatchers.IO) {
                var line = reader.readLine()
                while (line != null) {
                    if (!isActive) {
                        process.destroyForcibly()
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
                                        } ?: emptyList(),
                                        protocol = json.optString("protocol", protocol),
                                        mode = json.optString("mode", mode),
                                        // Actual run order after history ordering + mode cap.
                                        allStrategies = jsonStringList(json, "strategies"),
                                        strategyTitles = jsonTitleMap(json),
                                    )
                                    trySend(BlockcheckEvent.Started(session!!))
                                }
                                "auto_phase" -> {
                                    trySend(BlockcheckEvent.Phase(json.optString("phase", ""), session!!))
                                }
                                "auto_baseline_probe" -> {
                                    trySend(BlockcheckEvent.BaselineProbe(probe = BlockcheckBaselineProbe(
                                        host = json.optString("host", ""),
                                        verdict = json.optString("verdict", ""),
                                        reason = json.optString("reason", ""),
                                        httpCode = json.optInt("http_code", 0),
                                        size = json.optString("size", ""),
                                    ), session = session!!))
                                }
                                "auto_confirm_needed" -> {
                                    trySend(BlockcheckEvent.ConfirmNeeded(json.optString("question", "")))
                                }
                                "auto_fatal" -> {
                                    trySend(BlockcheckEvent.Fatal(
                                        json.optString("stop_kind", ""),
                                        json.optString("message", ""),
                                    ))
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
                                        attempt = json.optInt("attempt", 1),
                                        attemptsOk = json.optInt("attempts_ok", 0),
                                        attemptsTotal = json.optInt("attempts_total", 0),
                                        verdict = json.optString("verdict", ""),
                                        reason = json.optString("reason", ""),
                                        works = json.optBoolean("works", false),
                                        timeMs = json.optLong("time_ms", 0L),
                                        httpCode = json.optInt("http_code", 0),
                                    ), session = session!!))
                                }
                                "auto_strategy_result" -> {
                                    trySend(BlockcheckEvent.StrategyResult(result = BlockcheckStrategyResult(
                                        strategy = json.optString("strategy", ""),
                                        verdict = json.optString("verdict", "unknown"),
                                        hostsTotal = json.optInt("hosts_total", 0),
                                        baselineBlocked = json.optInt("baseline_blocked", 0),
                                        hostsOpened = json.optInt("hosts_opened", 0),
                                        hostsStillBlocked = json.optInt("hosts_still_blocked", 0),
                                        openedPct = json.optDouble("opened_pct", Double.NaN).let { if (it.isNaN()) null else it },
                                        score = run {
                                            val raw = json.opt("score")
                                            if (raw is Number) raw.toDouble() else null
                                        },
                                        timeMs = run {
                                            val raw = json.opt("time_ms")
                                            if (raw is Number) raw.toDouble() else null
                                        },
                                        attemptsOk = json.optInt("attempts_ok", 0),
                                        attemptsTotal = json.optInt("attempts_total", 0),
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
                                    val stopKind = json.optString("stop_kind", "").ifBlank { null }
                                    trySend(BlockcheckEvent.Finished(
                                        working, failed,
                                        forced = json.optBoolean("forced", false),
                                        stopKind = stopKind,
                                        session = session!!,
                                    ))
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
                            sentError = true
                            process.destroyForcibly()
                            break
                        }
                    }
                    line = reader.readLine()
                }
            }
        } finally {
            runCatching { reader.close() }
            runCatching { writer.close() }
            confirmWriter = null
            process.destroy()
        }

        // Stub/missing binaries (0-byte APK asset) exit without emitting any JSON.
        // Surface that as an error instead of leaving the UI stuck on "starting".
        if (session == null && !sentError) {
            trySend(BlockcheckEvent.Error(
                "nfqws_tester binary failed to start (no output). The APK ships an empty stub; rebuild the APK with the real binary."
            ))
        }

        // A tester blocked on a stdin confirm answer produces no output and
        // would hang a graceful destroy: kill the process tree forcibly; the
        // next run's cleanup_all() recovers leftover chains/session.
        awaitClose { process.destroyForcibly() }
    }

    /** Answer a pending auto_confirm_needed prompt. */
    fun answerConfirm(accept: Boolean) {
        val writer = confirmWriter ?: return
        runCatching {
            writer.write((if (accept) "y\n" else "n\n").toByteArray())
            writer.flush()
        }
    }

    companion object {
        private const val TAG = "BlockcheckRunner"

        suspend fun runRoot(cmd: String): String = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process.outputStream.write(cmd.toByteArray())
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }
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
            // The pipe stays open for confirm answers: without a trailing
            // newline sh would wait for EOF before running the exec line.
            append('\n')
        }
    }

    /** String array field of a tester event; empty when absent or all blank. */
    private fun jsonStringList(json: JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) add(v)
            }
        }
    }

    /**
     * Catalog ids paired with their display titles. `strategies` and `titles`
     * are parallel arrays in auto_started; a blank title falls back to the id.
     */
    private fun jsonTitleMap(json: JSONObject): Map<String, String> {
        val ids = json.optJSONArray("strategies") ?: return emptyMap()
        val titles = json.optJSONArray("titles") ?: return emptyMap()
        return buildMap {
            for (i in 0 until minOf(ids.length(), titles.length())) {
                val id = ids.optString(i, "")
                if (id.isBlank()) continue
                put(id, titles.optString(i, "").ifBlank { id })
            }
        }
    }
}
