use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::env;
use std::fs;
use std::fs::File;
use std::io::{self, Write};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitCode, Stdio};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const STRATEGIC_DIR: &str = "/data/adb/modules/ZDT-D/strategic/strategicvar";
const WORK_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws_tester";
const SESSION_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws_tester/session.json";
const SETTING_DIR: &str = "/data/adb/modules/ZDT-D/setting";
const MULTIPORT_NO_FILE: &str = "multiport_no";
// The ZDT-D daemon owns queue 200 for nfqws/nfqws2 profiles (see
// ports.rs program_base). The tester uses a dedicated queue so a running
// daemon session never loses its packets while blockcheck iterates
// strategies. An explicit --qnum still wins.
const DEFAULT_QNUM: u16 = 300;
const DESYNC_MARK: &str = "0x10000000";
#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionState {
    program: String,
    config_path: String,
    config_name: String,
    pid: u32,
    qnum: u16,
    started_at_unix_ms: u64,
}

#[derive(Debug, Clone)]
struct StartOptions {
    program: String,
    config_path: String,
    qnum: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PortRange {
    start: u16,
    end: u16,
}

#[derive(Debug, Clone, Default)]
struct ProtoPortFilter {
    tcp: Vec<PortRange>,
    udp: Vec<PortRange>,
}

impl ProtoPortFilter {
    fn is_empty(&self) -> bool {
        self.tcp.is_empty() && self.udp.is_empty()
    }
}

fn main() -> ExitCode {
    // Broken pipe on stdout (UI closed the stream) must not kill the tester
    // mid-run leaving iptables rules behind; probes handle write errors.
    unsafe { libc::signal(libc::SIGPIPE, libc::SIG_IGN) };
    match entry() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            let _ = writeln!(io::stderr(), "{err:#}");
            ExitCode::from(2)
        }
    }
}

fn sh_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

fn entry() -> Result<()> {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.is_empty() || args.iter().any(|it| it == "-h" || it == "--help") {
        print_help();
        return Ok(());
    }

    match args[0].as_str() {
        "--version" | "version" => {
            println!("nfqws-tester {VERSION}");
            Ok(())
        }
        "list" => {
            let program = parse_named_value(&args[1..], "--program")?;
            list_strategies(&normalize_program(&program)?)
        }
        "start" => {
            let options = parse_start_options(&args[1..])?;
            start_strategy(options)
        }
        "stop" | "cleanup" => stop_session(),
        "status" => print_status(),
        "auto" => {
            let program = parse_named_value(&args[1..], "--program")?;
            let protocol = parse_named_value(&args[1..], "--protocol")
                .unwrap_or_else(|_| "tcp_https".to_string());
            if !matches!(protocol.as_str(), "tcp_https" | "stun_voice" | "udp_games") {
                bail!("unknown protocol: {protocol}");
            }
            let mode = parse_named_value(&args[1..], "--mode")
                .unwrap_or_else(|_| "full".to_string());
            if !matches!(mode.as_str(), "quick" | "standard" | "full") {
                bail!("unknown mode: {mode}");
            }
            let hosts_file = parse_named_value(&args[1..], "--hosts").ok();
            if protocol == "tcp_https" && hosts_file.is_none() {
                bail!("--hosts is required for protocol tcp_https");
            }
            let history_path = parse_named_value(&args[1..], "--history").ok();
            let qnum = match parse_named_value(&args[1..], "--qnum") {
                Ok(v) => v.parse::<u16>().context("invalid --qnum")?,
                Err(_) => DEFAULT_QNUM,
            };
            let timeout_secs = match parse_named_value(&args[1..], "--timeout") {
                Ok(v) => v.parse::<u64>().context("invalid --timeout")?,
                Err(_) => 4,
            };
            let options = AutoOptions {
                program: normalize_program(&program)?,
                hosts_file,
                protocol,
                mode,
                history_path,
                qnum,
                timeout_secs,
            };
            // SIGTERM/SIGINT (UI "stop", su teardown): die fast but let
            // run_auto's exit path clean the engine + rules first. The handler
            // re-raises after cleanup via the Termination path in main.
            run_auto_guarded(options)
        }
        "usage" => {
            let pid_raw = parse_named_value(&args[1..], "--pid")?;
            let pid = pid_raw.parse::<u32>().context("invalid --pid")?;
            print_usage(pid)
        }
        other => Err(anyhow!("unknown command: {other}")),
    }
}

fn print_help() {
    println!("nfqws-tester {VERSION}");
    println!("Usage:");
    println!("  nfqws-tester --version");
    println!("  nfqws-tester list --program nfqws|nfqws2");
    println!("  nfqws-tester start --program nfqws|nfqws2 --config /path/to/file.txt [--qnum 200]");
    println!("  nfqws-tester stop");
    println!("  nfqws-tester status");
    println!("  nfqws-tester usage --pid 1234");
}

fn parse_named_value(args: &[String], key: &str) -> Result<String> {
    let mut i = 0usize;
    while i < args.len() {
        let arg = &args[i];
        if arg == key {
            return args.get(i + 1).cloned().with_context(|| format!("{key} requires value"));
        }
        let prefix = format!("{key}=");
        if let Some(v) = arg.strip_prefix(&prefix) {
            return Ok(v.to_string());
        }
        i += 1;
    }
    bail!("missing required option: {key}")
}

fn parse_start_options(args: &[String]) -> Result<StartOptions> {
    let program = normalize_program(&parse_named_value(args, "--program")?)?;
    let config_path = parse_named_value(args, "--config")?;
    let qnum = match parse_named_value(args, "--qnum") {
        Ok(v) => v.parse::<u16>().context("invalid --qnum")?,
        Err(_) => DEFAULT_QNUM,
    };
    Ok(StartOptions { program, config_path, qnum })
}

fn normalize_program(program: &str) -> Result<String> {
    let p = program.trim().to_lowercase();
    match p.as_str() {
        "nfqws" | "nfqws2" => Ok(p),
        _ => bail!("unsupported program: {program}"),
    }
}

fn list_strategies(program: &str) -> Result<()> {
    let dir = strategic_dir(program);
    let mut items: Vec<String> = Vec::new();
    if dir.is_dir() {
        for entry in fs::read_dir(&dir).with_context(|| format!("read {}", dir.display()))? {
            let entry = entry?;
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            if !name.ends_with(".txt") {
                continue;
            }
            items.push(name.to_string());
        }
    }
    items.sort();
    println!("{}", json!({"ok": true, "program": program, "dir": dir, "strategies": items}));
    Ok(())
}

fn start_strategy(options: StartOptions) -> Result<()> {
    let bin = program_bin(&options.program);
    if !bin.is_file() {
        bail!("binary not found: {}", bin.display());
    }
    let config_path = PathBuf::from(&options.config_path);
    if !config_path.is_file() {
        bail!("config not found: {}", config_path.display());
    }

    ensure_work_dir()?;
    cleanup_all()?;

    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let args = normalize_config_args(&raw);
    let filter = extract_proto_port_filter(&raw);
    let mut child = spawn_program(&options.program, &bin, config_path.parent().unwrap_or(Path::new("/")), options.qnum, &args)?;
    if let Err(err) = apply_nfqueue_rules(&options.program, options.qnum, &filter) {
        stop_child(&mut child);
        let _ = cleanup_rules_for_program(&options.program);
        return Err(err);
    }

    let state = SessionState {
        program: options.program.clone(),
        config_path: config_path.display().to_string(),
        config_name: config_path.file_name().and_then(|s| s.to_str()).unwrap_or_default().to_string(),
        pid: child.id(),
        qnum: options.qnum,
        started_at_unix_ms: now_unix_ms(),
    };
    write_session(&state)?;

    // Detach: the engine keeps running after this command exits. The Child
    // handle must stay reaped-able: dropping without wait() would leave a
    // zombie under our parent until it exits. Double-fork semantics come from
    // setsid in pre_exec; the zombie lives until the shell parent exits and
    // init reaps it, which is immediate here.
    let pid = child.id();
    std::mem::forget(child);

    println!("{}", json!({
        "ok": true,
        "program": state.program,
        "config_path": state.config_path,
        "config_name": state.config_name,
        "pid": pid,
        "qnum": state.qnum,
        "filter": {
            "tcp": format_ranges(&filter.tcp),
            "udp": format_ranges(&filter.udp),
        }
    }));
    Ok(())
}

fn stop_session() -> Result<()> {
    cleanup_all()?;
    println!("{}", json!({"ok": true, "active": false}));
    Ok(())
}

fn print_status() -> Result<()> {
    let session = read_session();
    let (active, state) = match session {
        Ok(Some(state)) => {
            let running = process_alive(state.pid);
            (running, Some(state))
        }
        Ok(None) => (false, None),
        Err(err) => return Err(err),
    };
    let response = if let Some(state) = state {
        json!({
            "ok": true,
            "active": active,
            "program": state.program,
            "config_path": state.config_path,
            "config_name": state.config_name,
            "pid": state.pid,
            "qnum": state.qnum,
            "started_at_unix_ms": state.started_at_unix_ms,
        })
    } else {
        json!({"ok": true, "active": false})
    };
    println!("{response}");
    Ok(())
}

