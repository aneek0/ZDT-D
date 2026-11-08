// NFQWS2 preset library (user presets) and per-item list/strategy editing.
//
// A profile's config.txt IS the preset. Item endpoints parse it with
// presets::, apply one text transform, and write it back atomically. The
// strict compiler (strategic/scripts/command-builder.sh) stays the only
// runtime authority on argv semantics.

use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::json;
use std::{fs, net::TcpStream, path::{Path, PathBuf}};

use crate::settings;
use crate::api::http::write_json;
use crate::api::support::fs::{sha256_hex_bytes, write_bytes_atomic};
use crate::api::support::paths::ensure_safe_segment;
use super::programs::shared::{profile_root, refresh_apps_after_save_if_running};
use super::strategic::{list_txt_files_only, strategic_root, strategicvar_root};

#[derive(Debug, Deserialize)]
struct UserPresetReq { name: String, #[serde(default)] content: String }

#[derive(Debug, Deserialize)]
struct ContentBody { content: String }

fn user_presets_root() -> PathBuf {
    PathBuf::from(settings::MODULE_DIR).join("working_folder/user-presets/nfqws2")
}

fn ensure_user_preset_name(name: &str) -> Result<()> {
    let stem = name.strip_suffix(".txt").unwrap_or(name);
    if stem.is_empty() || stem.len() > 80 || !stem.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == ' ') || stem.trim() != stem {
        anyhow::bail!("invalid preset name");
    }
    Ok(())
}

fn user_preset_path(name: &str) -> Result<PathBuf> {
    ensure_user_preset_name(name)?;
    let filename = if name.ends_with(".txt") { name.to_owned() } else { format!("{name}.txt") };
    Ok(user_presets_root().join(filename))
}

fn normalize_user_preset_text(source: &str, name: &str, imported: bool) -> Result<String> {
    let mut text = source.trim_start_matches('\u{feff}').replace("\r\n", "\n").replace('\r', "\n");
    let mut lines: Vec<String> = text.split('\n').map(str::to_owned).collect();
    while lines.last().is_some_and(String::is_empty) { lines.pop(); }
    let header_end = lines.iter().position(|line| {
        let line = line.trim();
        !line.is_empty() && !line.starts_with('#')
    }).unwrap_or(lines.len());
    let mut header = Vec::new();
    let mut saw_preset = false;
    let mut saw_kind = false;
    for raw in &lines[..header_end] {
        let lower = raw.trim().to_ascii_lowercase();
        if lower.starts_with("# preset:") {
            if !saw_preset { header.push(format!("# Preset: {name}")); }
            saw_preset = true;
        } else if lower.starts_with("# presetkind:") {
            if imported && !saw_kind { header.push("# PresetKind: imported".to_owned()); }
            saw_kind = true;
        } else if !lower.starts_with("# modified:") && !lower.starts_with("# activepreset:") {
            header.push(raw.clone());
        }
    }
    if !saw_preset { header.insert(0, format!("# Preset: {name}")); }
    if imported && !saw_kind {
        let insert_at = usize::from(header.first().is_some_and(|line| line.starts_with("# Preset:")));
        header.insert(insert_at, "# PresetKind: imported".to_owned());
    }
    let mut body = lines[header_end..].to_vec();
    for line in &mut body {
        if line.trim().to_ascii_lowercase().starts_with("--debug=@logs/") {
            *line = line.replacen("--debug=@logs/", "--debug=@user/logs/", 1);
        }
    }
    let mut normalized = header;
    normalized.extend(body);
    text = normalized.join("\n");
    let text = ensure_nfqws2_lua_init_block(&text)?;

    Ok(format!("{}\n", text.trim_end_matches('\n')))
}

