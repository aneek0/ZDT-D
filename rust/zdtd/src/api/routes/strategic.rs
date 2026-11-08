use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::json;
use std::{collections::HashMap, fs, net::TcpStream, path::{Path, PathBuf}};

use crate::settings;
use super::super::http::write_json;
use super::super::support::fs::{
    chmod_best_effort, read_text, sha256_hex_bytes, write_bytes_atomic, write_text_atomic,
};
use super::super::support::multipart::parse_multipart_file;
use super::super::support::paths::{decode_url_component, ensure_safe_filename, ensure_safe_segment};
use super::super::ContentReq;
use super::programs::shared::profile_root;

pub(in crate::api) fn strategic_root() -> PathBuf {
    Path::new(settings::MODULE_DIR).join("strategic")
}

pub(in crate::api) fn strategicvar_root() -> PathBuf {
    strategic_root().join("strategicvar")
}

fn is_allowed_strategicvar_program(id: &str) -> bool {
    matches!(id, "nfqws2" | "byedpi")
}

pub(in crate::api) fn list_txt_files_only(dir: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    if !dir.exists() {
        // Don't create by default: variants are shipped with the module.
        return Ok(out);
    }
    for ent in fs::read_dir(dir)
        .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", dir.display()))?
    {
        let ent = ent?;
        let p = ent.path();
        if p.is_file() {
            let name = ent.file_name();
            let name = name.to_string_lossy();
            if name.ends_with(".txt") {
                out.push(name.to_string());
            }
        }
    }
    out.sort();
    Ok(out)
}




fn list_files_only(dir: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    if !dir.exists() {
        fs::create_dir_all(dir).ok();
    }
    for ent in fs::read_dir(dir)
        .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", dir.display()))? {
        let ent = ent?;
        let p = ent.path();
        if p.is_file() {
            if let Some(name) = ent.file_name().to_str() {
                out.push(name.to_string());
            }
        }
    }
    out.sort();
    Ok(out)
}