fn print_usage(pid: u32) -> Result<()> {
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        println!("{}", json!({"ok": true, "active": false, "pid": pid, "cpu_percent": 0.0, "rss_mb": 0.0}));
        return Ok(());
    }
    let out = capture(&format!("ps -o pid,%cpu,rss -p {pid}"))?;
    let mut cpu_percent = 0.0f32;
    let mut rss_mb = 0.0f32;
    for line in out.lines().skip(1) {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() < 3 {
            continue;
        }
        if cols[0].parse::<u32>().ok() != Some(pid) {
            continue;
        }
        cpu_percent = cols[1].parse::<f32>().unwrap_or(0.0);
        rss_mb = cols[2].parse::<f32>().unwrap_or(0.0) / 1024.0;
    }
    println!("{}", json!({"ok": true, "active": true, "pid": pid, "cpu_percent": cpu_percent, "rss_mb": rss_mb}));
    Ok(())
}

fn program_bin(program: &str) -> PathBuf {
    PathBuf::from(MODULE_DIR).join("bin").join(program)
}

fn strategic_dir(program: &str) -> PathBuf {
    PathBuf::from(STRATEGIC_DIR).join(program)
}

fn ensure_work_dir() -> Result<()> {
    fs::create_dir_all(WORK_DIR).with_context(|| format!("create {WORK_DIR}"))?;
    Ok(())
}

fn write_session(state: &SessionState) -> Result<()> {
    ensure_work_dir()?;
    let text = serde_json::to_string_pretty(state)?;
    fs::write(SESSION_FILE, text).with_context(|| format!("write {SESSION_FILE}"))?;
    Ok(())
}

fn read_session() -> Result<Option<SessionState>> {
    let path = Path::new(SESSION_FILE);
    if !path.is_file() {
        return Ok(None);
    }
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let state = serde_json::from_str::<SessionState>(&raw).with_context(|| format!("parse {}", path.display()))?;
    Ok(Some(state))
}

fn cleanup_all() -> Result<()> {
    // Only the process this tester spawned (recorded in the session file).
    // A previously running tester session is stopped too, so a crashed run
    // never leaves a stray engine behind.
    kill_session_process()?;
    cleanup_rules_for_program("nfqws")?;
    cleanup_rules_for_program("nfqws2")?;
    Ok(())
}

/// Terminate a process the tester spawned, by pid. Never matches by name:
/// the daemon's own nfqws/nfqws2 (queue 200) must survive a blockcheck run.
fn kill_tester_process(pid: u32) -> Result<()> {
    if pid == 0 {
        return Ok(());
    }
    // SIGTERM first so the engine closes its nfq handle cleanly, then SIGKILL.
    let _ = run("sh", &["-c", &format!("kill -TERM {pid} 2>/dev/null || true")]);
    thread::sleep(Duration::from_millis(100));
    let _ = run("sh", &["-c", &format!("kill -KILL {pid} 2>/dev/null || true")]);
    Ok(())
}

/// Kill the process recorded in the tester session file, if any, and clear it.
fn kill_session_process() -> Result<()> {
    if let Ok(Some(state)) = read_session() {
        let _ = kill_tester_process(state.pid);
    }
    let _ = fs::remove_file(SESSION_FILE);
    Ok(())
}


fn chain_name(program: &str) -> &'static str {
    match program {
        "nfqws" => "ZDTNFTST1",
        _ => "ZDTNFTST2",
    }
}

fn apply_nfqueue_rules(program: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    let chain = chain_name(program);
    apply_nfqueue_rules_family("iptables", chain, queue, filter)?;
    if command_exists("ip6tables") {
        let _ = apply_nfqueue_rules_family("ip6tables", chain, queue, filter);
    }
    Ok(())
}

fn cleanup_rules_for_program(program: &str) -> Result<()> {
    let chain = chain_name(program);
    cleanup_family("iptables", chain)?;
    if command_exists("ip6tables") {
        let _ = cleanup_family("ip6tables", chain);
    }
    Ok(())
}

fn cleanup_family(cmd: &str, chain: &str) -> Result<()> {
    loop {
        let rc = run(cmd, &["-w", "5", "-t", "mangle", "-D", "OUTPUT", "-j", chain])?.0;
        if rc != 0 {
            break;
        }
    }
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-F", chain]);
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-X", chain]);
    Ok(())
}

fn apply_nfqueue_rules_family(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    cleanup_family(cmd, chain)?;
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-N", chain]);
    let (rc, out) = run(cmd, &["-w", "5", "-t", "mangle", "-A", "OUTPUT", "-j", chain])?;
    if rc != 0 {
        bail!("{cmd} add OUTPUT jump failed: {out}");
    }

    // prevent loop: skip packets already marked by nfqws/nfqws2
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-A", chain, "-m", "mark", "--mark", DESYNC_MARK, "-j", "RETURN"]);

    if filter.is_empty() {
        let (rc, out) = run(
            cmd,
            &["-w", "5", "-t", "mangle", "-A", chain, "-j", "NFQUEUE", "--queue-num", &queue.to_string(), "--queue-bypass"],
        )?;
        if rc != 0 {
            bail!("{cmd} add NFQUEUE rule failed: {out}");
        }
        return Ok(());
    }

    if multiport_disabled_by_flag() {
        add_filter_rules_per_port(cmd, chain, queue, filter)?;
        return Ok(());
    }

    if let Err(err) = add_filter_rules_multiport(cmd, chain, queue, filter) {
        disable_multiport_persistently(&format!("nfqws-tester {cmd} multiport failed: {err:#}"));
        cleanup_family(cmd, chain)?;
        let _ = run(cmd, &["-w", "5", "-t", "mangle", "-N", chain]);
        let (rc, out) = run(cmd, &["-w", "5", "-t", "mangle", "-A", "OUTPUT", "-j", chain])?;
        if rc != 0 {
            bail!("{cmd} add OUTPUT jump after multiport fallback failed: {out}");
        }
        add_filter_rules_per_port(cmd, chain, queue, filter)?;
    }
    Ok(())
}

fn multiport_no_path() -> PathBuf {
    Path::new(SETTING_DIR).join(MULTIPORT_NO_FILE)
}

fn multiport_disabled_by_flag() -> bool {
    multiport_no_path().is_file()
}

fn disable_multiport_persistently(reason: &str) {
    let path = multiport_no_path();
    if let Some(parent) = path.parent() {
        if let Err(err) = fs::create_dir_all(parent) {
            let _ = writeln!(io::stderr(), "nfqws-tester: multiport disabled in memory, but setting dir create failed: {err:#}");
            return;
        }
    }
    let body = format!("disabled_by=nfqws-tester\nreason={}\n", reason.trim());
    if let Err(err) = fs::write(&path, body) {
        let _ = writeln!(io::stderr(), "nfqws-tester: failed to write {}: {err:#}", path.display());
    }
}

fn add_filter_rules_multiport(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    add_protocol_rules_multiport(cmd, chain, queue, "tcp", &filter.tcp)?;
    add_protocol_rules_multiport(cmd, chain, queue, "udp", &filter.udp)?;
    Ok(())
}

fn add_filter_rules_per_port(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    add_protocol_rules_per_port(cmd, chain, queue, "tcp", &filter.tcp)?;
    add_protocol_rules_per_port(cmd, chain, queue, "udp", &filter.udp)?;
    Ok(())
}

fn add_protocol_rules_multiport(cmd: &str, chain: &str, queue: u16, proto: &str, ranges: &[PortRange]) -> Result<()> {
    if ranges.is_empty() {
        return Ok(());
    }
    for chunk in chunk_multiport(&to_multiport_elements(ranges), 15) {
        let csv = chunk.join(",");
        let (rc, out) = run(
            cmd,
            &[
                "-w", "5", "-t", "mangle", "-A", chain,
                "-p", proto,
                "-m", "multiport",
                "--dports", &csv,
                "-j", "NFQUEUE",
                "--queue-num", &queue.to_string(),
                "--queue-bypass",
            ],
        )?;
        if rc != 0 {
            bail!("{cmd} add {proto} multiport NFQUEUE rule failed: {out}");
        }
    }
    Ok(())
}

fn add_protocol_rules_per_port(cmd: &str, chain: &str, queue: u16, proto: &str, ranges: &[PortRange]) -> Result<()> {
    if ranges.is_empty() {
        return Ok(());
    }
    for range in ranges {
        let dport = if range.start == range.end {
            range.start.to_string()
        } else {
            format!("{}:{}", range.start, range.end)
        };
        let (rc, out) = run(
            cmd,
            &[
                "-w", "5", "-t", "mangle", "-A", chain,
                "-p", proto,
                "--dport", &dport,
                "-j", "NFQUEUE",
                "--queue-num", &queue.to_string(),
                "--queue-bypass",
            ],
        )?;
        if rc != 0 {
            bail!("{cmd} add {proto} per-port NFQUEUE rule failed dport={dport}: {out}");
        }
    }
    Ok(())
}

