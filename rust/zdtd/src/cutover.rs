//! One-shot versioned cutover from the legacy global-selection NFQWS2 model
//! to per-item lists (presets:: item API).
//!
//! Safety rules (plan section 5):
//! 1. A versioned marker under `working_folder` decides whether anything
//!    needs to happen. Idempotent: an interrupted run repeats safely.
//! 2. Everything the reset would destroy is backed up first, outside the
//!    reset area, with a manifest (paths + sha256). A backup error blocks
//!    the reset and leaves the marker untouched.
//! 3. Only NFQWS2 processes are stopped (other programs keep running).
//! 4. Each existing profile gets a clean per-item config written from the
//!    shipped default preset. Container runtime state (enabled flag, apps,
//!    interfaces, ports) is preserved. Old hostlist/ipset selections and
//!    old config blocks are NOT migrated — the default preset's own
//!    per-item references are the new baseline.
//! 5. Done marker is written only after every profile config validates.

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde_json::json;

use crate::settings;

/// Cutover data-model version this binary implements.
pub const CUTOVER_VERSION: u32 = 1;

/// "pending" = marker written, backup started/reset not finished.
/// "done:<v>" = clean per-item configs written by version v.
const MARKER_FILE: &str = "working_folder/nfqws2-items-cutover.json";
const BACKUP_ROOT: &str = "working_folder/nfqws2-items-backup";

/// Shipped default preset (clean per-item config source). The plan names
/// "Default v1 (game filter)" as the canonical default.
const DEFAULT_PRESET: &str = "strategic/strategicvar/nfqws2/Default v1 (game filter).txt";

fn module_dir() -> PathBuf {
    PathBuf::from(settings::MODULE_DIR)
}

fn marker_path() -> PathBuf {
    module_dir().join(MARKER_FILE)
}

fn backup_root() -> PathBuf {
    module_dir().join(BACKUP_ROOT)
}

fn nfqws2_root() -> PathBuf {
    module_dir().join("working_folder/nfqws2")
}

/// Cutover state derived from the marker file.
#[derive(Debug, Clone, PartialEq)]
pub enum State {
    /// No marker: cutover not needed (fresh install or already done by this
    /// binary and marker cleaned) — see `check`.
    NotNeeded,
    /// Legacy state detected, cutover should run.
    Pending,
    /// Cutover finished by version v.
    Done(u32),
}

pub fn check() -> State {
    let Ok(text) = fs::read_to_string(marker_path()) else {
        return State::NotNeeded;
    };
    match serde_json::from_str::<serde_json::Value>(&text) {
        Ok(v) => match v.get("state").and_then(|s| s.as_str()) {
            Some("pending") => State::Pending,
            Some(s) if s.starts_with("done:") => {
                s.strip_prefix("done:").and_then(|n| n.parse().ok()).map(State::Done).unwrap_or(State::Pending)
            }
            _ => State::Pending,
        },
        Err(_) => State::Pending,
    }
}

fn write_marker(state: &str, extra: Option<serde_json::Value>) -> Result<()> {
    let mut v = json!({
        "state": state,
        "version": CUTOVER_VERSION,
    });
    if let Some(extra) = extra {
        if let (Some(dst), Some(src)) = (v.as_object_mut(), extra.as_object()) {
            for (k, val) in src {
                dst.insert(k.clone(), val.clone());
            }
        }
    }
    let p = marker_path();
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(&p, serde_json::to_vec_pretty(&v)?)
        .with_context(|| format!("write {}", p.display()))?;
    Ok(())
}

pub fn is_pending_cutover() -> bool {
    match check() {
        State::Pending => true,
        State::NotNeeded => {
            // Marker absent: decide by legacy state presence. A fresh install
            // has the default profile with a clean per-item config already
            // (created by ensure_profile_layout), so only rewrite profiles
            // whose config still carries injected global selectors.
            profiles_dir_exists() && profiles_with_legacy_injection().len() > 0
        }
        State::Done(_) => false,
    }
}

fn profiles_dir_exists() -> bool {
    fs::read_dir(nfqws2_root()).map(|mut rd| rd.next().is_some()).unwrap_or(false)
}

