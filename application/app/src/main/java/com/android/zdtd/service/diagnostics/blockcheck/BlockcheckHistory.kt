package com.android.zdtd.service.diagnostics.blockcheck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Strategy scan history, keyed "<program>|<protocol>|<targetKey>".
 * The app writes this file; the tester (running as root) only reads it via
 * the absolute path, which root can reach inside the app-private dir.
 *
 * Format per key: {"confirmed": [strategy...], "failed": {strategy: epochSecs}, "ts": epochSecs}
 */
class BlockcheckHistory(private val context: Context) {

    private val file: File get() = File(context.filesDir, "strategy_history.json")

    fun path(): String = file.absolutePath

    /** Do not call for forced (informational) runs. */
    @Synchronized
    fun record(
        program: String,
        protocol: String,
        targetKey: String,
        working: List<String>,
        failed: List<String>,
    ) {
        if (targetKey.isBlank()) return
        val root = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        val key = "$program|$protocol|$targetKey"
        val now = System.currentTimeMillis() / 1000
        val entry = runCatching { root.getJSONObject(key) }.getOrElse { JSONObject() }

        val oldConfirmed = buildList {
            entry.optJSONArray("confirmed")?.let { arr ->
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        }
        val failedNow = failed.toSet()
        // Working strategies first, then old confirmed that did not just fail;
        // dedup preserving order.
        val mergedConfirmed = buildList {
            working.forEach { if (it.isNotBlank() && !contains(it)) add(it) }
            oldConfirmed.forEach { if (it.isNotBlank() && it !in failedNow && !contains(it)) add(it) }
        }

        val failedMap = entry.optJSONObject("failed") ?: JSONObject()
        working.forEach { failedMap.remove(it) }
        failed.forEach { if (it.isNotBlank()) failedMap.put(it, now) }

        entry.put("confirmed", JSONArray(mergedConfirmed))
        entry.put("failed", failedMap)
        entry.put("ts", now)
        root.put(key, entry)

        // LRU-evict: at most MAX_TARGETS keys, oldest ts first.
        while (root.length() > MAX_TARGETS) {
            val oldest = root.keys().asSequence().minByOrNull { k ->
                runCatching { root.getJSONObject(k).optLong("ts", 0L) }.getOrDefault(0L)
            } ?: break
            root.remove(oldest)
        }
        file.writeText(root.toString())
    }

    companion object {
        private const val MAX_TARGETS = 50
    }
}