fn spawn_program(program: &str, bin: &Path, cwd: &Path, qnum: u16, config_args: &[String]) -> Result<Child> {
    let devnull = File::options().read(true).write(true).open("/dev/null").context("open /dev/null")?;
    let devnull_err = devnull.try_clone().context("clone /dev/null")?;
    let mut cmd = Command::new(bin);
    let fwmark = match program {
        "nfqws" => format!("--dpi-desync-fwmark={DESYNC_MARK}"),
        _ => format!("--fwmark={DESYNC_MARK}"),
    };
    cmd.current_dir(cwd)
        .arg("--uid=0:0")
        .arg(fwmark)
        .arg(format!("--qnum={qnum}"))
        .args(config_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(devnull))
        .stderr(Stdio::from(devnull_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    thread::sleep(Duration::from_millis(200));
    if !process_alive(pid) {
        // Reap via wait() so a failed spawn never lingers as a zombie.
        let mut child = child;
        let _ = child.wait();
        bail!("{program} exited immediately after start")
    }
    Ok(child)
}

/// SIGKILL a tester-spawned engine child and reap it so no zombie remains.
/// Dropping the returned Child would leak the zombie entry under init.
fn stop_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

fn process_alive(pid: u32) -> bool {
    PathBuf::from("/proc").join(pid.to_string()).is_dir()
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_else(|_| Duration::from_secs(0))
        .as_millis() as u64
}

fn run(cmd: &str, args: &[&str]) -> Result<(i32, String)> {
    let out = Command::new(cmd)
        .args(args)
        .output()
        .with_context(|| format!("run {cmd}"))?;
    let code = out.status.code().unwrap_or(1);
    let text = String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
    Ok((code, text))
}

fn capture(script: &str) -> Result<String> {
    let (code, out) = run("sh", &["-c", script])?;
    if code != 0 {
        bail!("command failed: {script}: {out}");
    }
    Ok(out)
}

fn command_exists(cmd: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {cmd} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn normalize_config_args(raw: &str) -> Vec<String> {
    let mut s = String::with_capacity(raw.len());
    let mut it = raw.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.peek().copied() {
                Some('\n') => {
                    it.next();
                    continue;
                }
                Some('\r') => {
                    it.next();
                    if matches!(it.peek().copied(), Some('\n')) {
                        it.next();
                    }
                    continue;
                }
                _ => {}
            }
        }
        if c == '\n' || c == '\r' {
            s.push(' ');
        } else {
            s.push(c);
        }
    }
    s.split_whitespace()
        .filter(|token| *token != "\\")
        .map(|token| token.to_string())
        .collect()
}

fn extract_proto_port_filter(raw: &str) -> ProtoPortFilter {
    let mut tcp_specs = Vec::new();
    let mut udp_specs = Vec::new();
    let s = raw.replace("\\\r\n", "").replace("\\\n", "");
    for line in s.lines() {
        let mut l = line;
        if let Some(pos) = l.find('#') {
            l = &l[..pos];
        }
        let l = l.trim();
        if l.is_empty() {
            continue;
        }
        collect_specs_from_line(l, "--filter-tcp=", &mut tcp_specs);
        collect_specs_from_line(l, "--filter-udp=", &mut udp_specs);
    }
    ProtoPortFilter {
        tcp: parse_and_merge_specs(&tcp_specs),
        udp: parse_and_merge_specs(&udp_specs),
    }
}

fn collect_specs_from_line(line: &str, key: &str, out: &mut Vec<String>) {
    let mut start = 0usize;
    while let Some(rel) = line[start..].find(key) {
        let pos = start + rel + key.len();
        let tail = &line[pos..];
        let mut end = tail.len();
        for (i, ch) in tail.char_indices() {
            if ch.is_whitespace() {
                let rest_trim = tail[i..].trim_start();
                if rest_trim.starts_with("--") {
                    end = i;
                    break;
                }
            }
        }
        let val = tail[..end].trim();
        if !val.is_empty() {
            out.push(val.to_string());
        }
        start = pos;
    }
}

fn parse_and_merge_specs(specs: &[String]) -> Vec<PortRange> {
    let mut all = Vec::new();
    for spec in specs {
        all.extend(parse_ranges(spec));
    }
    merge_ranges(all)
}

fn parse_ranges(spec: &str) -> Vec<PortRange> {
    let mut out = Vec::new();
    let cleaned = spec.replace(' ', "").replace('\t', "");
    for token in cleaned.split(',') {
        let t = token.trim();
        if t.is_empty() {
            continue;
        }
        if let Some((a, b)) = t.split_once('-').or_else(|| t.split_once(':')) {
            if let (Ok(sa), Ok(sb)) = (a.parse::<u16>(), b.parse::<u16>()) {
                if (1..=65535).contains(&sa) && (1..=65535).contains(&sb) {
                    let (start, end) = if sa <= sb { (sa, sb) } else { (sb, sa) };
                    out.push(PortRange { start, end });
                }
            }
            continue;
        }
        if let Ok(port) = t.parse::<u16>() {
            if (1..=65535).contains(&port) {
                out.push(PortRange { start: port, end: port });
            }
        }
    }
    out
}

fn merge_ranges(mut ranges: Vec<PortRange>) -> Vec<PortRange> {
    if ranges.is_empty() {
        return ranges;
    }
    ranges.sort_by_key(|r| (r.start, r.end));
    let mut merged = Vec::new();
    let mut cur = ranges[0];
    for r in ranges.into_iter().skip(1) {
        if r.start <= cur.end.saturating_add(1) {
            cur.end = cur.end.max(r.end);
        } else {
            merged.push(cur);
            cur = r;
        }
    }
    merged.push(cur);
    merged
}

fn to_multiport_elements(ranges: &[PortRange]) -> Vec<String> {
    ranges
        .iter()
        .map(|r| {
            if r.start == r.end {
                r.start.to_string()
            } else {
                format!("{}:{}", r.start, r.end)
            }
        })
        .collect()
}

fn chunk_multiport(elements: &[String], max_elems: usize) -> Vec<Vec<String>> {
    if elements.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < elements.len() {
        let end = (i + max_elems).min(elements.len());
        out.push(elements[i..end].to_vec());
        i = end;
    }
    out
}

fn format_ranges(ranges: &[PortRange]) -> String {
    ranges
        .iter()
        .map(|r| if r.start == r.end { r.start.to_string() } else { format!("{}-{}", r.start, r.end) })
        .collect::<Vec<_>>()
        .join(",")
}

// ---------------------------------------------------------------------------
// Automatic blockcheck — runs every strategy, probes hosts, reports NDJSON
// ---------------------------------------------------------------------------

fn load_hosts(path: &str) -> Result<Vec<String>> {
    let raw = fs::read_to_string(path)
        .with_context(|| format!("read hosts file: {path}"))?;
    let mut hosts = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        // Extract host from "domain" or "domain:port" or "http(s)://domain..."
        let host = trimmed
            .trim_start_matches("http://")
            .trim_start_matches("https://");
        let host = host.split('/').next().unwrap_or(host);
        let host = host.split(':').next().unwrap_or(host);
        let host = host.trim();
        // Deduplicate hosts while preserving order so repeated entries in the
        // hostlist do not inflate the per-strategy probe count.
        if !host.is_empty() && seen.insert(host.to_string()) {
            hosts.push(host.to_string());
        }
    }
    if hosts.is_empty() {
        bail!("no hosts found in {path}");
    }
    Ok(hosts)
}

fn resolve_ip(host: &str) -> Option<String> {
    let out = capture(&format!("getent hosts {host} 2>/dev/null | awk '{{print $1; exit}}'")).ok()?;
    let ip = out.trim();
    if ip.is_empty() {
        None
    } else {
        Some(ip.to_string())
    }
}

/// Returns (curl exit code, http status, location header, downloaded bytes).
fn curl_probe(host: &str, ip: Option<&str>, timeout_secs: u64) -> Result<(i32, u32, String, u64)> {
    let url = format!("https://{host}/");
    let connect_to = ip.map(|ip| format!("{host}:443:{ip}"));
    let timeout_str = timeout_secs.to_string();
    let host_header = format!("Host: {host}");
    let user_agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    let mut args = vec![
        "-sS", "-o", "/dev/null",
        "-w", "%{http_code}\\n%header{location}\\n%{size_download}",
        "--max-time", &timeout_str,
        "--connect-timeout", &timeout_str,
    ];
    if let Some(ct) = connect_to.as_deref() {
        // Pin to the pre-resolved IP (baseline reuse).
        args.extend(["--connect-to", ct, "-H", &host_header]);
    }
    // Without an IP, curl resolves the host itself.
    args.extend(["-A", user_agent, "--compressed", "-L", "--max-redirs", "3", &url]);

    let (code, out) = run(
        "sh",
        &[
            "-c",
            &format!(
                "curl {}",
                args.iter()
                    .map(|a| sh_quote(a))
                    .collect::<Vec<_>>()
                    .join(" ")
            ),
        ],
    )?;
    let lines: Vec<&str> = out.lines().collect();
    let http_code = lines.first().and_then(|s| s.trim().parse::<u32>().ok()).unwrap_or(0);
    let location = lines.get(1).map(|s| s.trim().to_string()).unwrap_or_default();
    let size_bytes = lines.get(2).and_then(|s| s.trim().parse::<u64>().ok()).unwrap_or(0);

    Ok((code, http_code, location, size_bytes))
}