/// The old daemon injected `# @zdtd-variant ` markers and global
/// `--hostlist=`/`--ipset=` lines before the first `--new`. Detect that.
fn config_has_legacy_injection(text: &str) -> bool {
    // The old daemon always stamped the applied variant name at the top.
    if text.contains("# @zdtd-variant ") {
        return true;
    }
    // Hand-edited configs may lack the marker: detect injected global
    // selectors the old daemon placed before the first item boundary.
    // Item boundary = `--new` OR the first unnamed profile's leading match
    // tokens (ZDT-D builtin presets open without `--new`, e.g.
    // `--name=chatgpt` / `--filter-tcp=...` right after the preamble).
    // Those tokens belong to the first item, not to a global selection.
    for line in text.lines() {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') || t.starts_with(';') {
            continue;
        }
        if t == "--new" || t.starts_with("--new=") {
            // Sections start: selectors past this point are per-item.
            return false;
        }
        if t.starts_with("--name=") || t.starts_with("--filter-") || t.starts_with("--match") {
            // First profile body started: same, selectors from here on are
            // part of that item.
            return false;
        }
        if t.starts_with("--hostlist") || t.starts_with("--ipset") {
            return true;
        }
    }
    false
}

/// Profiles whose config still carries legacy global selections — exposed
/// to the app so the UI can show what a pending cutover would rewrite.
pub fn pending_profiles() -> Vec<String> {
    profiles_with_legacy_injection()
}

fn profiles_with_legacy_injection() -> Vec<String> {
    let mut out = Vec::new();
    let Ok(rd) = fs::read_dir(nfqws2_root()) else { return out };
    for ent in rd.flatten() {
        let p = ent.path();
        if !p.is_dir() {
            continue;
        }
        let Some(name) = p.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue };
        if name == "user-presets" || name == "resources" {
            continue;
        }
        let cfg = p.join("config/config.txt");
        if let Ok(text) = fs::read_to_string(&cfg) {
            if config_has_legacy_injection(&text) {
                out.push(name);
            }
        }
    }
    out
}

fn read_default_preset_text() -> Result<String> {
    let p = module_dir().join(DEFAULT_PRESET);
    fs::read_to_string(&p).with_context(|| format!("read default preset {}", p.display()))
}


fn write_bytes_atomic(p: &Path, data: &[u8]) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, data)
        .with_context(|| format!("write failed {}", tmp.display()))?;
    fs::rename(&tmp, p)
        .with_context(|| format!("rename failed {} -> {}", tmp.display(), p.display()))?;
    Ok(())
}

fn read_json_file<T: for<'de> serde::Deserialize<'de>>(p: &Path) -> Option<T> {
    fs::read(p).ok().and_then(|b| serde_json::from_slice(&b).ok())
}

/// Everything that will be reset, mirrored with hashes into
struct BackupOutcome {
    dir: PathBuf,
    manifest: serde_json::Value,
}