fn ensure_nfqws2_lua_init_block(source: &str) -> Result<String> {
    const REQUIRED: [&str; 8] = [
        "lua/zapret-lib.lua", "lua/zapret-antidpi.lua", "lua/zapret-auto.lua",
        "lua/custom_funcs.lua", "lua/custom_diag.lua", "lua/zapret-multishake.lua",
        "lua/fakemultisplit.lua", "lua/fakemultidisorder.lua",
    ];
    let lines: Vec<String> = source.lines().map(str::to_owned).collect();
    let header_end = lines.iter().position(|line| {
        let line = line.trim();
        !line.is_empty() && !line.starts_with('#')
    }).unwrap_or(lines.len());
    let mut body = Vec::new();
    for raw in &lines[header_end..] {
        let stripped = raw.trim();
        let parts = split_preset_options(stripped);
        let kept: Vec<_> = parts.into_iter().filter(|part| {
            let lower = part.to_ascii_lowercase();
            let Some(value) = lower.strip_prefix("--lua-init=") else { return true; };
            let path = value.trim_matches(['\'', '"']).trim_start_matches('@').replace('\\', "/");
            !REQUIRED.iter().any(|required| path == *required || path.ends_with(&format!("/{required}")))
        }).collect();
        if kept.len() != 1 || kept.first().is_none_or(|part| *part != stripped) {
            if !kept.is_empty() { body.push(kept.join(" ")); }
        } else { body.push(raw.clone()); }
    }
    let mut output = lines[..header_end].to_vec();
    while output.last().is_some_and(|line| line.trim().is_empty()) { output.pop(); }
    output.extend(REQUIRED.iter().map(|path| format!("--lua-init=@{path}")));
    output.extend(body);
    Ok(output.join("\n"))
}

fn split_preset_options(line: &str) -> Vec<&str> {
    let mut starts = vec![0];
    let bytes = line.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_whitespace() {
            while i < bytes.len() && bytes[i].is_ascii_whitespace() { i += 1; }
            if i + 1 < bytes.len() && bytes[i] == b'-' && bytes[i + 1] == b'-' { starts.push(i); }
        } else { i += 1; }
    }
    starts.iter().copied().enumerate().filter_map(|(idx, start)| {
        let end = starts.get(idx + 1).copied().unwrap_or(bytes.len());
        let part = line.get(start..end)?.trim();
        (!part.is_empty()).then_some(part)
    }).collect()
}

fn save_user_preset(path: &Path, source: &str, imported: bool) -> Result<()> {
    let name = path.file_stem().and_then(|s| s.to_str()).context("invalid preset filename")?;
    let normalized = normalize_user_preset_text(source, name, imported)?;
    write_bytes_atomic(path, normalized.as_bytes())
}