/// Quick HEAD probe used for internet-control hosts. Returns (curl rc, http code).
fn curl_probe_baseline(host: &str, timeout_secs: u64) -> Result<(i32, u32)> {
    let url = format!("https://{host}/");
    let timeout_str = timeout_secs.to_string();
    let user_agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // blockcheck2.sh approach: quick HEAD, no redirects, no body
    let args = vec![
        "-sS", "-o", "/dev/null",
        "-w", "%{http_code}",
        "--max-time", &timeout_str,
        "--connect-timeout", &timeout_str,
        "-I",
        "-A", user_agent,
        &url,
    ];

    let (rc, out) = run(
        "sh",
        &[
            "-c",
            &format!(
                "curl {}",
                args.iter()
                    .map(|a| sh_quote(a))
                    .collect::<Vec<_>>()
                    .join(" ")
            ),
        ],
    )?;
    let http_code = out.trim().parse::<u32>().unwrap_or(0);

    Ok((rc, http_code))
}

static SHUTDOWN_REQUESTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

extern "C" fn tester_signal_handler(_sig: libc::c_int) {
    SHUTDOWN_REQUESTED.store(true, std::sync::atomic::Ordering::SeqCst);
}

fn shutdown_requested() -> bool {
    SHUTDOWN_REQUESTED.load(std::sync::atomic::Ordering::SeqCst)
}

fn run_auto_guarded(opts: AutoOptions) -> Result<()> {
    unsafe {
        libc::signal(libc::SIGTERM, tester_signal_handler as libc::sighandler_t);
        libc::signal(libc::SIGINT, tester_signal_handler as libc::sighandler_t);
    }
    let result = run_auto(&opts);
    // run_auto already cleaned up; ensure nothing is left even on error.
    let _ = cleanup_all();
    result
}

fn emit_event(event: &serde_json::Value) {
    let stdout = io::stdout();
    let mut handle = stdout.lock();
    let _ = writeln!(handle, "{event}");
    let _ = handle.flush();
}

// ---------------------------------------------------------------------------
// Strategy scan pipeline (ported from zapretgui strategy_search)
// ---------------------------------------------------------------------------

const CONFIRM_ATTEMPTS: u32 = 3;
const CRASH_RETRIES: u32 = 2;
const NETWORK_WAIT_SECONDS: u64 = 30;
const UDP_ATTEMPTS: u32 = 2;
const UDP_REPLY_TIMEOUT_MS: u64 = 1200;
const FAILED_MEMORY_SECONDS: u64 = 14 * 24 * 3600;
const FREEZE_MIN_BYTES: u64 = 14_000;
const FREEZE_MAX_BYTES: u64 = 24_000;
const LUA_INIT_ZAPRET: &str = "--lua-init=@/data/adb/modules/ZDT-D/strategic/lua/zapret-lib.lua";

const CONTROL_HOSTS: [&str; 3] = ["ya.ru", "www.microsoft.com", "www.google.com"];

/// DNS answers that indicate a provider stub page instead of the real host.
const KNOWN_BLOCK_IPS: [&str; 10] = [
    "127.0.0.1", "0.0.0.0", "10.10.10.10", "195.82.146.214", "81.19.72.32",
    "213.180.193.250", "217.169.80.229", "62.33.207.196", "62.33.207.197",
    "62.33.207.198",
];

#[derive(Debug, Clone)]
struct AutoOptions {
    program: String,
    hosts_file: Option<String>,
    protocol: String,
    mode: String,
    history_path: Option<String>,
    qnum: u16,
    timeout_secs: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProbeVerdict {
    Ok,
    Blocked(&'static str),
    Unreachable(&'static str),
}

fn verdict_class(v: &ProbeVerdict) -> &'static str {
    match v {
        ProbeVerdict::Ok => "ok",
        ProbeVerdict::Blocked(_) => "blocked",
        ProbeVerdict::Unreachable(_) => "unreachable",
    }
}

fn verdict_reason(v: &ProbeVerdict) -> &'static str {
    match v {
        ProbeVerdict::Ok => "http_ok",
        ProbeVerdict::Blocked(reason) | ProbeVerdict::Unreachable(reason) => reason,
    }
}

/// Map a curl result onto a DPI verdict (zapretgui verdict.judge_https adapted
/// to curl exit codes; raw-TLS exceptions have no curl equivalent).
fn classify_https(rc: i32, http_code: u32, size_bytes: u64) -> ProbeVerdict {
    if rc == 0 {
        if http_code == 400 {
            return ProbeVerdict::Blocked("http_400");
        }
        if http_code > 0 {
            return ProbeVerdict::Ok;
        }
        return ProbeVerdict::Blocked("reset");
    }
    match rc {
        51 | 58 | 59 | 60 | 90 | 91 => ProbeVerdict::Blocked("cert_substitute"),
        35 => ProbeVerdict::Blocked("tls_error"),
        52 | 56 => ProbeVerdict::Blocked("reset"),
        28 => ProbeVerdict::Blocked("timeout"),
        18 if (FREEZE_MIN_BYTES..=FREEZE_MAX_BYTES).contains(&size_bytes) => ProbeVerdict::Blocked("body_cut"),
        18 => ProbeVerdict::Blocked("reset"),
        6 => ProbeVerdict::Unreachable("resolve_failed"),
        7 => ProbeVerdict::Unreachable("connect_failed"),
        _ => ProbeVerdict::Blocked("reset"),
    }
}

// --- UDP probes -------------------------------------------------------------

fn random_bytes(n: usize) -> Vec<u8> {
    use std::io::Read;
    if let Ok(mut f) = File::open("/dev/urandom") {
        let mut buf = vec![0u8; n];
        if f.read_exact(&mut buf).is_ok() {
            return buf;
        }
    }
    // Fallback: timestamp-derived bytes (only used if /dev/urandom is broken).
    let seed = now_unix_ms();
    let mut buf = vec![0u8; n];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = ((seed >> ((i % 8) * 8)) as u8) ^ (i as u8).wrapping_mul(0x9d);
    }
    buf
}

/// STUN RFC5389 binding request: type 0x0001, length 0, magic cookie, 96-bit id.
fn stun_request() -> [u8; 20] {
    let mut out = [0u8; 20];
    out[0] = 0x00;
    out[1] = 0x01;
    out[4..8].copy_from_slice(&0x2112A442u32.to_be_bytes());
    out[8..20].copy_from_slice(&random_bytes(12));
    out
}

/// Source-engine A2S_INFO query.
fn a2s_request() -> &'static [u8] {
    b"\xff\xff\xff\xffTSource Engine Query\x00"
}

/// RakNet (Minecraft Bedrock) unconnected ping: 0x01 + u64be ms + magic + guid.
fn bedrock_request() -> Vec<u8> {
    const MAGIC: [u8; 16] = [
        0x00, 0xff, 0xff, 0x00, 0xfe, 0xfe, 0xfe, 0xfe,
        0xfd, 0xfd, 0xfd, 0xfd, 0x12, 0x34, 0x56, 0x78,
    ];
    let mut out = Vec::with_capacity(33);
    out.push(0x01);
    out.extend_from_slice(&now_unix_ms().to_be_bytes());
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&random_bytes(8));
    out
}

/// Send a UDP request and wait for any datagram back. Any reply (even a
/// malformed one) proves the path is open; ICMP refused = address reachable
/// but closed; silence after all attempts = filtered.
fn udp_exchange(ip: &str, port: u16, request: &[u8]) -> ProbeVerdict {
    use std::net::UdpSocket;
    let (bind, target) = if ip.contains(':') {
        ("[::]:0", format!("[{ip}]:{port}"))
    } else {
        ("0.0.0.0:0", format!("{ip}:{port}"))
    };
    let socket = match UdpSocket::bind(bind) {
        Ok(s) => s,
        Err(_) => return ProbeVerdict::Unreachable("connect_failed"),
    };
    if socket.connect(&target).is_err() {
        return ProbeVerdict::Unreachable("connect_failed");
    }
    let _ = socket.set_read_timeout(Some(Duration::from_millis(UDP_REPLY_TIMEOUT_MS)));
    for _ in 0..UDP_ATTEMPTS {
        if socket.send(request).is_err() {
            return ProbeVerdict::Unreachable("connect_failed");
        }
        let mut buf = [0u8; 2048];
        match socket.recv(&mut buf) {
            Ok(_) => return ProbeVerdict::Ok,
            Err(e) => match e.kind() {
                io::ErrorKind::ConnectionRefused => return ProbeVerdict::Unreachable("icmp_closed"),
                io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => {}
                _ => return ProbeVerdict::Unreachable("connect_failed"),
            },
        }
    }
    ProbeVerdict::Blocked("no_reply")
}

// --- Probe targets ----------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProbeKind {
    Https,
    Stun,
    A2s,
    Bedrock,
}

#[derive(Debug, Clone)]
struct ProbeTarget {
    key: String,
    host: String,
    port: u16,
    kind: ProbeKind,
}

const STUN_TARGETS: [(&str, u16); 5] = [
    ("stun.l.google.com", 19302),
    ("stun.cloudflare.com", 3478),
    ("global.stun.twilio.com", 3478),
    ("stun.telegram.org", 3478),
    ("stun.voip.telegram.org", 3478),
];