pub(in crate::api) fn handle_strategic(stream: TcpStream, method: &str, path: &str, headers: &HashMap<String, String>, body: &[u8]) -> Result<()> {
    // Routes:
    //   GET    /api/strategic/{list|lua|bin}
    //   GET    /api/strategic/{list|lua}/{name}
    //   PUT    /api/strategic/{list|lua}/{name}   (JSON {content})
    //   DELETE /api/strategic/{list|lua|bin}/{name}
    //   POST   /api/strategic/{list|lua|bin}/upload   (multipart/form-data)
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let res = (|| -> Result<serde_json::Value> {
        let kind = seg.get(2).copied().ok_or_else(|| anyhow::anyhow!("bad path"))?;
        if !matches!(kind, "list" | "lua" | "bin") {
            anyhow::bail!("unknown strategic dir");
        }

        let base = strategic_root().join(kind);
        // Ensure base exists (best effort).
        fs::create_dir_all(&base).ok();

        const STRATEGIC_TEXT_LIMIT: u64 = 200 * 1024; // 200KB safeguard for app/UI

        fn file_len(p: &Path) -> Result<u64> {
            Ok(fs::metadata(p)?.len())
        }

        match (method, seg.as_slice()) {
            ("GET", ["api", "strategic", _]) => {
                let files = list_files_only(&base)?;
                // Provide sizes for UI so it can avoid loading huge files.
                let mut sizes = serde_json::Map::new();
                for name in &files {
                    let p = base.join(name);
                    if let Ok(len) = file_len(&p) {
                        sizes.insert(name.clone(), json!(len));
                    }
                }
                Ok(json!({"ok": true, "files": files, "sizes": sizes, "limit": STRATEGIC_TEXT_LIMIT}))
            }
            ("POST", ["api", "strategic", _, "upload"]) => {
                // Upload a file. International display names are allowed, but
                // path separators/control characters are still rejected by the
                // multipart parser. Text assets are converted to canonical UTF-8
                // so later editing/saving never depends on the source encoding.
                let f = parse_multipart_file(headers, body)?;
                let dst = base.join(&f.filename);
                if dst.parent() != Some(base.as_path()) {
                    anyhow::bail!("invalid destination");
                }
                if kind == "bin" {
                    write_bytes_atomic(&dst, &f.data)?;
                    chmod_best_effort(&dst, 0o755);
                } else {
                    let content = crate::common::fs::external_text::decode_external_text(&f.data)
                        .with_context(|| format!("decode imported strategic file {}", f.filename))?;
                    write_text_atomic(&dst, &content)?;
                    chmod_best_effort(&dst, 0o644);
                }
                Ok(json!({"ok": true, "filename": f.filename}))
            }
            ("GET", ["api", "strategic", "bin", _name]) => {
                anyhow::bail!("bin files are not text-readable via API");
            }
            ("GET", ["api", "strategic", _, name]) => {
                if kind == "bin" {
                    anyhow::bail!("bin files are not text-readable via API");
                }
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let p = base.join(&name);
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                // Safety: don't read huge files into memory.
                let len = file_len(&p).unwrap_or(0);
                if len > STRATEGIC_TEXT_LIMIT {
                    return Ok(json!({"ok": false, "error": "too_large", "size": len, "limit": STRATEGIC_TEXT_LIMIT}));
                }
                let content = read_text(&p)?;
                Ok(json!({"ok": true, "content": content}))
            }
            ("PUT", ["api", "strategic", _, name]) => {
                if kind == "bin" {
                    anyhow::bail!("bin files cannot be edited as text");
                }
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let content = crate::common::fs::external_text::normalize_text(&req.content)?;
                let content_len = content.as_bytes().len() as u64;
                if content_len > STRATEGIC_TEXT_LIMIT {
                    return Ok(json!({"ok": false, "error": "too_large", "size": content_len, "limit": STRATEGIC_TEXT_LIMIT}));
                }
                let p = base.join(&name);
                write_text_atomic(&p, &content)?;
                chmod_best_effort(&p, 0o644);
                Ok(json!({"ok": true}))
            }
            ("DELETE", ["api", "strategic", _, name]) => {
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let p = base.join(&name);
                if p.exists() {
                    fs::remove_file(&p)
                        .map_err(|e| anyhow::anyhow!("remove failed {}: {e}", p.display()))?;
                }
                Ok(json!({"ok": true}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();

    match res {
        Ok(v) => write_json(stream, 200, v),
        Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
    }
}

#[derive(Debug, Deserialize)]
struct ApplyStrategicVarReq {
    program: String,
    profile: String,
    file: String,
    #[serde(default)]
    hostlists: Vec<String>,
    #[serde(default)]
    exclude_hostlists: Vec<String>,
    #[serde(default)]
    ipsets: Vec<String>,
    #[serde(default)]
    exclude_ipsets: Vec<String>,
}

/// Holds the user-selected domain (hostlist) and IP (ipset) selection that the
/// daemon injects into a strategy file, replacing any hardcoded selection args.
/// `variant_name`, when set, is the built-in preset the user applied; it is
/// persisted as a comment marker in the config so the UI can keep showing the
/// preset name even after the user edits the hostlist selection (which changes
/// the file hash and would otherwise make the preset unrecognizable).
#[derive(Clone)]
struct Selection {
    hostlists: Vec<String>,
    exclude_hostlists: Vec<String>,
    ipsets: Vec<String>,
    exclude_ipsets: Vec<String>,
    variant_name: Option<String>,
}

/// Marker written at the very top of a config so the applied preset name
/// survives hostlist edits. Must be a comment that both nfqws2 and the daemon
/// ignore during argument parsing.
const VARIANT_MARKER: &str = "# @zdtd-variant ";

/// Reads a previously written `# @zdtd-variant <name>` marker from config text.
fn read_variant_marker(text: &str) -> Option<String> {
    for line in text.lines() {
        let trimmed = line.trim_start();
        if let Some(rest) = trimmed.strip_prefix(VARIANT_MARKER) {
            let name = rest.trim();
            if !name.is_empty() {
                return Some(name.to_string());
            }
        }
    }
    None
}

fn apply_selection_to_config(data: &[u8], sel: &Selection) -> Vec<u8> {
    let text = String::from_utf8_lossy(data);
    let module_list = "/data/adb/modules/ZDT-D/strategic/list/";

    // Determine the applied preset name to persist: prefer an explicit value
    // from the request, otherwise keep a previously written marker so that
    // hostlist-only edits don't erase the preset association.
    let variant_name = match &sel.variant_name {
        Some(v) if !v.is_empty() => Some(v.clone()),
        _ => read_variant_marker(&text),
    };
    let mut injected_args: Vec<String> = Vec::new();
    for hl in &sel.hostlists {
        injected_args.push(format!("--hostlist={module_list}{hl}"));
    }
    for ex in &sel.exclude_hostlists {
        injected_args.push(format!("--hostlist-exclude={module_list}{ex}"));
    }
    for ip in &sel.ipsets {
        injected_args.push(format!("--ipset={module_list}{ip}"));
    }
    for ex in &sel.exclude_ipsets {
        injected_args.push(format!("--ipset-exclude={module_list}{ex}"));
    }

    // ---- line-oriented pass -------------------------------------------------
    // The config must stay in the human-readable nfqws2 preset format the
    // strict compiler (command-builder.sh) expects: one option per line,
    // comment lines (`# ...`) intact. Flattening the whole file through
    // split_whitespace() exploded every comment into one word per line and the
    // profile refused to start after any hostlist edit.
    let mut out_lines: Vec<String> = Vec::new();

    // Pass 1: collect the selection tokens that already live inside preset
    // `--new` sections so we never inject a duplicate (the preset's own
    // block already covers that list). Only section-internal tokens count:
    // global tokens are the daemon-owned selection and are replaced below.
    let mut builtin_values: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut in_section = false;
    for line in text.lines() {
        let line = line.trim();
        if line == "--new" || line.starts_with("--new=") {
            in_section = true;
            continue;
        }
        if !in_section {
            continue;
        }
        for tok in line.split_whitespace() {
            if tok.starts_with("--hostlist=")
                || tok.starts_with("--hostlist-exclude=")
                || tok.starts_with("--ipset=")
                || tok.starts_with("--ipset-exclude=")
            {
                builtin_values.insert(tok.to_string());
            }
        }
    }
    let injected_args: Vec<String> = injected_args
        .into_iter()
        .filter(|a| !builtin_values.contains(a))
        .collect();

    // Pass 2: rebuild the file. Injected selection goes to the top of the
    // GLOBAL section (before the first `--new`); existing daemon-owned
    // selection tokens in the global section are dropped so re-apply never
    // duplicates. Comment lines and section lines are copied verbatim.
    let is_user_token = |t: &str| {
        t.starts_with("--hostlist=")
            || t.starts_with("--hostlist-exclude=")
            || t.starts_with("--ipset=")
            || t.starts_with("--ipset-exclude=")
    };
    let mut seen_first_new = false;
    let mut selection_emitted = false;
    for raw_line in text.lines() {
        let line = raw_line.trim_end_matches('\r');
        let trimmed = line.trim();

        // Keep the variant marker line out of the body: it is re-emitted at
        // the very top below.
        if trimmed.starts_with(VARIANT_MARKER) {
            continue;
        }

        if trimmed == "--new" || trimmed.starts_with("--new=") {
            if !selection_emitted {
                // First section boundary: the global section is complete, so
                // the user selection must have been emitted before it.
                out_lines.extend(injected_args.iter().cloned());
                selection_emitted = true;
            }
            seen_first_new = true;
            out_lines.push(line.to_string());
            continue;
        }

        // Comment lines and blank lines are preserved verbatim, everywhere.
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            out_lines.push(line.to_string());
            continue;
        }

        // Non-comment global line before the first section: drop old
        // selection tokens, keep everything else.
        if !seen_first_new && is_user_token(trimmed) {
            continue;
        }

        out_lines.push(line.to_string());
    }
    // No `--new` at all: the whole file is the global section.
    if !selection_emitted {
        out_lines.extend(injected_args.iter().cloned());
    }

    let mut result = String::new();
    if let Some(name) = &variant_name {
        result.push_str(VARIANT_MARKER);
        result.push_str(name);
        result.push('\n');
    }
    for l in &out_lines {
        result.push_str(l);
        result.push('\n');
    }
    result.into_bytes()
}

/// Reads the current profile config and extracts the domain (hostlist) and IP
/// (ipset) selection the user chose. The selection the daemon injects lives in
/// the GLOBAL section (before the first `--new`); per-section `--hostlist*`/
/// `--ipset*` entries inside `--new` blocks are part of the preset strategy
/// itself (e.g. game-filter built-in ipset filters) and must NOT be treated as
/// the user's selection, otherwise a re-apply would wrongly strip them.
fn extract_selection_from_config(cfg: &Path) -> Selection {
    let module_list = "/data/adb/modules/ZDT-D/strategic/list/";
    let mut sel = Selection {
        hostlists: Vec::new(),
        exclude_hostlists: Vec::new(),
        ipsets: Vec::new(),
        exclude_ipsets: Vec::new(),
        variant_name: None,
    };
    let Ok(data) = fs::read(cfg) else {
        return sel;
    };
    let text = String::from_utf8_lossy(&data);
    // Preserve the previously written preset marker so a re-apply with no
    // explicit selection keeps the chip on the built-in name.
    sel.variant_name = read_variant_marker(&text);
    for token in text.split_whitespace() {
        let t = token.trim();
        // Stop at the first profile section: everything after belongs to the
        // preset strategy, not the user's global selection.
        if t == "--new" {
            break;
        }
        if let Some(rest) = t.strip_prefix("--hostlist=") {
            if let Some(name) = rest.strip_prefix(module_list) {
                if !name.is_empty() {
                    sel.hostlists.push(name.to_string());
                }
            }
        } else if let Some(rest) = t.strip_prefix("--hostlist-exclude=") {
            if let Some(name) = rest.strip_prefix(module_list) {
                if !name.is_empty() {
                    sel.exclude_hostlists.push(name.to_string());
                }
            }
        } else if let Some(rest) = t.strip_prefix("--ipset=") {
            if let Some(name) = rest.strip_prefix(module_list) {
                if !name.is_empty() {
                    sel.ipsets.push(name.to_string());
                }
            }
        } else if let Some(rest) = t.strip_prefix("--ipset-exclude=") {
            if let Some(name) = rest.strip_prefix(module_list) {
                if !name.is_empty() {
                    sel.exclude_ipsets.push(name.to_string());
                }
            }
        }
    }
    sel
}

/// Routes:
///   GET  /api/strategicvar/{program}
///   GET  /api/strategicvar/human/{program}/{file}   (human-readable preset form)
///   POST /api/strategicvar/apply        (JSON {program, profile, file[, hostlists, exclude_hostlists, ipsets, exclude_ipsets]})
///   POST /api/strategicvar/hostlists    (JSON {program, profile, hostlists, exclude_hostlists, ipsets, exclude_ipsets})
pub(in crate::api) fn handle_strategicvar(stream: TcpStream, method: &str, path: &str, body: &[u8]) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let res = (|| -> Result<serde_json::Value> {
        match (method, seg.as_slice()) {
            ("GET", ["api", "strategicvar", program]) => {
                ensure_safe_segment(program, "program")?;
                if !is_allowed_strategicvar_program(program) {
                    anyhow::bail!("unknown program");
                }
                let base = strategicvar_root().join(program);
                let files = list_txt_files_only(&base)?;

                // Provide optional metadata (sha256) so the app can label configs
                // as built-in strategy vs user config, without downloading file contents.
                let mut meta = Vec::new();
                for name in &files {
                    let p = base.join(name);
                    if let Ok(data) = fs::read(&p) {
                        meta.push(json!({
                            "name": name,
                            "sha256": sha256_hex_bytes(&data),
                        }));
                    }
                }

                Ok(json!({"ok": true, "files": files, "meta": meta}))
            }
            ("GET", ["api", "strategicvar", "human", program, file]) => {
                ensure_safe_segment(program, "program")?;
                ensure_safe_filename(file)?;
                if !is_allowed_strategicvar_program(program) {
                    anyhow::bail!("unknown program");
                }
                if !file.ends_with(".txt") {
                    anyhow::bail!("strategy file must be .txt");
                }
                let src = strategicvar_root().join(program).join(file);
                if !src.is_file() {
                    anyhow::bail!("strategy not found");
                }
                let raw = fs::read(&src)
                    .map_err(|e| anyhow::anyhow!("read failed {}: {e}", src.display()))?;
                let human = crate::strategic_translator::nfqws2_to_human(&String::from_utf8_lossy(&raw));
                Ok(json!({"ok": true, "file": file, "human": human}))
            }
            ("POST", ["api", "strategicvar", "apply"]) => {
                let req: ApplyStrategicVarReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;

                ensure_safe_segment(&req.program, "program")?;
                ensure_safe_segment(&req.profile, "profile")?;
                ensure_safe_filename(&req.file)?;
                if !req.file.ends_with(".txt") {
                    anyhow::bail!("strategy file must be .txt");
                }
                if !is_allowed_strategicvar_program(&req.program) {
                    anyhow::bail!("unknown program");
                }

                // Ensure profile exists.
                let prof_root = profile_root(&req.program, &req.profile);
                if !prof_root.exists() {
                    anyhow::bail!("profile not found");
                }

                let src = strategicvar_root().join(&req.program).join(&req.file);
                if !src.is_file() {
                    anyhow::bail!("strategy not found");
                }
                let data = fs::read(&src)
                    .map_err(|e| anyhow::anyhow!("read failed {}: {e}", src.display()))?;

                // byedpi keeps the legacy global selection model: when the
                // client did not supply a selection, reuse the one already
                // stored in the config. nfqws2 lists are per-item now
                // (presets:: item API) and must never be globally injected.
                let data = if req.program == "nfqws2" {
                    data
                } else {
                    let sel = if !req.hostlists.is_empty()
                        || !req.exclude_hostlists.is_empty()
                        || !req.ipsets.is_empty()
                        || !req.exclude_ipsets.is_empty()
                    {
                        Selection {
                            hostlists: req.hostlists.clone(),
                            exclude_hostlists: req.exclude_hostlists.clone(),
                            ipsets: req.ipsets.clone(),
                            exclude_ipsets: req.exclude_ipsets.clone(),
                            variant_name: Some(req.file.clone()),
                        }
                    } else {
                        let cfg = prof_root.join("config/config.txt");
                        extract_selection_from_config(&cfg)
                    };

                    if sel.hostlists.iter().chain(&sel.ipsets).next().is_some()
                        || sel.exclude_hostlists.iter().chain(&sel.exclude_ipsets).next().is_some()
                    {
                        apply_selection_to_config(&data, &sel)
                    } else {
                        data
                    }
                };

                let dst = prof_root.join("config/config.txt");
                // The written config is the human-readable preset verbatim
                // (byedpi may carry a global hostlist/ipset selection injected
                // above; nfqws2 is injected never — its lists are per-item).
                // The strict compiler (strategic/scripts/command-builder.sh,
                // invoked by the daemon at profile start) turns this into the
                // absolute argv the engine consumes, mirroring magisk-zapret2.
                write_bytes_atomic(&dst, &data)?;
                Ok(json!({"ok": true}))
            }
            ("POST", ["api", "strategicvar", "hostlists"]) => {
                #[derive(Debug, Deserialize)]
                struct HostlistsOnlyReq {
                    program: String,
                    profile: String,
                    #[serde(default)]
                    hostlists: Vec<String>,
                    #[serde(default)]
                    exclude_hostlists: Vec<String>,
                    #[serde(default)]
                    ipsets: Vec<String>,
                    #[serde(default)]
                    exclude_ipsets: Vec<String>,
                }
                let req: HostlistsOnlyReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;

                ensure_safe_segment(&req.program, "program")?;
                ensure_safe_segment(&req.profile, "profile")?;
                if !is_allowed_strategicvar_program(&req.program) {
                    anyhow::bail!("unknown program");
                }

                // nfqws2 lists are per-item now (presets:: item API); this
                // legacy global-selector route must no longer rewrite its
                // config. byedpi keeps the old global selection model.
                if req.program == "nfqws2" {
                    anyhow::bail!("nfqws2 hostlists are per-item; use /api/presets/nfqws2/profile/{}/items", req.profile);
                }

                let prof_root = profile_root(&req.program, &req.profile);
                if !prof_root.exists() {
                    anyhow::bail!("profile not found");
                }

                let cfg = prof_root.join("config/config.txt");
                let data = fs::read(&cfg)
                    .map_err(|e| anyhow::anyhow!("read failed {}: {e}", cfg.display()))?;
                let sel = Selection {
                    hostlists: req.hostlists.clone(),
                    exclude_hostlists: req.exclude_hostlists.clone(),
                    ipsets: req.ipsets.clone(),
                    exclude_ipsets: req.exclude_ipsets.clone(),
                    variant_name: None,
                };
                let data = apply_selection_to_config(&data, &sel);
                write_bytes_atomic(&cfg, &data)?;
                Ok(json!({"ok": true}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();

    match res {
        Ok(v) => write_json(stream, 200, v),
        Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
    }
}