pub(in crate::api) fn handle_user_presets(stream: TcpStream, method: &str, path: &str, body: &[u8], services_running: bool) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let result = (|| -> Result<serde_json::Value> {
        match (method, seg.as_slice()) {
            ("GET", ["api", "presets", "nfqws2"]) => {
                let files = list_txt_files_only(&user_presets_root())?;
                let meta: Vec<_> = files.iter().filter_map(|n| fs::read(user_presets_root().join(n)).ok().map(|b| json!({"name":n,"sha256":sha256_hex_bytes(&b)}))).collect();
                Ok(json!({"ok":true,"files":files,"meta":meta}))
            }
            ("GET", ["api", "presets", "nfqws2", name]) => {
                let p = user_preset_path(name)?;
                let content = fs::read_to_string(&p).map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))?;
                Ok(json!({"ok":true,"name":p.file_name().unwrap().to_string_lossy(),"content":content}))
            }
            ("POST", ["api", "presets", "nfqws2"]) | ("POST", ["api", "presets", "nfqws2", "import"]) => {
                let req: UserPresetReq = serde_json::from_slice(body)?;
                let p = user_preset_path(&req.name)?;
                if p.exists() { anyhow::bail!("preset already exists"); }
                let imported = seg.last() == Some(&"import");
                save_user_preset(&p, &req.content, imported)?;
                Ok(json!({"ok":true,"name":p.file_name().unwrap().to_string_lossy()}))
            }
            ("PUT", ["api", "presets", "nfqws2", name]) => {
                let req: ContentBody = serde_json::from_slice(body)?;
                let p = user_preset_path(name)?;
                if !p.is_file() { anyhow::bail!("preset not found"); }
                save_user_preset(&p, &req.content, false)?;
                Ok(json!({"ok":true}))
            }
            ("POST", ["api", "presets", "nfqws2", name, "duplicate"]) | ("POST", ["api", "presets", "nfqws2", name, "rename"]) => {
                let req: UserPresetReq = serde_json::from_slice(body)?;
                let src = user_preset_path(name)?;
                let dst = user_preset_path(&req.name)?;
                if !src.is_file() { anyhow::bail!("preset not found"); }
                if dst.exists() { anyhow::bail!("preset already exists"); }
                if seg.last() == Some(&"duplicate") {
                    let source = fs::read_to_string(&src)?;
                    save_user_preset(&dst, &source, false)?;
                } else {
                    let source = fs::read_to_string(&src)?;
                    save_user_preset(&dst, &source, false)?;
                    fs::remove_file(src)?;
                }
                Ok(json!({"ok":true,"name":dst.file_name().unwrap().to_string_lossy()}))
            }
            ("DELETE", ["api", "presets", "nfqws2", name]) => {
                let p = user_preset_path(name)?;
                fs::remove_file(p).map_err(|e| anyhow::anyhow!("delete failed: {e}"))?;
                Ok(json!({"ok":true}))
            }
            ("POST", ["api", "presets", "nfqws2", "export", name]) => {
                let p = user_preset_path(name)?;
                let content = fs::read_to_string(p)?;
                Ok(json!({"ok":true,"name":name,"content":content}))
            }
            ("POST", ["api", "presets", "nfqws2", "apply"]) => {
                // Whole-preset apply writes the normalized preset verbatim:
                // hostlist/ipset refs live inside the item text, global
                // selection injection is gone (parity with the item API).
                #[derive(Deserialize)] struct ApplyReq { profile:String, name:String, content:String }
                let req: ApplyReq = serde_json::from_slice(body)?;
                ensure_safe_segment(&req.profile, "profile")?;
                let root = profile_root("nfqws2", &req.profile);
                if !root.exists() { anyhow::bail!("profile not found"); }
                let cfg = root.join("config/config.txt");
                let normalized = normalize_user_preset_text(&req.content, &req.name, false)?;
                write_bytes_atomic(&cfg, normalized.as_bytes())?;
                refresh_apps_after_save_if_running(services_running, "nfqws2", Some(&req.profile), "common")?;
                Ok(json!({"ok":true}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();
    match result { Ok(v) => write_json(stream, 200, v), Err(e) => write_json(stream, 200, json!({"ok":false,"error":format!("{e:#}")})) }
}

// --- NFQWS2 per-item API -------------------------------------------------

/// Absolute prefix preset hostlist/ipset lines use.
fn nfqws2_module_list_prefix() -> String {
    format!("{}/strategic/list/", strategic_root().display())
}

/// L7 protocols that run over TCP (zapretgui `_TCP_L7`); everything else is
/// UDP-ish.
fn l7_is_tcp(value: &str) -> bool {
    let names: std::collections::HashSet<String> = value
        .split(',')
        .map(|t| t.trim().to_ascii_lowercase())
        .filter(|t| !t.is_empty())
        .collect();
    if names.is_empty() {
        return false;
    }
    names.iter().all(|n| matches!(n.as_str(), "http" | "tls" | "xmpp" | "mtproto" | "bt"))
}

fn parse_port_token(token: &str) -> Option<(u32, u32)> {
    let t = token.trim();
    if t.is_empty() {
        return None;
    }
    if let Some((l, r)) = t.split_once('-') {
        let (l, r) = (l.trim(), r.trim());
        if !l.chars().all(|c| c.is_ascii_digit()) || !r.chars().all(|c| c.is_ascii_digit()) {
            return None;
        }
        let mut s: u32 = l.parse().ok()?;
        let mut e: u32 = r.parse().ok()?;
        if s > e {
            std::mem::swap(&mut s, &mut e);
        }
        return Some((s, e));
    }
    let p: u32 = t.parse().ok()?;
    Some((p, p))
}

fn ports_overlap(raw: &str, start: u32, end: u32) -> bool {
    raw.split(',').filter_map(parse_port_token).any(|(s, e)| s <= end && e >= start)
}

fn ports_equal(raw: &str, port: u32) -> bool {
    let mut seen = false;
    for token in raw.split(',') {
        let Some((s, e)) = parse_port_token(token) else { continue };
        if e - s > 512 {
            if s != port && e != port {
                return false;
            }
            seen = true;
            continue;
        }
        for p in s..=e {
            if p != port {
                return false;
            }
            seen = true;
        }
    }
    seen
}

/// One item's match lines -> (catalog, protocol_label, ports_label) mirroring
/// zapretgui match_filters.py.
fn classify_match_lines(match_lines: &[String]) -> (&'static str, &'static str, String) {
    let values = |option: &str| -> Vec<String> {
        let prefix = format!("--{option}=");
        match_lines
            .iter()
            .filter_map(|l| l.to_ascii_lowercase().starts_with(&prefix).then(|| l.split_once('=').map(|x| x.1.trim().to_string())).flatten())
            .collect()
    };
    let tcp_values = values("filter-tcp");
    let udp_values = values("filter-udp");
    let l7_values = values("filter-l7");
    let joined = match_lines.join(" ").to_ascii_lowercase();

    // Voice: stun/discord/wireguard l7, or stun-ish name + UDP 50000-59000.
    let l7_joined = l7_values.join(",").to_ascii_lowercase();
    let is_voice = l7_joined.split(',').any(|t| matches!(t.trim(), "stun" | "discord" | "wireguard"))
        || (["discord", "stun", "voice", "голос"].iter().any(|t| joined.contains(t))
            && udp_values.iter().any(|v| ports_overlap(v, 50000, 59000)));
    let is_http80 = !tcp_values.is_empty()
        && udp_values.is_empty()
        && !(!l7_values.is_empty() && !l7_is_tcp(&l7_values.join(",")))
        && ports_equal(&tcp_values.join(","), 80);

    let catalog = if is_voice {
        "voice"
    } else if !udp_values.is_empty() && tcp_values.is_empty() {
        "udp"
    } else if !tcp_values.is_empty() && udp_values.is_empty() {
        if is_http80 { "http80" } else { "tcp" }
    } else if !l7_values.is_empty() {
        if l7_is_tcp(&l7_values.join(",")) { "tcp" } else { "udp" }
    } else {
        "tcp"
    };

    let protocol = if is_voice {
        "Voice"
    } else if !tcp_values.is_empty() && (!udp_values.is_empty() || !l7_values.is_empty()) {
        "TCP/UDP"
    } else if !l7_values.is_empty() {
        "L7"
    } else if !udp_values.is_empty() {
        "UDP"
    } else if is_http80 {
        "TCP/HTTP"
    } else {
        "TCP"
    };

    let mut parts: Vec<String> = Vec::new();
    if !tcp_values.is_empty() {
        parts.push(format!("TCP {}", tcp_values.join(", ")));
    }
    if !udp_values.is_empty() {
        parts.push(format!("UDP {}", udp_values.join(", ")));
    }
    let ports_label = parts.join("; ");
    (catalog, protocol, ports_label)
}

/// JSON projection of one profile (item) for the editor UI.
fn profile_to_item_json(profile: &crate::presets::Profile, index: usize, module_list: &str) -> serde_json::Value {
    use crate::presets::{MatchFamily, Segment};
    let mut filters: Vec<serde_json::Value> = Vec::new();
    let mut lists: Vec<serde_json::Value> = Vec::new();
    let mut strategy_lines: Vec<String> = Vec::new();
    let mut raw_lines: Vec<String> = Vec::new();
    for seg in &profile.segments {
        let line = seg.text().to_string();
        raw_lines.push(line.clone());
        match seg {
            Segment::Match { family, .. } => {
                let option = line.split_once('=').map(|x| x.0.to_string()).unwrap_or_default();
                let value = line.split_once('=').map(|x| x.1.trim().to_string()).unwrap_or_default();
                match family {
                    MatchFamily::FilterTcp | MatchFamily::FilterUdp | MatchFamily::FilterL7 | MatchFamily::FilterOther => {
                        filters.push(json!({"option": option, "value": value}));
                    }
                    MatchFamily::Hostlist
                    | MatchFamily::HostlistExclude
                    | MatchFamily::HostlistDomains
                    | MatchFamily::HostlistAuto
                    | MatchFamily::Ipset
                    | MatchFamily::IpsetExclude
                    | MatchFamily::InlineIpset => {
                        let kind = match family {
                            MatchFamily::Hostlist => "hostlist",
                            MatchFamily::HostlistExclude => "hostlist-exclude",
                            MatchFamily::HostlistDomains => "hostlist-domains",
                            MatchFamily::HostlistAuto => "hostlist-auto",
                            MatchFamily::Ipset => "ipset",
                            MatchFamily::IpsetExclude => "ipset-exclude",
                            MatchFamily::InlineIpset => "ipset-ip",
                            _ => "other",
                        };
                        let basename = crate::presets::model::basename_of(&value).to_string();
                        let is_file_ref = value.starts_with(module_list) || value.starts_with("lists/");
                        lists.push(json!({
                            "kind": kind,
                            "option": option,
                            "value": value,
                            "name": basename,
                            "file_ref": is_file_ref,
                        }));
                    }
                    _ => {}
                }
            }
            Segment::StrategyFilter { .. } | Segment::Strategy { .. } => {
                strategy_lines.push(line);
            }
            _ => {}
        }
    }
    let match_lines: Vec<String> = profile
        .segments
        .iter()
        .filter_map(|s| match s {
            Segment::Match { line, .. } => Some(line.clone()),
            _ => None,
        })
        .collect();
    let (catalog, protocol, ports_label) = classify_match_lines(&match_lines);
    json!({
        "index": index,
        "id": format!("item-{index}"),
        "name": profile.name,
        "display_name": profile.display_name(),
        "enabled": profile.enabled,
        "filters": filters,
        "lists": lists,
        "strategy": strategy_lines,
        "raw": raw_lines.join("\n"),
        "catalog": catalog,
        "protocol": protocol,
        "ports": ports_label,
    })
}

/// Parse config.txt of an nfqws2 profile; error when the layout is missing.
fn nfqws2_profile_config(profile: &str) -> Result<(PathBuf, String)> {
    ensure_safe_segment(profile, "profile")?;
    let root = profile_root("nfqws2", profile);
    if !root.exists() {
        anyhow::bail!("profile not found");
    }
    let cfg = root.join("config/config.txt");
    let text = fs::read_to_string(&cfg)
        .with_context(|| format!("read {}", cfg.display()))?;
    Ok((cfg, text))
}

/// Apply one `source -> Option<String>` transform to the profile config and
/// schedule a runtime refresh when the service is running.
fn nfqws2_item_transform(
    profile: &str,
    services_running: bool,
    transform: impl Fn(&str) -> Option<String>,
) -> Result<serde_json::Value> {
    let (cfg, text) = nfqws2_profile_config(profile)?;
    let Some(new_text) = transform(&text) else {
        anyhow::bail!("item transform failed (bad index or raw text)");
    };
    write_bytes_atomic(&cfg, new_text.as_bytes())?;
    refresh_apps_after_save_if_running(services_running, "nfqws2", Some(profile), "common")?;
    Ok(json!({"ok": true, "text": new_text}))
}

pub(in crate::api) fn handle_nfqws2_items(stream: TcpStream, method: &str, path: &str, body: &[u8], services_running: bool) -> Result<()> {
    // Routes (all under /api/presets/nfqws2/profile/{profile}):
    //   GET  items                     -> full item list projection
    //   GET  items/{i}                 -> single item projection
    //   POST items/{i}/enabled         (JSON {enabled})
    //   POST items/{i}/strategy        (JSON {lines, whole})
    //   POST items/{i}/match           (JSON {name, filter, hostlist, ipset})
    //   POST items/{i}/raw             (JSON {raw})
    //   POST items/append              (JSON {template: [lines], at})
    //   POST items/{i}/delete
    //   POST items/{i}/duplicate
    //   POST items/{i}/move            (JSON {delta})
    //   GET  strategies                -> per-family strategy catalog
    //   GET  lists                     -> available layered lists
    //   GET  lists/{name}              -> {base, user, final} entries
    //   PUT  lists/{name}              (JSON {entries, kind})
    //   POST lists/{name}/delete       -> drop user layer
    //   POST lists                     (JSON {name, kind}) -> create user list
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let result = (|| -> Result<serde_json::Value> {
        if seg.len() < 6 || seg[0] != "api" || seg[1] != "presets" || seg[2] != "nfqws2" || seg[3] != "profile" {
            anyhow::bail!("not found");
        }
        let profile = seg[4];
        ensure_safe_segment(profile, "profile")?;
        use crate::presets as p;

        let module_list = nfqws2_module_list_prefix();

        match (method, seg.as_slice()) {
            ("GET", [_, _, _, _, _, "items"]) => {
                let (_, text) = nfqws2_profile_config(profile)?;
                let doc = p::parse_preset(&text);
                let items: Vec<_> = doc.profiles.iter().enumerate()
                    .map(|(i, pr)| profile_to_item_json(pr, i, &module_list))
                    .collect();
                Ok(json!({"ok": true, "items": items, "text": text}))
            }
            ("GET", [_, _, _, _, _, "items", idx]) => {
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                let (_, text) = nfqws2_profile_config(profile)?;
                let doc = p::parse_preset(&text);
                let item = doc.profiles.get(i).ok_or_else(|| anyhow::anyhow!("no such item"))?;
                Ok(json!({"ok": true, "item": profile_to_item_json(item, i, &module_list)}))
            }
            ("POST", [_, _, _, _, _, "items", idx, "enabled"]) => {
                #[derive(Deserialize)]
                struct Req { enabled: bool }
                let req: Req = serde_json::from_slice(body)?;
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| p::set_profile_enabled(t, i, req.enabled))
            }
            ("POST", [_, _, _, _, _, "items", idx, "strategy"]) => {
                #[derive(Deserialize)]
                struct Req { lines: Vec<String>, #[serde(default)] whole: bool }
                let req: Req = serde_json::from_slice(body)?;
                p::validation::validate_user_strategy_lines(&req.lines)
                    .ok_or_else(|| anyhow::anyhow!("invalid strategy lines"))?;
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| {
                    if req.whole { p::with_profile_whole_strategy(t, i, &req.lines) } else { p::with_profile_strategy(t, i, &req.lines) }
                })
            }
            ("POST", [_, _, _, _, _, "items", idx, "match"]) => {
                #[derive(Deserialize)]
                struct Req {
                    #[serde(default)] name: Option<String>,
                    #[serde(default)] filter: Option<String>,
                    #[serde(default)] filter_transport: Option<String>,
                    #[serde(default)] hostlist: Option<String>,
                    #[serde(default)] ipset: Option<String>,
                }
                let req: Req = serde_json::from_slice(body)?;
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                let _ = req.filter_transport;
                nfqws2_item_transform(profile, services_running, move |t| {
                    p::set_profile_user_match(t, i, req.name.as_deref(), req.filter.as_deref(), req.hostlist.as_deref(), req.ipset.as_deref())
                })
            }
            ("POST", [_, _, _, _, _, "items", idx, "raw"]) => {
                #[derive(Deserialize)]
                struct Req { raw: String }
                let req: Req = serde_json::from_slice(body)?;
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| p::with_profile_raw_text(t, i, &req.raw))
            }
            ("POST", [_, _, _, _, _, "items", "append"]) => {
                #[derive(Deserialize)]
                struct Req { template: Vec<String>, #[serde(default)] at: Option<usize> }
                let req: Req = serde_json::from_slice(body)?;
                if req.template.is_empty() {
                    anyhow::bail!("empty template");
                }
                nfqws2_item_transform(profile, services_running, move |t| p::append_profile(t, &req.template, req.at))
            }
            ("POST", [_, _, _, _, _, "items", idx, "delete"]) => {
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| p::delete_profile(t, i))
            }
            ("POST", [_, _, _, _, _, "items", idx, "duplicate"]) => {
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| p::duplicate_profile(t, i))
            }
            ("POST", [_, _, _, _, _, "items", idx, "move"]) => {
                #[derive(Deserialize)]
                struct Req { delta: i32 }
                let req: Req = serde_json::from_slice(body)?;
                let i: usize = idx.parse().map_err(|_| anyhow::anyhow!("bad index"))?;
                nfqws2_item_transform(profile, services_running, move |t| p::move_profile(t, i, req.delta))
            }
            ("GET", [_, _, _, _, _, "strategies"]) => {
                // Catalog built from builtin + user preset strategy fragments,
                // classified per-family. Whole-preset preamble args are not
                // part of an item strategy.
                let mut out: serde_json::Map<String, serde_json::Value> = serde_json::Map::new();
                let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
                fn push_preset(
                    name: &str,
                    text: &str,
                    builtin: bool,
                    out: &mut serde_json::Map<String, serde_json::Value>,
                    seen: &mut std::collections::HashSet<String>,
                ) {
                    let doc = crate::presets::parse_preset(text);
                    for (i, pr) in doc.profiles.iter().enumerate() {
                        let lines: Vec<String> = pr
                            .segments
                            .iter()
                            .filter_map(|s| match s {
                                crate::presets::Segment::StrategyFilter { line } | crate::presets::Segment::Strategy { line } => Some(line.clone()),
                                _ => None,
                            })
                            .collect();
                        if lines.is_empty() || !lines.iter().any(|l| l.trim().starts_with("--lua-desync=")) {
                            continue;
                        }
                        let label = pr.name.clone().unwrap_or_else(|| pr.display_name());
                        let key = format!("{builtin}|{name}|{label}");
                        if !seen.insert(key.clone()) {
                            continue;
                        }
                        let match_lines: Vec<String> = pr
                            .segments
                            .iter()
                            .filter_map(|s| match s {
                                crate::presets::Segment::Match { line, .. } => Some(line.clone()),
                                _ => None,
                            })
                            .collect();
                        let (catalog, _proto, _ports) = classify_match_lines(&match_lines);
                        let arr = out.entry(catalog.to_string()).or_insert_with(|| json!([]));
                        arr.as_array_mut().unwrap().push(json!({
                            "name": label,
                            "preset": name,
                            "index": i,
                            "builtin": builtin,
                            "lines": lines,
                        }));
                    }
                }
                let builtin_dir = strategicvar_root().join("nfqws2");
                for name in list_txt_files_only(&builtin_dir)? {
                    if let Ok(text) = fs::read_to_string(builtin_dir.join(&name)) {
                        push_preset(&name, &text, true, &mut out, &mut seen);
                    }
                }
                for name in list_txt_files_only(&user_presets_root())? {
                    if let Ok(text) = fs::read_to_string(user_presets_root().join(&name)) {
                        push_preset(&name, &text, false, &mut out, &mut seen);
                    }
                }
                Ok(json!({"ok": true, "families": out}))
            }
            ("GET", [_, _, _, _, _, "lists"]) => {
                let lists = p::service::LayeredLists::new(&strategic_root().join("list"));
                let mut names = list_txt_files_only(&strategic_root().join("list"))?;
                // Include user-layer-only lists whose final was written
                // without the .txt suffix (legacy or pre-normalization
                // creations); list_txt_files_only skips non-.txt finals.
                for layer_name in list_txt_files_only(&strategic_root().join("list/list-layers/user"))? {
                    if !names.contains(&layer_name) {
                        names.push(layer_name.clone());
                    }
                }
                names.sort();
                let mut items: Vec<serde_json::Value> = Vec::new();
                for name in names {
                    let Some(plan) = lists.plan(&name) else { continue };
                    let lower = name.to_ascii_lowercase();
                    let kind = if lower.starts_with("ipset") || lower.contains("ipset") { "ipset" } else { "hostlist" };
                    let managed = !matches!(plan.ownership, p::service::Ownership::External);
                    let user_count = if managed { lists.read_user(&name).map(|v| v.len()).unwrap_or(0) } else { 0 };
                    let final_count = lists.read_final(&name).map(|v| v.len()).unwrap_or(0);
                    items.push(json!({
                        "name": name,
                        "kind": kind,
                        "managed": managed,
                        "user_entries": user_count,
                        "final_entries": final_count,
                    }));
                }
                Ok(json!({"ok": true, "lists": items}))
            }
            ("GET", [_, _, _, _, _, "lists", name]) => {
                let lists = p::service::LayeredLists::new(&strategic_root().join("list"));
                let Some(plan) = lists.plan(name) else { anyhow::bail!("unsafe list name") };
                let base = plan.base_layer.as_ref().map(|p| fs::read_to_string(p).unwrap_or_default())
                    .map(|t| t.lines().map(str::to_string).collect::<Vec<_>>());
                let user = lists.read_user(name).unwrap_or_default();
                let final_entries = lists.read_final(name).unwrap_or_default();
                Ok(json!({
                    "ok": true,
                    "name": name,
                    "managed": !matches!(plan.ownership, p::service::Ownership::External),
                    "base": base,
                    "user": user,
                    "final": final_entries,
                }))
            }
            ("PUT", [_, _, _, _, _, "lists", name]) => {
                #[derive(Deserialize)]
                struct Req { entries: Vec<String>, kind: String }
                let req: Req = serde_json::from_slice(body)?;
                let kind = match req.kind.as_str() {
                    "hostlist" => p::service::ListKind::Hostlist,
                    "ipset" => p::service::ListKind::Ipset,
                    other => anyhow::bail!("bad kind {other}"),
                };
                let lists = p::service::LayeredLists::new(&strategic_root().join("list"));
                lists.write_user(name, &req.entries, kind).map_err(|e| anyhow::anyhow!("{e}"))?;
                Ok(json!({"ok": true}))
            }
            ("POST", [_, _, _, _, _, "lists", name, "delete"]) => {
                let lists = p::service::LayeredLists::new(&strategic_root().join("list"));
                lists.delete_user(name).map_err(|e| anyhow::anyhow!("{e}"))?;
                Ok(json!({"ok": true}))
            }
            ("POST", [_, _, _, _, _, "lists"]) => {
                #[derive(Deserialize)]
                struct Req { name: String, kind: String }
                let req: Req = serde_json::from_slice(body)?;
                let lists = p::service::LayeredLists::new(&strategic_root().join("list"));
                // Normalize: every list file gets the .txt suffix so it is
                // discoverable by list_txt_files_only and consistent with
                // the shipped builtin lists.
                let name = if req.name.to_ascii_lowercase().ends_with(".txt") {
                    req.name.clone()
                } else {
                    format!("{}.txt", req.name.trim())
                };
                if lists.plan(&name).map(|pl| pl.final_path.exists()).unwrap_or(false) {
                    anyhow::bail!("list already exists");
                }
                let kind = match req.kind.as_str() {
                    "hostlist" => p::service::ListKind::Hostlist,
                    "ipset" => p::service::ListKind::Ipset,
                    other => anyhow::bail!("bad kind {other}"),
                };
                lists.write_user(&name, &[], kind).map_err(|e| anyhow::anyhow!("{e}"))?;
                Ok(json!({"ok": true, "name": name}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();
    match result { Ok(v) => write_json(stream, 200, v), Err(e) => write_json(stream, 200, json!({"ok":false,"error":format!("{e:#}")})) }
}

#[cfg(test)]
mod user_preset_tests {
    use super::*;

    #[test]
    fn user_preset_names_are_safe_and_storage_is_separate() {
        assert!(user_preset_path("My preset.txt").is_ok());
        assert!(user_preset_path("../built-in").is_err());
        assert!(user_preset_path("/absolute").is_err());
        assert!(!user_presets_root().ends_with("strategic/strategicvar/nfqws2"));
    }
}

#[cfg(test)]
mod nfqws2_item_tests {
    use super::*;

    fn s(lines: &[&str]) -> Vec<String> {
        lines.iter().map(|l| l.to_string()).collect()
    }

    #[test]
    fn classify_mirrors_zapretgui_voice_udp_http80_tcp() {
        let (cat, proto, _) = classify_match_lines(&s(&["--filter-l7=discord"]));
        assert_eq!((cat, proto), ("voice", "Voice"));
        let (cat, _, _) = classify_match_lines(&s(&["--filter-udp=50000-51000", "--name=voice"]));
        assert_eq!(cat, "voice");
        let (cat, proto, ports) = classify_match_lines(&s(&["--filter-udp=443"]));
        assert_eq!((cat, proto), ("udp", "UDP"));
        assert_eq!(ports, "UDP 443");
        let (cat, proto, _) = classify_match_lines(&s(&["--filter-tcp=80"]));
        assert_eq!((cat, proto), ("http80", "TCP/HTTP"));
        let (cat, proto, _) = classify_match_lines(&s(&["--filter-tcp=443"]));
        assert_eq!((cat, proto), ("tcp", "TCP"));
        let (cat, proto, _) = classify_match_lines(&s(&["--filter-tcp=80,443", "--filter-udp=443"]));
        assert_eq!((cat, proto), ("tcp", "TCP/UDP"));
        let (cat, _, _) = classify_match_lines(&s(&["--filter-l7=quic"]));
        assert_eq!(cat, "udp");
        let (cat, _, _) = classify_match_lines(&s(&["--filter-l7=http,tls"]));
        assert_eq!(cat, "tcp");
    }

    #[test]
    fn item_projection_splits_filters_lists_strategy() {
        let text = "--filter-tcp=443\n--hostlist=/data/adb/modules/ZDT-D/strategic/list/discord.txt\n--lua-desync=something\n";
        let doc = crate::presets::parse_preset(text);
        let item = profile_to_item_json(&doc.profiles[0], 0, "/data/adb/modules/ZDT-D/strategic/list/");
        assert_eq!(item["index"], 0);
        assert_eq!(item["enabled"], true);
        assert_eq!(item["filters"][0]["option"], "--filter-tcp");
        assert_eq!(item["filters"][0]["value"], "443");
        assert_eq!(item["lists"][0]["kind"], "hostlist");
        assert_eq!(item["lists"][0]["name"], "discord.txt");
        assert_eq!(item["lists"][0]["file_ref"], true);
        assert_eq!(item["strategy"][0], "--lua-desync=something");
        assert_eq!(item["catalog"], "tcp");
        assert_eq!(item["protocol"], "TCP");
        assert_eq!(item["ports"], "TCP 443");
        assert_eq!(item["raw"], text.trim_end_matches('\n'));
    }

    #[test]
    fn http80_detection_survives_range_notation() {
        let (cat, _, _) = classify_match_lines(&s(&["--filter-tcp=79-81"]));
        assert_eq!(cat, "tcp");
        let (cat, _, _) = classify_match_lines(&s(&["--filter-tcp=80-81"]));
        assert_eq!(cat, "tcp");
        let (cat, _, _) = classify_match_lines(&s(&["--filter-tcp=80"]));
        assert_eq!(cat, "http80");
    }
}