const UDP_GAMES_TARGETS: [(&str, ProbeKind, &str, u16); 3] = [
    ("Rust A2S", ProbeKind::A2s, "205.178.168.170", 28015),
    ("CS A2S", ProbeKind::A2s, "46.174.55.234", 27015),
    ("Bedrock CubeCraft", ProbeKind::Bedrock, "play.cubecraft.net", 19132),
];

fn auto_targets(opts: &AutoOptions) -> Result<Vec<ProbeTarget>> {
    match opts.protocol.as_str() {
        "tcp_https" => {
            let path = opts.hosts_file.as_deref().unwrap_or_default();
            let hosts = load_hosts(path)?;
            Ok(hosts
                .into_iter()
                .map(|h| ProbeTarget { key: h.clone(), host: h, port: 443, kind: ProbeKind::Https })
                .collect())
        }
        "stun_voice" => Ok(STUN_TARGETS
            .iter()
            .map(|(host, port)| ProbeTarget {
                key: format!("{host}:{port}"),
                host: host.to_string(),
                port: *port,
                kind: ProbeKind::Stun,
            })
            .collect()),
        "udp_games" => Ok(UDP_GAMES_TARGETS
            .iter()
            .map(|(name, kind, host, port)| ProbeTarget {
                key: name.to_string(),
                host: host.to_string(),
                port: *port,
                kind: *kind,
            })
            .collect()),
        other => bail!("unknown protocol: {other}"),
    }
}

/// History key component: protocol name for the fixed UDP target sets,
/// hosts-file basename for HTTPS (matches BlockcheckHistory on the app side;
/// custom domains use a temp file named `custom_<domain>`).
fn auto_target_key(opts: &AutoOptions) -> String {
    if opts.protocol != "tcp_https" {
        return opts.protocol.clone();
    }
    opts.hosts_file
        .as_deref()
        .and_then(|p| Path::new(p).file_name())
        .and_then(|s| s.to_str())
        .unwrap_or_default()
        .to_string()
}

struct ProbeOutcome {
    verdict: ProbeVerdict,
    http_code: u32,
    size_bytes: u64,
    time_ms: u64,
}

impl ProbeOutcome {
    fn failed_resolve() -> Self {
        ProbeOutcome { verdict: ProbeVerdict::Unreachable("resolve_failed"), http_code: 0, size_bytes: 0, time_ms: 0 }
    }
}

fn probe_target(target: &ProbeTarget, resolved_ip: Option<&String>, timeout_secs: u64) -> ProbeOutcome {
    let started = std::time::Instant::now();
    let (verdict, http_code, size_bytes) = match target.kind {
        ProbeKind::Https => {
            if resolved_ip.map(|ip| KNOWN_BLOCK_IPS.contains(&ip.as_str())).unwrap_or(false) {
                // DNS stub: no point probing, the answer is a block page IP.
                (ProbeVerdict::Blocked("dns_stub"), 0, 0)
            } else {
                // No pre-resolved IP: let curl resolve itself (getent may be
                // missing or broken on the device while curl resolves fine).
                match curl_probe(&target.host, resolved_ip.map(|s| s.as_str()), timeout_secs) {
                    Ok((rc, code, _location, size)) => (classify_https(rc, code, size), code, size),
                    Err(_) => (ProbeVerdict::Unreachable("connect_failed"), 0, 0),
                }
            }
        }
        kind => {
            let Some(ip) = resolved_ip else {
                return ProbeOutcome::failed_resolve();
            };
            let request = match kind {
                ProbeKind::Stun => stun_request().to_vec(),
                ProbeKind::A2s => a2s_request().to_vec(),
                ProbeKind::Bedrock => bedrock_request(),
                ProbeKind::Https => unreachable!(),
            };
            (udp_exchange(ip, target.port, &request), 0, 0)
        }
    };
    ProbeOutcome { verdict, http_code, size_bytes, time_ms: started.elapsed().as_millis() as u64 }
}

/// Internet reachability control: any of the control hosts answering HTTPS.
fn control_alive() -> bool {
    CONTROL_HOSTS
        .iter()
        .any(|host| curl_probe_baseline(host, 3).map(|(_rc, code)| code > 0).unwrap_or(false))
}

// --- History + ordering (zapretgui ordering.py) ------------------------------

#[derive(Debug, Default)]
struct HistoryEntry {
    confirmed: Vec<String>,
    failed: std::collections::HashMap<String, u64>,
}

/// Read the app-written history file. Any problem (missing, unreadable,
/// unparsable) degrades to empty history — ordering then is interleave-only.
fn load_history(path: Option<&str>, program: &str, protocol: &str, target_key: &str) -> HistoryEntry {
    let Some(path) = path else { return HistoryEntry::default() };
    let Ok(raw) = fs::read_to_string(path) else { return HistoryEntry::default() };
    let Ok(root) = serde_json::from_str::<serde_json::Value>(&raw) else { return HistoryEntry::default() };
    let key = format!("{program}|{protocol}|{target_key}");
    let Some(entry) = root.get(&key) else { return HistoryEntry::default() };
    let confirmed = entry
        .get("confirmed")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    let mut failed = std::collections::HashMap::new();
    if let Some(map) = entry.get("failed").and_then(|v| v.as_object()) {
        for (k, v) in map {
            if let Some(ts) = v.as_u64() {
                failed.insert(k.clone(), ts);
            }
        }
    }
    HistoryEntry { confirmed, failed }
}

#[derive(Debug, Clone)]
struct Candidate {
    name: String,
    techniques: std::collections::BTreeSet<String>,
}

impl Candidate {
    /// Pass-only strategies carry no bypass technique: scanning them proves
    /// nothing (they are the pass-control baseline, not candidates).
    fn is_pass(&self) -> bool {
        self.techniques.is_empty()
            || (self.techniques.len() == 1 && self.techniques.contains("pass"))
    }
}

/// Technique fingerprint of a strategy config: desync function names.
fn technique_set(program: &str, raw_config: &str) -> std::collections::BTreeSet<String> {
    let mut out = std::collections::BTreeSet::new();
    if program == "nfqws2" {
        // One arg per line; the function name ends at the first ':' (args).
        for line in raw_config.lines() {
            let line = line.trim();
            if let Some(rest) = line.strip_prefix("--lua-desync=") {
                let name: String = rest
                    .chars()
                    .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
                    .collect();
                if !name.is_empty() {
                    out.insert(name.to_lowercase());
                }
            }
        }
    } else {
        // nfqws v1: whitespace-separated args, comma-separated techniques.
        for token in raw_config.split_whitespace() {
            if let Some(value) = token.strip_prefix("--dpi-desync=") {
                for part in value.split(',') {
                    let part = part.trim().to_lowercase();
                    if !part.is_empty() {
                        out.insert(part);
                    }
                }
            }
        }
    }
    out
}

/// Round-robin across technique groups (first-seen group order).
fn interleave_by_technique<'a>(candidates: &[&'a Candidate]) -> Vec<&'a Candidate> {
    let mut group_order: Vec<&std::collections::BTreeSet<String>> = Vec::new();
    let mut groups: std::collections::HashMap<&std::collections::BTreeSet<String>, Vec<&Candidate>> =
        std::collections::HashMap::new();
    for c in candidates {
        let key = &c.techniques;
        if !groups.contains_key(key) {
            group_order.push(key);
        }
        groups.entry(key).or_default().push(*c);
    }
    let mut queues: Vec<std::collections::VecDeque<&Candidate>> = group_order
        .into_iter()
        .filter_map(|k| groups.remove(k))
        .map(|v| v.into_iter().collect())
        .collect();
    let mut out = Vec::new();
    loop {
        let mut progressed = false;
        for queue in queues.iter_mut() {
            if let Some(c) = queue.pop_front() {
                out.push(c);
                progressed = true;
            }
        }
        if !progressed {
            break;
        }
    }
    out
}

fn order_candidates(
    candidates: &[Candidate],
    confirmed: &[String],
    failed_at: &std::collections::HashMap<String, u64>,
    now: u64,
) -> Vec<Candidate> {
    let pool: Vec<&Candidate> = candidates.iter().filter(|c| !c.is_pass()).collect();
    // Confirmed-by-history first, in history order, skipping gone strategies.
    let mut first: Vec<&Candidate> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for id in confirmed {
        if !seen.insert(id.as_str()) {
            continue;
        }
        if let Some(c) = pool.iter().find(|c| c.name == *id) {
            first.push(*c);
        }
    }
    let taken: std::collections::HashSet<&str> = first.iter().map(|c| c.name.as_str()).collect();
    let mut fresh: Vec<&Candidate> = Vec::new();
    let mut recently_failed: Vec<(u64, &Candidate)> = Vec::new();
    for c in &pool {
        if taken.contains(c.name.as_str()) {
            continue;
        }
        let failed_time = failed_at.get(&c.name).copied().unwrap_or(0);
        if failed_time > 0 && now.saturating_sub(failed_time) < FAILED_MEMORY_SECONDS {
            recently_failed.push((failed_time, *c));
        } else {
            fresh.push(*c);
        }
    }
    // Longer-ago failures come before ones that failed just now.
    recently_failed.sort_by_key(|(t, _)| *t);
    let mut out: Vec<Candidate> = first.into_iter().cloned().collect();
    out.extend(interleave_by_technique(&fresh).into_iter().cloned());
    let rf: Vec<&Candidate> = recently_failed.into_iter().map(|(_, c)| c).collect();
    out.extend(interleave_by_technique(&rf).into_iter().cloned());
    out
}

