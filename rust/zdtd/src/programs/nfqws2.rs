use anyhow::{Context, Result};
use super::common::*;
use log::info;
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};
use std::fs::OpenOptions;
use std::os::unix::process::CommandExt;

use crate::android::pkg_uid::{self, Mode, Sha256Tracker};
use crate::settings;
use crate::iptables::{iptables_v1, iptables_v2};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const NFQWS2_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws2";
const NFQWS2_BIN: &str = "/data/adb/modules/ZDT-D/bin/nfqws2";
// Strict preset compiler mirrored from magisk-zapret2. The daemon compiles the
// human-readable preset (config.txt) into the absolute argv nfqws2 consumes.
const STRATEGIC_DIR: &str = "/data/adb/modules/ZDT-D/strategic";
const COMMAND_BUILDER: &str = "/data/adb/modules/ZDT-D/strategic/scripts/command-builder.sh";
const SH_BIN: &str = "/system/bin/sh";
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

pub fn active_path() -> PathBuf { PathBuf::from(NFQWS2_ROOT).join("active.json") }
pub fn profile_root(profile: &str) -> PathBuf { PathBuf::from(NFQWS2_ROOT).join(profile) }

#[derive(Debug, Deserialize)]
struct ActiveJson {
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize)]
struct ProfileState {
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct PortJson {
    port: u16,
    iface_mobile: String,
    iface_wifi: String,
}

pub fn start_active_profiles() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(NFQWS2_ROOT)?;
    ensure_file(NFQWS2_BIN)?;

    let active_path = Path::new(NFQWS2_ROOT).join("active.json");
    let active = read_json::<ActiveJson>(&active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);

    for (profile_name, st) in active.profiles.iter() {
        if !st.enabled {
            continue;
        }
        start_profile(profile_name, &tracker)?;
    }
    Ok(())
}

fn start_profile(profile_name: &str, tracker: &Sha256Tracker) -> Result<()> {
    let profile_dir = Path::new(NFQWS2_ROOT).join(profile_name);
    ensure_dir(profile_dir.to_string_lossy().as_ref())?;

    let port_path = profile_dir.join("port.json");
    let port_cfg = read_json::<PortJson>(&port_path)
        .with_context(|| format!("read {}", port_path.display()))?;

    // lists
    let uid_dir = profile_dir.join("app/uid");
    let out_dir = profile_dir.join("app/out");
    fs::create_dir_all(&out_dir).with_context(|| format!("mkdir {}", out_dir.display()))?;

    let in_mobile = uid_dir.join("mobile_program");
    let in_wifi = uid_dir.join("wifi_program");
    let in_user = uid_dir.join("user_program");

    let out_mobile = out_dir.join("mobile_program");
    let out_wifi = out_dir.join("wifi_program");
    let out_user = out_dir.join("user_program");

    // Convert package list -> package=uid (sha256 gated)
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_mobile, &in_mobile)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_wifi, &in_wifi)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_user, &in_user)?;

    
// Pass config file path as argument (nfqws will parse the file itself).

    // Spawn nfqws: nfqws --uid=0:0 --qnum=<port> <config_path>
    let log_dir = profile_dir.join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;
    let log_path = log_dir.join("nfqws.log");
    
let resolved_mobile = count_valid_uid_pairs(&out_mobile)?;
    let resolved_wifi = count_valid_uid_pairs(&out_wifi)?;
    let resolved_user = count_valid_uid_pairs(&out_user)?;
    let resolved_total = resolved_mobile + resolved_wifi + resolved_user;
    let has_launch_marker = pkg_uid::file_has_launch_marker(&in_mobile).unwrap_or(false)
        || pkg_uid::file_has_launch_marker(&in_wifi).unwrap_or(false)
        || pkg_uid::file_has_launch_marker(&in_user).unwrap_or(false);
    if resolved_total == 0 && !has_launch_marker {
        log::warn!("nfqws2: no apps resolved for {} -> skip start/iptables", profile_dir.display());
        return Ok(());
    }
    if resolved_total == 0 && has_launch_marker {
        log::info!("nfqws2: launch marker present for {}, starting without routing app UIDs", profile_dir.display());
    }

    let config_path = profile_dir.join("config/config.txt");
    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;

    // Compile the human-readable preset into absolute argv via the strict
    // compiler (mirrors magisk-zapret2). Falls back to the legacy in-process
    // normalizer if the script is unavailable or fails, so an older module
    // layout still starts nfqws2.
    let argv_tmp = profile_dir.join("config/argv.txt");
    let config_args = match compile_preset_argv(port_cfg.port, &config_path, &argv_tmp) {
        Some(args) => args,
        None => {
            log::warn!(
                "nfqws2[{}]: command-builder.sh unavailable, using built-in normalizer",
                profile_name
            );
            normalize_config_args(&raw)
        }
    };

    let port_filter = crate::programs::nfqws_filters::extract_proto_port_filter(&raw);
    let port_filter_ref = if port_filter.is_empty() { None } else { Some(&port_filter) };


    crate::logging::user_info(&format!("zapret2[{profile_name}]: запуск"));
    spawn_nfqws(&profile_dir, port_cfg.port, &config_args, &log_path)?;

    // Apply iptables:
    crate::logging::user_info(&format!("zapret2[{profile_name}]: iptables"));
    // v2 (no iface) for USER
    iptables_v2::apply(port_cfg.port, Some(&out_user), port_filter_ref)?;
    // v1 full for mobile + wifi with iface mapping
    iptables_v1::apply("full", port_cfg.port, Some(port_cfg.iface_mobile.as_str()), Some(&out_mobile), port_filter_ref)?;
    iptables_v1::apply("full", port_cfg.port, Some(port_cfg.iface_wifi.as_str()), Some(&out_wifi), port_filter_ref)?;

    info!("nfqws profile started: {} port={}", profile_name, port_cfg.port);
    Ok(())
}