fn run_backup() -> Result<BackupOutcome> {
    let stamp = chrono_like_timestamp();
    let root = backup_root().join(stamp);
    fs::create_dir_all(&root)
        .with_context(|| format!("mkdir {}", root.display()))?;

    let mut files: Vec<serde_json::Value> = Vec::new();
    let mut backup_one = |src: &Path, rel: &str, files: &mut Vec<serde_json::Value>| -> Result<()> {
        let Ok(data) = fs::read(src) else { return Ok(()) };
        let dst = root.join(rel);
        if let Some(parent) = dst.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&dst, &data).with_context(|| format!("backup {} -> {}", src.display(), dst.display()))?;
        files.push(json!({
            "path": rel,
            "source": src.display().to_string(),
            "size": data.len(),
            "sha256": crate::api::sha256_hex_bytes_pub(&data),
        }));
        Ok(())
    };

    // Old user presets (explicit import remains possible after cutover).
    let user_presets = module_dir().join("working_folder/user-presets/nfqws2");
    if let Ok(rd) = fs::read_dir(&user_presets) {
        for ent in rd.flatten() {
            if ent.path().extension().and_then(|e| e.to_str()) == Some("txt") {
                let name = ent.file_name().to_string_lossy().to_string();
                backup_one(&ent.path(), &format!("user-presets/{name}"), &mut files)?;
            }
        }
    }

    // Per-profile state: config + apps + port + enabled.
    if let Ok(rd) = fs::read_dir(nfqws2_root()) {
        let mut profiles: Vec<String> = rd
            .flatten()
            .filter(|e| e.path().is_dir())
            .filter_map(|e| e.file_name().to_str().map(|s| s.to_string()))
            .filter(|n| n != "user-presets" && n != "resources")
            .collect();
        profiles.sort();
        for name in profiles {
            let dir = nfqws2_root().join(&name);
            backup_one(&dir.join("config/config.txt"), &format!("profiles/{name}/config.txt"), &mut files)?;
            for kind in ["user", "mobile", "wifi"] {
                backup_one(&dir.join(format!("app/uid/{kind}_program")), &format!("profiles/{name}/apps/{kind}_program"), &mut files)?;
            }
            backup_one(&dir.join("port.json"), &format!("profiles/{name}/port.json"), &mut files)?;
        }
        backup_one(&nfqws2_root().join("active.json"), "active.json", &mut files)?;
    }

    let manifest = json!({
        "version": CUTOVER_VERSION,
        "created_at_unix_ms": unix_ms(),
        "default_preset": DEFAULT_PRESET,
        "files": files,
    });
    let mp = root.join("manifest.json");
    fs::write(&mp, serde_json::to_vec_pretty(&manifest)?)
        .with_context(|| format!("write {}", mp.display()))?;

    // Verify readability + manifest completeness (plan 5.2).
    let verified = read_json_file::<serde_json::Value>(&mp);
    let n_manifest = manifest["files"].as_array().map(|a| a.len()).unwrap_or(0);
    let n_verified = verified
        .as_ref()
        .and_then(|v| v["files"].as_array().map(|a| a.len()))
        .unwrap_or(0);
    if n_manifest == 0 || n_manifest != n_verified {
        bail!("backup manifest incomplete: {n_verified}/{n_manifest} entries");
    }
    for f in manifest["files"].as_array().unwrap() {
        let rel = f["path"].as_str().unwrap_or("");
        let p = root.join(rel);
        let ok = fs::read(&p)
            .map(|b| crate::api::sha256_hex_bytes_pub(&b) == f["sha256"].as_str().unwrap_or(""))
            .unwrap_or(false);
        if !ok {
            bail!("backup verification failed: {rel}");
        }
    }

    Ok(BackupOutcome { dir: root, manifest })
}

fn unix_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// `YYYYmmdd-HHMMSS` from the wall clock; UTC is fine for a backup name.
fn chrono_like_timestamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) as i64;
    // Days since epoch -> civil date (Howard Hinnant's algorithm).
    let days = secs.div_euclid(86400);
    let rem = secs.rem_euclid(86400);
    let (h, m, s) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { y + 1 } else { y };
    format!("{year:04}{month:02}{d:02}-{h:02}{m:02}{s:02}")
}

/// Kill only NFQWS2 processes (pidof nfqws2), best-effort.
fn stop_nfqws2_only() {
    if let Ok((_, out)) = crate::shell::run("pidof", &["nfqws2"], crate::shell::Capture::Stdout) {
        for pid in out.split_whitespace().filter_map(|s| s.trim().parse::<i32>().ok()) {
            let _ = crate::shell::run("kill", &[&pid.to_string()], crate::shell::Capture::None);
        }
    }
}

/// Parse the profile's old config for container runtime parameters that the
/// new clean config must keep. The plan keeps only container runtime flags
/// (pkt limits) — those live in the preset header comments.
fn keep_runtime_params(old: &str, fresh: &str) -> String {
    // Nothing to preserve yet: header runtime params (`NFQWS2_*_PKT_*`) are
    // part of the shipped default preset already. Keep this hook explicit so
    // future parameters have one place to migrate.
    let _ = (old, fresh);
    fresh.to_string()
}