fn batch_for_mode(ordered: Vec<Candidate>, mode: &str) -> Vec<Candidate> {
    let cap = match mode {
        "quick" => 30,
        "standard" => 80,
        _ => usize::MAX,
    };
    ordered.into_iter().take(cap).collect()
}

fn list_strategy_names(program: &str) -> Result<Vec<String>> {
    let dir = strategic_dir(program);
    let mut items: Vec<String> = Vec::new();
    if dir.is_dir() {
        for entry in fs::read_dir(&dir).with_context(|| format!("read {}", dir.display()))? {
            let entry = entry?;
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            if !name.ends_with(".txt") {
                continue;
            }
            items.push(name.to_string());
        }
    }
    items.sort();
    Ok(items)
}

// --- run_auto ---------------------------------------------------------------

/// Emit a confirm request and wait for one answer line on stdin (fd 0 is the
/// su pipe held open by the app). EOF or error = decline.
fn ask_confirm(question: &str) -> bool {
    emit_event(&json!({
        "type": "auto_confirm_needed",
        "question": question,
        "ts": now_unix_ms(),
    }));
    let mut line = String::new();
    match io::stdin().read_line(&mut line) {
        Ok(_) => line.trim().eq_ignore_ascii_case("y"),
        Err(_) => false,
    }
}

/// Early exit used when the user declines a confirm prompt.
fn finish_declined(program: &str, total: usize) -> Result<()> {
    emit_event(&json!({
        "type": "auto_finished",
        "ok": true,
        "program": program,
        "total_strategies": total,
        "working": Vec::<String>::new(),
        "failed": Vec::<String>::new(),
        "interrupted": true,
        "stop_kind": "declined",
        "forced": false,
        "ts": now_unix_ms(),
    }));
    let _ = cleanup_all();
    Ok(())
}

fn emit_probe_event(strategy: &str, target: &ProbeTarget, outcome: &ProbeOutcome, attempt: u32, attempts_ok: u32) {
    let mut event = json!({
        "type": "auto_strategy_probe",
        "strategy": strategy,
        "host": target.key,
        "attempt": attempt,
        "attempts_ok": attempts_ok,
        "attempts_total": CONFIRM_ATTEMPTS,
        "works": outcome.verdict == ProbeVerdict::Ok,
        "verdict": verdict_class(&outcome.verdict),
        "reason": verdict_reason(&outcome.verdict),
        "time_ms": outcome.time_ms,
        "ts": now_unix_ms(),
    });
    if target.kind == ProbeKind::Https {
        event["http_code"] = json!(outcome.http_code);
        event["size"] = json!(outcome.size_bytes.to_string());
    }
    emit_event(&event);
}

fn pass_filter_for_protocol(protocol: &str) -> ProtoPortFilter {
    let ranges = |ports: &[u16]| -> Vec<PortRange> {
        ports.iter().map(|p| PortRange { start: *p, end: *p }).collect()
    };
    match protocol {
        "stun_voice" => ProtoPortFilter { tcp: Vec::new(), udp: ranges(&[3478, 19302]) },
        "udp_games" => ProtoPortFilter { tcp: Vec::new(), udp: ranges(&[19132, 27015, 28015]) },
        _ => ProtoPortFilter { tcp: ranges(&[443]), udp: Vec::new() },
    }
}

