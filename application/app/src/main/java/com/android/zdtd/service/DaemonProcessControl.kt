package com.android.zdtd.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import java.io.File

/**
 * Root-level process control for the ZDT-D daemon.
 *
 * This is intentionally process-only: it never touches `start.json` and never
 * runs `service.sh`. It starts the daemon binary the same way `service.sh` does
 * (`setsid`, detached stdin, appended module log), but with
 * `ZDTD_NO_AUTOSTART=1` so an app-owned start can never auto-enable bypass.
 *
 * Every kill is matched against the exact executable path (`/proc/<pid>/exe`),
 * covering the whole module tree plus the app-installed tester and detector
 * binaries. Process groups are re-validated against a fresh process table
 * immediately before each signal, and root/validation failures throw instead of
 * being reported as "nothing found".
 */
class DaemonProcessControl(
  private val context: Context,
  private val root: RootConfigManager,
) {
  private val moduleDir = "/data/adb/modules/ZDT-D"
  private val moduleUpdateDir = "/data/adb/modules_update/ZDT-D"
  private val lowerModuleDir = "/data/adb/modules/zdtd"
  private val daemonBinary = "$moduleDir/bin/zdtd"
  private val daemonLog = "$moduleDir/log/deamon.log"
  private val myprogramProfileRoot = "$moduleDir/working_folder/myprogram/profile"
  private val appBinDir = File(context.noBackupFilesDir, "bin").absolutePath

  /** Allowed skew between `runtime.json` `started_at` and `ps` ELAPSED. */
  private val groupElapsedToleranceSec = 5L

  /** Marker that a root scan reached its end (partial output must not look empty). */
  private val scanEndMarker = "__ZDTD_SCAN_END__"

  /**
   * Prefix that fails the script (exit 97) on a non-root shell, so a scan can
   * never report "nothing found" because it lacked permission.
   */
  private val rootGuard = "[ \"\$(id -u)\" = 0 ] || exit 97; "

  /**
   * Exact executable paths owned by ZDT-D. The module patterns match the whole
   * module tree (a `case` wildcard spans slashes), so helper binaries under
   * working_folder, profile dirs and log-pipe children are covered too.
   */
  private fun ownedExePatterns(): List<String> = listOf(
    "$moduleDir/*",
    "$moduleUpdateDir/*",
    "$lowerModuleDir/*",
    "$appBinDir/nfqws_tester",
    "$appBinDir/dpi-detector",
    "/data/data/${context.packageName}/no_backup/bin/nfqws_tester",
    "/data/data/${context.packageName}/no_backup/bin/dpi-detector",
    "/data/user/0/${context.packageName}/no_backup/bin/nfqws_tester",
    "/data/user/0/${context.packageName}/no_backup/bin/dpi-detector",
  ).distinct()

  /**
   * `/data/adb` is unreadable for the app sandbox, so existence must be asked
   * through the root shell (`test -x`), not `File.exists()`.
   *
   * `test` exit code 1 means "not installed"; anything else non-zero is a root
   * execution failure and is thrown, never reported as a missing binary.
   */
  private fun daemonBinaryExists(): Boolean {
    val r = runCatching { root.execRootSh(rootGuard + "test -x ${shQuote(daemonBinary)}") }.getOrElse {
      throw IllegalStateException("root probe failed for $daemonBinary", it)
    }
    return when (r.code) {
      0 -> true
      1 -> false
      else -> throw IllegalStateException("root probe failed for $daemonBinary (code=${r.code})")
    }
  }

  suspend fun isDaemonAlive(): Boolean = withContext(Dispatchers.IO) {
    processIds(onlyDaemon = true).isNotEmpty()
  }

  /**
   * Starts the daemon exactly once. A live daemon is killed first (only the
   * daemon, by exact PID/path) so a live-but-unreachable instance — stale
   * listener, rotated token — is replaced instead of duplicated.
   *
   * [beforeStart] runs immediately before spawning; returning false aborts the
   * launch without starting anything (used to re-check the caller's preconditions
   * after the slow kill phase). Root/scan/kill failures throw.
   */
  suspend fun restart(beforeStart: () -> Boolean = { true }): Boolean =
    withContext(Dispatchers.IO) {
      if (!daemonBinaryExists()) return@withContext false

      if (isDaemonAlive()) {
        signalOwnedPids("TERM", onlyDaemon = true)
        delay(700)
        // Only escalate when the old daemon actually ignored TERM.
        if (isDaemonAlive()) {
          signalOwnedPids("KILL", onlyDaemon = true)
          delay(400)
        }
        check(processIds(onlyDaemon = true).isEmpty()) { "old daemon survived SIGKILL" }
      }

      if (!beforeStart()) return@withContext false

      val start = root.execRootSh(
        buildString {
          append(rootGuard)
          append("mkdir -p ")
          append(shQuote("$moduleDir/log"))
          append("; cd ")
          append(shQuote(moduleDir))
          append(" 2>/dev/null; export PATH=")
          append(shQuote("$moduleDir/bin:/system/bin:/system/xbin"))
          append(":\$PATH; export ZDTD_NO_AUTOSTART=1; setsid ")
          append(shQuote(daemonBinary))
          append(" >>")
          append(shQuote(daemonLog))
          append(" 2>&1 </dev/null & echo started")
        }
      )
      if (!start.isSuccess) {
        throw IllegalStateException("daemon launch command failed (code=${start.code}): ${start.err.joinToString(" ")}")
      }

      // setsid + exec needs a moment before the pid is visible.
      repeat(10) {
        if (isDaemonAlive()) return@withContext true
        delay(200)
      }
      false
    }

  /**
   * Kills every ZDT-D owned process: the daemon and module-tree helpers (module,
   * module_update and the lowercase module dir), app-installed `nfqws_tester` /
   * `dpi-detector`, and myprogram profile process groups. TERM first, KILL for
   * survivors, then one extra KILL sweep. Throws when anything survives or a
   * root call fails.
   */
  suspend fun killAll(): Boolean = withContext(Dispatchers.IO) {
    // Identity-validated groups only; validated again before every signal.
    val groups = readMyprogramGroups()

    signalOwnedPids("TERM")
    signalGroups(groups, "TERM")
    delay(700)

    signalOwnedPids("KILL")
    signalGroups(groups, "KILL")
    delay(300)
    if (survivors(groups).isEmpty()) return@withContext true

    // Second sweep: a process that was mid-fork during the first KILL.
    signalOwnedPids("KILL")
    signalGroups(groups, "KILL")
    delay(400)
    val left = survivors(groups)
    check(left.isEmpty()) { "ZDT-D processes survived SIGKILL: ${left.joinToString(",")}" }
    true
  }

  /**
   * Largest set of PIDs safe to signal: exact `/proc/<pid>/exe` matches for
   * daemon-only scans, otherwise every ZDT-D owned path. Throws on a failed or
   * truncated root scan — an empty list always means "nothing found".
   */
  private fun processIds(onlyDaemon: Boolean): List<String> {
    val r = runCatching { root.execRootSh(pidsScript(onlyDaemon = onlyDaemon)) }.getOrElse {
      throw IllegalStateException("root process scan failed", it)
    }
    if (!r.isSuccess) {
      throw IllegalStateException("root process scan failed (code=${r.code}): ${r.err.joinToString(" ")}")
    }
    val lines = r.out.map { it.trim() }
    check(lines.any { it == scanEndMarker }) { "root process scan did not complete" }
    return lines.filter { it.isNotEmpty() && it.all(Char::isDigit) }
  }

  /**
   * Sends [signal] to every owned PID. A kill that reports failure is tolerated
   * only when a fresh successful scan confirms that PID is gone; a survivor or
   * a failed scan throws.
   */
  private fun signalOwnedPids(signal: String, onlyDaemon: Boolean = false) {
    val targets = processIds(onlyDaemon)
    if (targets.isEmpty()) return

    val failed = ArrayList<String>()
    for (pid in targets) {
      val r = runCatching { root.execRootSh(rootGuard + "kill -$signal $pid") }.getOrNull()
      if (r?.isSuccess != true) failed += pid
    }
    if (failed.isEmpty()) return

    val aliveNow = processIds(onlyDaemon)
    val survived = failed.filter { it in aliveNow }
    check(survived.isEmpty()) {
      "failed to signal pid(s) ${survived.joinToString(",")} with SIG$signal"
    }
  }

  /**
   * Sends [signal] to already validated process groups. A fresh process table is
   * taken before every group: no member means "confirmed gone" (nothing sent);
   * members present require the original leader to still match its recorded
   * identity, otherwise this throws and nothing is signalled. A kill that
   * reports failure is tolerated only when a fresh table shows no member left.
   */
  private fun signalGroups(groups: List<RuntimeGroup>, signal: String) {
    for (group in groups) {
      val table = readProcessTable()
      if (!groupHasMembers(table, group.pgid)) continue
      requireLeaderIdentity(table, group)

      val r = runCatching {
        root.execRootSh(rootGuard + "kill -$signal -- -${group.pgid}")
      }.getOrNull()
      if (r?.isSuccess != true) {
        val after = readProcessTable()
        check(!groupHasMembers(after, group.pgid)) {
          "failed to signal group ${group.pgid} with SIG$signal"
        }
      }
    }
  }

  /**
   * Remaining owned processes as `pid` / `group:<pgid>` labels (empty == clean).
   *
   * A group with members whose validated leader has exited or no longer matches
   * its identity is indeterminate, not gone: this throws instead of reporting
   * success.
   */
  private fun survivors(groups: List<RuntimeGroup>): List<String> {
    val pids = processIds(onlyDaemon = false).map { "pid:$it" }
    val table = readProcessTable()
    val nowSec = System.currentTimeMillis() / 1000L
    val labels = ArrayList<String>()
    for (group in groups) {
      if (!groupHasMembers(table, group.pgid)) continue
      check(leaderIdentityMatches(table, group, nowSec)) {
        "group ${group.pgid} still has members but its validated leader is gone or changed"
      }
      labels += "group:${group.pgid}"
    }
    return pids + labels
  }

  private fun groupHasMembers(table: Map<Int, ProcessRow>, pgid: Int): Boolean =
    table.values.any { it.pgid == pgid }

  private fun leaderIdentityMatches(
    table: Map<Int, ProcessRow>,
    group: RuntimeGroup,
    nowSec: Long,
  ): Boolean {
    val row = table[group.pid] ?: return false
    if (row.pgid != group.pgid) return false
    return abs(nowSec - group.startedAtSec - row.elapsedSec) <= groupElapsedToleranceSec
  }

  private fun requireLeaderIdentity(table: Map<Int, ProcessRow>, group: RuntimeGroup) {
    val nowSec = System.currentTimeMillis() / 1000L
    check(leaderIdentityMatches(table, group, nowSec)) {
      "refusing to signal group ${group.pgid}: validated leader identity is gone or changed"
    }
  }

  /** A runtime.json record that passed identity validation at kill time. */
  private data class RuntimeGroup(val pid: Int, val pgid: Int, val startedAtSec: Long)

  private data class ProcessRow(val pgid: Int, val elapsedSec: Long)

  /**
   * Myprogram profile runtime records with `running = true` and at least one live
   * member in their PGID. This is fail-closed for live groups: a malformed
   * identity, an absent leader, a different PGID, a clock/elapsed mismatch, or a
   * root read failure throws instead of being skipped. A record whose PGID has no
   * live members at all is stale/dead and is skipped; `running = false` records
   * are ignored.
   */
  private fun readMyprogramGroups(): List<RuntimeGroup> {
    val r = runCatching { root.execRootSh(runtimeScanScript()) }.getOrElse {
      throw IllegalStateException("root runtime.json scan failed", it)
    }
    if (!r.isSuccess) {
      throw IllegalStateException("root runtime.json scan failed (code=${r.code}): ${r.err.joinToString(" ")}")
    }
    val lines = r.out.map { it.trim() }
    check(lines.any { it == scanEndMarker }) { "root runtime.json scan did not complete" }
    val paths = lines.filter { it.endsWith("/runtime.json") }
    if (paths.isEmpty()) return emptyList()

    val table = readProcessTable()
    val nowSec = System.currentTimeMillis() / 1000L
    val groups = ArrayList<RuntimeGroup>()
    for (path in paths) {
      val raw = readRootFile(path)
      val obj = runCatching { JSONObject(raw) }.getOrElse {
        throw IllegalStateException("malformed runtime.json: $path", it)
      }
      if (!obj.optBoolean("running", false)) continue

      val pid = obj.optInt("pid", 0)
      val pgid = obj.optInt("pgid", 0)
      val startedAt = obj.optLong("started_at", 0L)
      check(pid > 1 && pgid > 1 && pid == pgid && startedAt > 0L) {
        "runtime.json without valid identity: $path"
      }
      // No process of this PGID is alive: the record is stale/dead, nothing to signal.
      if (!groupHasMembers(table, pgid)) continue
      val row = table[pid]
        ?: throw IllegalStateException("group leader $pid from $path is not live")
      check(row.pgid == pgid) {
        "leader $pid from $path runs in PGID ${row.pgid}, expected $pgid"
      }
      val expectedElapsed = nowSec - startedAt
      check(abs(expectedElapsed - row.elapsedSec) <= groupElapsedToleranceSec) {
        "elapsed mismatch for group $pgid ($path): expected ${expectedElapsed}s, ps says ${row.elapsedSec}s"
      }
      groups += RuntimeGroup(pid = pid, pgid = pgid, startedAtSec = startedAt)
    }
    return groups.distinctBy { it.pgid }
  }

  /** Strict root file read: a failed read throws instead of looking empty. */
  private fun readRootFile(path: String): String {
    val r = runCatching { root.execRootSh(rootGuard + "cat ${shQuote(path)}") }.getOrElse {
      throw IllegalStateException("root read failed: $path", it)
    }
    if (!r.isSuccess) {
      throw IllegalStateException("root read failed: $path (code=${r.code}): ${r.err.joinToString(" ")}")
    }
    return r.out.joinToString("\n")
  }

  /**
   * Only myprogram profile runtime.json files, plus marker. Standard daemon
   * programs keep `running = true` without myprogram's pgid/started_at identity
   * and are already covered by the exact module-tree executable paths, so they
   * must not enter the group validation path.
   */
  private fun runtimeScanScript(): String = buildString {
    append(rootGuard)
    append("for f in ")
    append("$myprogramProfileRoot/*/runtime.json; ")
    append("do [ -f \"\$f\" ] && echo \"\$f\"; done; echo \"$scanEndMarker\"")
  }

  /**
   * `ps -A -o PID,PGID,ELAPSED` as pid -> (pgid, elapsed). Throws when the root
   * query fails — an empty map always means "no processes", never "no answer".
   */
  private fun readProcessTable(): Map<Int, ProcessRow> {
    val r = runCatching {
      root.execRootSh(rootGuard + "ps -A -o PID,PGID,ELAPSED && echo \"$scanEndMarker\"")
    }.getOrElse { throw IllegalStateException("root ps query failed", it) }
    if (!r.isSuccess) {
      throw IllegalStateException("root ps query failed (code=${r.code}): ${r.err.joinToString(" ")}")
    }
    val lines = r.out.map { it.trim() }
    check(lines.any { it == scanEndMarker }) { "root ps query did not complete" }

    val rows = LinkedHashMap<Int, ProcessRow>()
    for (line in lines) {
      val cols = line.split(Regex("\\s+"))
      if (cols.size < 3) continue
      // Skips the header ("PID PGID ELAPSED") and any garbage line.
      if (!cols[0].all(Char::isDigit) || !cols[1].all(Char::isDigit)) continue
      val pid = cols[0].toIntOrNull() ?: continue
      val pgid = cols[1].toIntOrNull() ?: continue
      val elapsedSec = parseElapsedSeconds(cols[2]) ?: continue
      rows[pid] = ProcessRow(pgid = pgid, elapsedSec = elapsedSec)
    }
    return rows
  }

  /** Parses `ps` ELAPSED: `D-HH:MM:SS`, `HH:MM:SS`, `MM:SS` (also bare `SS`). */
  private fun parseElapsedSeconds(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val dash = text.indexOf('-')
    val days = if (dash > 0) text.substring(0, dash).toLongOrNull() ?: return null else 0L
    val clock = if (dash > 0) text.substring(dash + 1) else text
    val parts = clock.split(':').map { it.toLongOrNull() ?: return null }
    val secs = when (parts.size) {
      1 -> parts[0]
      2 -> parts[0] * 60L + parts[1]
      3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
      else -> return null
    }
    return days * 86_400L + secs
  }

  /**
   * Scan-only: prints the PID of every process whose `/proc/<pid>/exe` exactly
   * matches a ZDT-D owned path. One `ls` reads all symlinks; the shell parses
   * them without spawning a separate `readlink` for every process.
   * With [onlyDaemon] the match is narrowed to daemon binaries under module roots.
   */
  private fun pidsScript(onlyDaemon: Boolean): String {
    val daemonPaths = listOf(
      "$moduleDir/bin/zdtd",
      "$moduleUpdateDir/bin/zdtd",
      "$lowerModuleDir/bin/zdtd",
    )
    val casePatterns = if (onlyDaemon) daemonPaths else ownedExePatterns()
    return buildString {
      append(rootGuard)
      append("ls -l /proc/[0-9]*/exe 2>/dev/null | while IFS= read -r line; do ")
      append("exe=\${line#* -> }; ")
      append("[ \"\$exe\" != \"\$line\" ] || continue; ")
      append("source=\${line%% -> *}; ")
      append("pid=\${source%/exe}; ")
      append("pid=\${pid##*/}; ")
      append("exe=\${exe% (deleted)}; ")
      append("case \"\$exe\" in ")
      append(casePatterns.joinToString("|"))
      append(") echo \"\$pid\";; esac; ")
      append("done; echo \"$scanEndMarker\"")
    }
  }

  private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}