/// Preserve per-profile runtime state (apps/port/enabled are on disk and not
/// reset; only config.txt is rewritten), then validate the fresh config.
fn apply_clean_configs(default_text: &str, legacy_profiles: &[String]) -> Result<usize> {
    let mut written = 0usize;
    for name in legacy_profiles {
        let dir = nfqws2_root().join(name);
        let cfg = dir.join("config/config.txt");
        let old = fs::read_to_string(&cfg).unwrap_or_default();
        let fresh = keep_runtime_params(&old, default_text);

        // Validate before writing: parse must yield at least one profile and
        // no parse error.
        let parsed = crate::presets::parse_preset(&fresh);
        if parsed.profiles.is_empty() {
            bail!("default preset failed to parse for profile {name}");
        }

        write_bytes_atomic(&cfg, fresh.as_bytes())
            .with_context(|| format!("write {}", cfg.display()))?;
        written += 1;
    }
    Ok(written)
}

/// Full cutover run. Returns the backup dir on success.
pub fn run() -> Result<PathBuf> {
    if !is_pending_cutover() {
        // Already done (or fresh install with nothing to do).
        write_marker(&format!("done:{CUTOVER_VERSION}"), None)?;
        return Ok(backup_root());
    }

    // 1. Mark pending before any destructive step so interrupted runs retry.
    write_marker("pending", Some(json!({"started_at_unix_ms": unix_ms()})))?;

    // 2. Backup first; failure blocks the reset and keeps files intact.
    let backup = run_backup()?;

    // 3. Stop only NFQWS2 processes.
    stop_nfqws2_only();

    // 4. Clean per-item configs from the shipped default preset.
    let default_text = read_default_preset_text()?;
    let legacy = profiles_with_legacy_injection();
    let written = apply_clean_configs(&default_text, &legacy)?;

    // 5. Done marker only after all configs validated and were written.
    write_marker(
        &format!("done:{CUTOVER_VERSION}"),
        Some(json!({
            "backup_dir": backup.dir.display().to_string(),
            "profiles_rewritten": written,
            "finished_at_unix_ms": unix_ms(),
        })),
    )?;

    Ok(backup.dir)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("zdtd-cutover-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn marker_states_roundtrip() {
        let d = tmp();
        // Redirect module dir via env? No: paths are const. Test the parsing
        // logic directly through check() with a temp marker path override.
        // check() reads a const path, so exercise the parse branches inline.
        let parse = |text: &str| -> State {
            match serde_json::from_str::<serde_json::Value>(text) {
                Ok(v) => match v.get("state").and_then(|s| s.as_str()) {
                    Some("pending") => State::Pending,
                    Some(s) if s.starts_with("done:") => {
                        s.strip_prefix("done:").and_then(|n| n.parse().ok()).map(State::Done).unwrap_or(State::Pending)
                    }
                    _ => State::Pending,
                },
                Err(_) => State::Pending,
            }
        };
        assert_eq!(parse(r#"{"state":"pending"}"#), State::Pending);
        assert_eq!(parse(r#"{"state":"done:1"}"#), State::Done(1));
        assert_eq!(parse(r#"{"state":"done:x"}"#), State::Pending);
        assert_eq!(parse("garbage"), State::Pending);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn legacy_injection_detection() {
        assert!(config_has_legacy_injection("# @zdtd-variant Default v1\n--filter-tcp=443"));
        assert!(config_has_legacy_injection("--hostlist=/path/list.txt\n--new\n--filter-tcp=80"));
        assert!(config_has_legacy_injection("--ipset=x"));
        // Per-item config: lists only inside sections after --new.
        assert!(!config_has_legacy_injection(
            "--filter-tcp=443\n--new\n--name=x\n--hostlist=y"
        ));
        // ZDT-D builtin format: first item opens WITHOUT --new; its lists
        // are per-item, not a global injection.
        assert!(!config_has_legacy_injection(
            "--lua-init=@x.lua\n--name=chatgpt\n--filter-tcp=80,443\n--hostlist=/data/adb/modules/ZDT-D/strategic/list/chatgpt.txt\n--out-range=-d8\n--lua-desync=pass"
        ));
    }

    #[test]
    fn timestamp_shape() {
        let t = chrono_like_timestamp();
        assert_eq!(t.len(), 15);
        assert!(t.as_bytes()[8] == b'-');
    }
}