fn run_auto(opts: &AutoOptions) -> Result<()> {
    let program = opts.program.as_str();
    let targets = auto_targets(opts)?;
    let target_key = auto_target_key(opts);
    let history = load_history(opts.history_path.as_deref(), program, &opts.protocol, &target_key);
    let now = now_unix_ms() / 1000;

    let all_names = list_strategy_names(program)?;
    if all_names.is_empty() {
        bail!("no strategies found for program {program}");
    }
    let candidates: Vec<Candidate> = all_names
        .iter()
        .map(|name| {
            let raw = fs::read_to_string(strategic_dir(program).join(name)).unwrap_or_default();
            Candidate { name: name.clone(), techniques: technique_set(program, &raw) }
        })
        .collect();
    let ordered = order_candidates(&candidates, &history.confirmed, &history.failed, now);
    let strategies = batch_for_mode(ordered, &opts.mode);
    let total = strategies.len();
    let total_targets = targets.len();

    emit_event(&json!({
        "type": "auto_started",
        "ok": true,
        "program": program,
        "qnum": opts.qnum,
        "protocol": opts.protocol,
        "mode": opts.mode,
        "total_strategies": total,
        "total_hosts": total_targets,
        "hosts": targets.iter().map(|t| t.key.clone()).collect::<Vec<_>>(),
        "strategies": strategies.iter().map(|c| c.name.clone()).collect::<Vec<_>>(),
        "ts": now_unix_ms(),
    }));

    // Phase: network — without internet every verdict would be garbage.
    emit_event(&json!({ "type": "auto_phase", "phase": "network", "ts": now_unix_ms() }));
    if !control_alive() {
        emit_event(&json!({
            "type": "auto_fatal",
            "stop_kind": "no_internet",
            "message": "no internet connection",
            "ts": now_unix_ms(),
        }));
        let _ = cleanup_all();
        return Ok(());
    }

    // Phase: baseline — probe all targets without any bypass.
    emit_event(&json!({ "type": "auto_phase", "phase": "baseline", "ts": now_unix_ms() }));

    let mut baseline: std::collections::HashMap<String, ProbeVerdict> = std::collections::HashMap::new();
    // Resolve each target once; reuse for baseline and all strategy probes.
    let mut resolved_ips: std::collections::HashMap<String, Option<String>> = std::collections::HashMap::new();
    for target in &targets {
        if shutdown_requested() {
            return finish_declined(program, total);
        }
        let ip = resolve_ip(&target.host);
        resolved_ips.insert(target.key.clone(), ip.clone());
        let outcome = probe_target(target, ip.as_ref(), opts.timeout_secs);
        baseline.insert(target.key.clone(), outcome.verdict);
        let mut event = json!({
            "type": "auto_baseline_probe",
            "host": target.key,
            "verdict": verdict_class(&outcome.verdict),
            "reason": verdict_reason(&outcome.verdict),
            "ts": now_unix_ms(),
        });
        if target.kind == ProbeKind::Https {
            event["http_code"] = json!(outcome.http_code);
            event["size"] = json!(outcome.size_bytes.to_string());
        }
        emit_event(&event);
    }

    let blocked_keys: Vec<String> = targets
        .iter()
        .filter(|t| matches!(baseline.get(&t.key), Some(ProbeVerdict::Blocked(_))))
        .map(|t| t.key.clone())
        .collect();

    if total_targets > 0
        && blocked_keys.len() == total_targets
        && blocked_keys
            .iter()
            .all(|k| matches!(baseline.get(k), Some(ProbeVerdict::Blocked("dns_stub"))))
    {
        emit_event(&json!({
            "type": "auto_fatal",
            "stop_kind": "dns_stub",
            "message": "DNS returns provider block pages",
            "ts": now_unix_ms(),
        }));
        let _ = cleanup_all();
        return Ok(());
    }

    if targets
        .iter()
        .all(|t| matches!(baseline.get(&t.key), Some(ProbeVerdict::Unreachable(_))))
    {
        emit_event(&json!({
            "type": "auto_fatal",
            "stop_kind": "address_block",
            "message": "target addresses are unreachable",
            "ts": now_unix_ms(),
        }));
        let _ = cleanup_all();
        return Ok(());
    }

    let mut forced = false;
    if blocked_keys.is_empty() {
        // Everything opens without a bypass: results can only be informational.
        if !ask_confirm("baseline_open") {
            return finish_declined(program, total);
        }
        forced = true;
    }

    // Phase: pass control — a do-nothing strategy must NOT open the targets.
    // If it does, traffic is not really going through the engine.
    if !blocked_keys.is_empty() {
        emit_event(&json!({ "type": "auto_phase", "phase": "pass_control", "ts": now_unix_ms() }));
        let pass_raw = if program == "nfqws2" {
            format!("{LUA_INIT_ZAPRET}\n--lua-desync=pass\n")
        } else {
            // nfqws v1 has no no-op desync: empty config (no filters matched).
            String::new()
        };
        ensure_work_dir()?;
        let pass_path = Path::new(WORK_DIR).join("pass_probe.txt");
        fs::write(&pass_path, &pass_raw).with_context(|| format!("write {}", pass_path.display()))?;
        let bin = program_bin(program);
        let pass_args = normalize_config_args(&pass_raw);
        let pass_filter = pass_filter_for_protocol(&opts.protocol);
        let spawned = if bin.is_file() {
            cleanup_all()?;
            spawn_program(program, &bin, pass_path.parent().unwrap_or(Path::new("/")), opts.qnum, &pass_args)
                .ok()
                .and_then(|mut child| match apply_nfqueue_rules(program, opts.qnum, &pass_filter) {
                    Ok(()) => Some(child),
                    Err(_) => {
                        stop_child(&mut child);
                        let _ = cleanup_rules_for_program(program);
                        None
                    }
                })
        } else {
            None
        };
        match spawned {
            Some(mut child) => {
                thread::sleep(Duration::from_millis(500));
                let mut pass_opened = false;
                for target in targets.iter().filter(|t| blocked_keys.contains(&t.key)) {
                    if shutdown_requested() {
                        break;
                    }
                    let ip = resolved_ips.get(&target.key).cloned().flatten();
                    let outcome = probe_target(target, ip.as_ref(), opts.timeout_secs);
                    let mut event = json!({
                        "type": "auto_pass_probe",
                        "host": target.key,
                        "verdict": verdict_class(&outcome.verdict),
                        "reason": verdict_reason(&outcome.verdict),
                        "ts": now_unix_ms(),
                    });
                    if target.kind == ProbeKind::Https {
                        event["http_code"] = json!(outcome.http_code);
                    }
                    emit_event(&event);
                    if outcome.verdict == ProbeVerdict::Ok {
                        pass_opened = true;
                    }
                }
                stop_child(&mut child);
                let _ = cleanup_rules_for_program(program);
                let _ = fs::remove_file(SESSION_FILE);
                if pass_opened && !forced {
                    // Targets open even with a do-nothing strategy: the engine
                    // does not really see this traffic; measurement unreliable.
                    if !ask_confirm("pass_opened") {
                        return finish_declined(program, total);
                    }
                    forced = true;
                }
            }
            None => {
                emit_event(&json!({
                    "type": "auto_phase",
                    "phase": "pass_control_skipped",
                    "ts": now_unix_ms(),
                }));
            }
        }
    }

    // Phase: strategies.
    emit_event(&json!({ "type": "auto_phase", "phase": "strategies", "ts": now_unix_ms() }));

    let mut working: Vec<String> = Vec::new();
    let mut failed: Vec<String> = Vec::new();
    let mut interrupted = false;

    'strategies: for (idx, candidate) in strategies.iter().enumerate() {
        if shutdown_requested() {
            interrupted = true;
            break;
        }
        let strategy = candidate.name.clone();
        let config_path = strategic_dir(program).join(&strategy);
        if !config_path.is_file() {
            emit_event(&json!({
                "type": "auto_strategy_skip",
                "strategy": strategy,
                "reason": "config not found",
                "ts": now_unix_ms(),
            }));
            continue;
        }

        emit_event(&json!({
            "type": "auto_strategy_start",
            "strategy": strategy,
            "index": idx,
            "total": total,
            "ts": now_unix_ms(),
        }));

        let raw = fs::read_to_string(&config_path)
            .with_context(|| format!("read {}", config_path.display()))?;
        let config_args = normalize_config_args(&raw);
        let filter = extract_proto_port_filter(&raw);
        let bin = program_bin(program);
        if !bin.is_file() {
            emit_event(&json!({
                "type": "auto_strategy_error",
                "strategy": strategy,
                "error": format!("binary not found: {}", bin.display()),
                "ts": now_unix_ms(),
            }));
            failed.push(strategy.clone());
            continue;
        }

        let mut crash_retries: u32 = 0;
        let mut network_recovered = false;
        let mut per_target: Vec<(String, u32)> = Vec::new(); // (target key, ok attempts)
        let mut confirm_times: Vec<u64> = Vec::new();
        let mut crashed_out: bool;

        'strategy: loop {
            ensure_work_dir()?;
            cleanup_all()?;
            let mut child = match spawn_program(program, &bin, config_path.parent().unwrap_or(Path::new("/")), opts.qnum, &config_args) {
                Ok(c) => c,
                Err(err) => {
                    emit_event(&json!({
                        "type": "auto_strategy_error",
                        "strategy": strategy,
                        "error": format!("spawn failed: {err}"),
                        "ts": now_unix_ms(),
                    }));
                    failed.push(strategy.clone());
                    continue 'strategies;
                }
            };
            // Keep the session file in sync so `stop` (or a SIGKILL recovery on
            // the next run) can kill exactly this engine, not the daemon's.
            write_session(&SessionState {
                program: program.to_string(),
                config_path: config_path.display().to_string(),
                config_name: strategy.clone(),
                pid: child.id(),
                qnum: opts.qnum,
                started_at_unix_ms: now_unix_ms(),
            })?;
            if let Err(err) = apply_nfqueue_rules(program, opts.qnum, &filter) {
                stop_child(&mut child);
                let _ = cleanup_rules_for_program(program);
                emit_event(&json!({
                    "type": "auto_strategy_error",
                    "strategy": strategy,
                    "error": format!("nfqueue rules failed: {err}"),
                    "ts": now_unix_ms(),
                }));
                failed.push(strategy.clone());
                continue 'strategies;
            }
            // Wait for the engine to stabilize.
            thread::sleep(Duration::from_millis(500));

            per_target.clear();
            confirm_times.clear();
            crashed_out = false;
            let mut all_unreachable = !blocked_keys.is_empty();

            for target in &targets {
                if shutdown_requested() {
                    break;
                }
                if !blocked_keys.contains(&target.key) {
                    continue;
                }
                // Engine died mid-probe: respawn and redo this strategy.
                if matches!(child.try_wait(), Ok(Some(_))) {
                    stop_child(&mut child);
                    let _ = cleanup_rules_for_program(program);
                    if crash_retries >= CRASH_RETRIES {
                        emit_event(&json!({
                            "type": "auto_strategy_error",
                            "strategy": strategy,
                            "error": "engine_crashed",
                            "ts": now_unix_ms(),
                        }));
                        crashed_out = true;
                        break;
                    }
                    crash_retries += 1;
                    continue 'strategy;
                }
                let ip = resolved_ips.get(&target.key).cloned().flatten();
                let first = probe_target(target, ip.as_ref(), opts.timeout_secs);
                let mut ok_count: u32 = if first.verdict == ProbeVerdict::Ok { 1 } else { 0 };
                emit_probe_event(&strategy, target, &first, 1, ok_count);
                if matches!(first.verdict, ProbeVerdict::Blocked(_)) {
                    all_unreachable = false;
                }
                if first.verdict == ProbeVerdict::Ok {
                    confirm_times.push(first.time_ms);
                    // Confirm on fresh connections: 2 more probes, stop at the
                    // first failure (zapretgui CONFIRM_ATTEMPTS).
                    for attempt in 2..=CONFIRM_ATTEMPTS {
                        if matches!(child.try_wait(), Ok(Some(_))) {
                            break;
                        }
                        let extra = probe_target(target, ip.as_ref(), opts.timeout_secs);
                        if extra.verdict == ProbeVerdict::Ok {
                            ok_count += 1;
                            confirm_times.push(extra.time_ms);
                        }
                        if matches!(extra.verdict, ProbeVerdict::Blocked(_)) {
                            all_unreachable = false;
                        }
                        emit_probe_event(&strategy, target, &extra, attempt, ok_count);
                        if extra.verdict != ProbeVerdict::Ok {
                            break;
                        }
                    }
                }
                per_target.push((target.key.clone(), ok_count));
            }

            stop_child(&mut child);
            let _ = cleanup_rules_for_program(program);
            let _ = fs::remove_file(SESSION_FILE);

            if crashed_out {
                failed.push(strategy.clone());
                continue 'strategies;
            }
            if shutdown_requested() {
                interrupted = true;
                break 'strategy;
            }

            // Network-loss guard: every probe unreachable = maybe WE went
            // offline, not the strategy failing.
            if all_unreachable && !control_alive() {
                let mut waited: u64 = 0;
                while waited < NETWORK_WAIT_SECONDS && !control_alive() {
                    thread::sleep(Duration::from_secs(3));
                    waited += 3;
                }
                if waited >= NETWORK_WAIT_SECONDS {
                    emit_event(&json!({
                        "type": "auto_fatal",
                        "stop_kind": "network_lost",
                        "message": "internet connection lost during scan",
                        "ts": now_unix_ms(),
                    }));
                    interrupted = true;
                    break 'strategy;
                }
                if !network_recovered {
                    // Network is back: re-run this strategy once (free retry).
                    network_recovered = true;
                    continue 'strategy;
                }
            }
            break 'strategy;
        }

        if crashed_out {
            continue;
        }
        if interrupted {
            break;
        }

        // Per-target results over baseline-blocked targets: 3/3 = confirmed
        // open, 1-2 = unstable, 0 = still blocked.
        let confirmed_open = per_target.iter().filter(|(_, ok)| *ok == CONFIRM_ATTEMPTS).count() as u32;
        let opened_any = per_target.iter().filter(|(_, ok)| *ok > 0).count() as u32;
        let baseline_blocked_total = blocked_keys.len() as u32;
        let opened_pct: f64 = if baseline_blocked_total > 0 {
            opened_any as f64 / baseline_blocked_total as f64 * 100.0
        } else {
            0.0
        };
        let score: Option<f64> = if baseline_blocked_total > 0 { Some(opened_pct) } else { None };
        let time_ms: Option<f64> = if confirm_times.is_empty() {
            None
        } else {
            Some(confirm_times.iter().sum::<u64>() as f64 / confirm_times.len() as f64)
        };

        let mut verdict: &str = if baseline_blocked_total == 0 {
            "no_baseline_block"
        } else if confirmed_open == baseline_blocked_total {
            "works"
        } else if opened_any > 0 {
            "unstable"
        } else {
            "failed"
        };

        // Post-success control: re-probe without bypass. If the targets open
        // now, the network changed under us — the result proves nothing.
        if verdict == "works" && !forced {
            let mut reopened = false;
            for target in targets.iter().filter(|t| blocked_keys.contains(&t.key)) {
                let ip = resolved_ips.get(&target.key).cloned().flatten();
                if probe_target(target, ip.as_ref(), opts.timeout_secs).verdict == ProbeVerdict::Ok {
                    reopened = true;
                    break;
                }
            }
            if reopened {
                verdict = "not_counted";
            }
        }

        emit_event(&json!({
            "type": "auto_strategy_result",
            "strategy": strategy,
            "verdict": verdict,
            "hosts_total": total_targets as u32,
            "baseline_blocked": baseline_blocked_total,
            "hosts_opened": confirmed_open,
            "hosts_unstable": per_target.iter().filter(|(_, ok)| *ok > 0 && *ok < CONFIRM_ATTEMPTS).count() as u32,
            "hosts_still_blocked": per_target.iter().filter(|(_, ok)| *ok == 0).count() as u32,
            "attempts_ok": per_target.iter().map(|(_, ok)| *ok).min().unwrap_or(0),
            "attempts_total": CONFIRM_ATTEMPTS,
            "opened_pct": opened_pct,
            "score": score,
            "time_ms": time_ms,
            "crash_retries": crash_retries,
            "ts": now_unix_ms(),
        }));

        match verdict {
            "works" => working.push(strategy.clone()),
            _ => failed.push(strategy.clone()),
        }
    }

    // Final summary.
    let mut finished = json!({
        "type": "auto_finished",
        "ok": true,
        "program": program,
        "total_strategies": total,
        "working": working,
        "failed": failed,
        "interrupted": interrupted,
        "forced": forced,
        "ts": now_unix_ms(),
    });
    if interrupted {
        finished["stop_kind"] = json!("interrupted");
    }
    emit_event(&finished);

    // Safety net: no engine, no rules, no session file left behind.
    let _ = cleanup_all();
    Ok(())
}