fn spawn_nfqws(workdir: &Path, port: u16, config_args: &[String], log_path: &Path) -> Result<()> {
    let q = format!("--qnum={}", port);

    // Open log file (append) for stdout/stderr so we can debug early exits.
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    // We intentionally pass `config_arg` as ONE argument, exactly like "$config" in shell.
    let mut cmd = Command::new(NFQWS2_BIN);
    cmd.current_dir(workdir);
    cmd.arg("--uid=0:0")
        .arg(q)
        .args(config_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    // Detach from controlling terminal/session so the process survives when the launcher exits.
    unsafe {
        cmd.pre_exec(|| {
            // If setsid fails, we still proceed; the process may die on terminal close, so we log it later.
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", NFQWS2_BIN))?;
    let pid = child.id();
    info!("spawned nfqws pid={} qnum={} log={}", pid, port, log_path.display());

    // Quick liveness check (best-effort)
    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        info!("nfqws pid={} is not running after spawn (check log {})", pid, log_path.display());
    }

    Ok(())
}

/// Compile the human-readable preset at `config_path` into one-argv-per-line
/// form using the strict compiler mirrored from magisk-zapret2
/// (`strategic/scripts/command-builder.sh zdt-compile`). Returns the argv
/// tokens, or `None` if the script is missing or fails so the caller can fall
/// back to the built-in normalizer.
///
/// The script emits `--qnum`/`--uid` itself; we strip those so `spawn_nfqws`
/// remains the single owner of the queue number and uid it already supplies.
fn compile_preset_argv(port: u16, config_path: &Path, argv_tmp: &Path) -> Option<Vec<String>> {
    if !Path::new(COMMAND_BUILDER).is_file() {
        return None;
    }
    let qnum = format!("{}", port);
    let status = Command::new(SH_BIN)
        .arg(COMMAND_BUILDER)
        .arg("zdt-compile")
        .arg(config_path)
        .arg(argv_tmp)
        .env("QNUM", &qnum)
        .env("ZAPRET_DIR", STRATEGIC_DIR)
        .env("PRESETS_DIR", format!("{}/strategicvar/nfqws2", STRATEGIC_DIR))
        .env("LISTS_DIR", format!("{}/list", STRATEGIC_DIR))
        .env("Z2_LUA_DIR", format!("{}/lua", STRATEGIC_DIR))
        .env("Z2_BIN_DIR", format!("{}/bin", STRATEGIC_DIR))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    let status = match status {
        Ok(s) => s,
        Err(e) => {
            log::warn!("nfqws2: command-builder.sh spawn failed: {e}");
            return None;
        }
    };
    if !status.success() {
        log::warn!("nfqws2: command-builder.sh exited {}", status);
        return None;
    }

    let text = match fs::read_to_string(argv_tmp) {
        Ok(t) => t,
        Err(e) => {
            log::warn!("nfqws2: read argv tmp failed: {e}");
            return None;
        }
    };
    let mut args = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        // Drop tokens the daemon owns (spawn_nfqws re-adds --qnum/--uid).
        if line.starts_with("--qnum=") || line.starts_with("--uid=") {
            continue;
        }
        args.push(line.to_string());
    }
    if args.is_empty() {
        return None;
    }
    Some(args)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::jsonfs::read_json(path)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}

fn ensure_file(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_file() {
        anyhow::bail!("file missing: {}", path.display());
    }
    Ok(())
}