// ---------------------------------------------------------------------------
// Tests (pure logic only; device-facing paths need root + NFQUEUE)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_https_table() {
        assert_eq!(classify_https(0, 200, 5000), ProbeVerdict::Ok);
        assert_eq!(classify_https(0, 301, 0), ProbeVerdict::Ok);
        assert_eq!(classify_https(0, 400, 0), ProbeVerdict::Blocked("http_400"));
        assert_eq!(classify_https(0, 0, 0), ProbeVerdict::Blocked("reset"));
        assert_eq!(classify_https(60, 0, 0), ProbeVerdict::Blocked("cert_substitute"));
        assert_eq!(classify_https(51, 0, 0), ProbeVerdict::Blocked("cert_substitute"));
        assert_eq!(classify_https(91, 0, 0), ProbeVerdict::Blocked("cert_substitute"));
        assert_eq!(classify_https(35, 0, 0), ProbeVerdict::Blocked("tls_error"));
        assert_eq!(classify_https(52, 0, 0), ProbeVerdict::Blocked("reset"));
        assert_eq!(classify_https(56, 0, 0), ProbeVerdict::Blocked("reset"));
        assert_eq!(classify_https(28, 0, 0), ProbeVerdict::Blocked("timeout"));
        assert_eq!(classify_https(18, 0, 16_000), ProbeVerdict::Blocked("body_cut"));
        assert_eq!(classify_https(18, 0, 13_999), ProbeVerdict::Blocked("reset"));
        assert_eq!(classify_https(18, 0, 24_001), ProbeVerdict::Blocked("reset"));
        assert_eq!(classify_https(6, 0, 0), ProbeVerdict::Unreachable("resolve_failed"));
        assert_eq!(classify_https(7, 0, 0), ProbeVerdict::Unreachable("connect_failed"));
        assert_eq!(classify_https(2, 0, 0), ProbeVerdict::Blocked("reset"));
    }

    #[test]
    fn stun_request_layout() {
        let req = stun_request();
        assert_eq!(req.len(), 20);
        assert_eq!(&req[0..2], &[0x00, 0x01]);
        assert_eq!(&req[2..4], &[0x00, 0x00]);
        assert_eq!(&req[4..8], &0x2112A442u32.to_be_bytes());
    }

    #[test]
    fn bedrock_request_layout() {
        let req = bedrock_request();
        assert_eq!(req.len(), 33);
        assert_eq!(req[0], 0x01);
        let magic: [u8; 16] = [
            0x00, 0xff, 0xff, 0x00, 0xfe, 0xfe, 0xfe, 0xfe,
            0xfd, 0xfd, 0xfd, 0xfd, 0x12, 0x34, 0x56, 0x78,
        ];
        assert_eq!(&req[9..25], &magic);
    }

    #[test]
    fn a2s_request_layout() {
        let req = a2s_request();
        assert_eq!(&req[0..4], &[0xff, 0xff, 0xff, 0xff]);
        assert!(req.ends_with(b"Source Engine Query\x00"));
    }

    #[test]
    fn technique_set_nfqws2_desync_names() {
        let raw = "--lua-init=@/x.lua\n--lua-desync=fake:blob=tls:repeats=6\n--lua-desync=send syndata\n--lua-desync=PASS\n";
        let set = technique_set("nfqws2", raw);
        assert!(set.contains("fake"));
        assert!(set.contains("send"));
        assert!(set.contains("pass"));
        assert_eq!(set.len(), 3);
    }

    #[test]
    fn technique_set_nfqws_tokens() {
        let raw = "--filter-tcp=443 --dpi-desync=fake,multisplit --dpi-desync-fooling=ts\n--new --dpi-desync=disorder\n";
        let set = technique_set("nfqws", raw);
        assert!(set.contains("fake"));
        assert!(set.contains("multisplit"));
        assert!(set.contains("disorder"));
        assert!(!set.contains("ts"));
    }

    fn candidate(name: &str, tech: &[&str]) -> Candidate {
        Candidate {
            name: name.to_string(),
            techniques: tech.iter().map(|t| t.to_string()).collect(),
        }
    }

    #[test]
    fn ordering_confirmed_first_and_failed_last() {
        let now = 1_000_000u64;
        let candidates = vec![
            candidate("a.txt", &["fake"]),
            candidate("b.txt", &["split"]),
            candidate("c.txt", &["fake"]),
            candidate("pass.txt", &["pass"]),
        ];
        let confirmed = vec!["c.txt".to_string()];
        let mut failed = std::collections::HashMap::new();
        failed.insert("b.txt".to_string(), now - 100);
        let ordered = order_candidates(&candidates, &confirmed, &failed, now);
        let names: Vec<&str> = ordered.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names.first(), Some(&"c.txt"));
        assert_eq!(names.last(), Some(&"b.txt"));
        assert!(!names.contains(&"pass.txt"));
        assert_eq!(names.len(), 3);
    }

    #[test]
    fn ordering_interleaves_techniques() {
        let candidates = vec![
            candidate("f1.txt", &["fake"]),
            candidate("f2.txt", &["fake"]),
            candidate("s1.txt", &["split"]),
            candidate("f3.txt", &["fake"]),
        ];
        let ordered = order_candidates(&candidates, &[], &std::collections::HashMap::new(), 0);
        let names: Vec<&str> = ordered.iter().map(|c| c.name.as_str()).collect();
        // Round-robin: second strategy must be from another technique group.
        assert_eq!(names[1], "s1.txt");
        assert_eq!(names.len(), 4);
    }

    #[test]
    fn ordering_old_failures_are_fresh_again() {
        let now = FAILED_MEMORY_SECONDS + 1_000_000u64;
        let candidates = vec![candidate("a.txt", &["fake"]), candidate("b.txt", &["split"])];
        let mut failed = std::collections::HashMap::new();
        failed.insert("a.txt".to_string(), now - FAILED_MEMORY_SECONDS - 10);
        let ordered = order_candidates(&candidates, &[], &failed, now);
        // a.txt failed but >14 days ago: treated as fresh, so interleave order
        // (a first, then b) is preserved.
        assert_eq!(ordered[0].name, "a.txt");
    }

    #[test]
    fn batch_caps_by_mode() {
        let candidates: Vec<Candidate> = (0..100).map(|i| candidate(&format!("s{i}.txt"), &["fake"])).collect();
        let ordered = order_candidates(&candidates, &[], &std::collections::HashMap::new(), 0);
        assert_eq!(batch_for_mode(ordered.clone(), "quick").len(), 30);
        assert_eq!(batch_for_mode(ordered.clone(), "standard").len(), 80);
        assert_eq!(batch_for_mode(ordered, "full").len(), 100);
    }

    #[test]
    fn history_missing_or_broken_is_empty() {
        let h = load_history(Some("/nonexistent/strategy_history.json"), "nfqws2", "tcp_https", "default.txt");
        assert!(h.confirmed.is_empty());
        assert!(h.failed.is_empty());
    }
}
