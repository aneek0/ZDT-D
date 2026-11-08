#![allow(dead_code)]
use anyhow::{bail, Context, Result};
use serde_json::{Map, Value};
use std::{collections::BTreeSet, fs, path::{Path, PathBuf}, time::Duration};

use crate::common::fs::ensure_parent_dir;
use crate::common::network::is_valid_ifname;
use crate::common::process::shell::{self, Capture};

use super::{append_check_log, forbidden_tun, unique_tmp_path, write_json_atomic, VariantSpec};

const SINGBOX_CHECK_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) fn collect_singbox_inbound_tags(obj: &Map<String, Value>) -> Vec<String> {
    let mut tags = obj
        .get("inbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|item| item.as_object())
                .filter_map(|o| o.get("tag").and_then(|v| v.as_str()))
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    tags.sort();
    tags.dedup();
    tags
}

pub(crate) fn collect_singbox_direct_dns_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Some(rules) = obj
        .get("dns")
        .and_then(|v| v.as_object())
        .and_then(|o| o.get("rules"))
        .and_then(|v| v.as_array())
    {
        for rule in rules {
            let Some(rule_obj) = rule.as_object() else { continue };
            if rule_obj
                .get("server")
                .and_then(|v| v.as_str())
                .map(|s| s == "dns-direct")
                .unwrap_or(false)
            {
                collect_domains_from_value(rule_obj.get("domain"), &mut out);
            }
        }
    }
    out
}

pub(crate) fn collect_domains_from_value(value: Option<&Value>, out: &mut BTreeSet<String>) {
    match value {
        Some(Value::String(s)) => {
            let s = s.trim();
            if !s.is_empty() {
                out.insert(s.to_string());
            }
        }
        Some(Value::Array(arr)) => {
            for item in arr {
                collect_domains_from_value(Some(item), out);
            }
        }
        _ => {}
    }
}

pub(crate) fn has_singbox_outbound_tag(obj: &Map<String, Value>, tag: &str) -> bool {
    obj.get("outbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter().any(|item| {
                item.as_object()
                    .and_then(|o| o.get("tag"))
                    .and_then(|v| v.as_str())
                    .map(|s| s == tag)
                    .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

pub(crate) fn is_vpn_removed_route_rule(rule: &Value) -> bool {
    let Some(obj) = rule.as_object() else { return false };
    obj.get("action")
        .and_then(|v| v.as_str())
        .map(|s| s.eq_ignore_ascii_case("hijack-dns"))
        .unwrap_or(false)
}

pub(crate) fn normalize_domain_strategy(strategy: &str) -> String {
    match strategy.trim().to_ascii_lowercase().as_str() {
        "prefer_ipv6" => "prefer_ipv6".to_string(),
        "ipv4_only" => "ipv4_only".to_string(),
        "ipv6_only" => "ipv6_only".to_string(),
        _ => "prefer_ipv4".to_string(),
    }
}

pub(crate) fn effective_vpn_tun_from_config_value(
    spec: VariantSpec,
    value: &Value,
    fallback: &str,
) -> Result<String> {
    let from_config = value
        .get("inbounds")
        .and_then(|v| v.as_array())
        .and_then(|arr| {
            arr.iter().find_map(|item| {
                let obj = item.as_object()?;
                let ty = obj.get("type")?.as_str()?.trim().to_ascii_lowercase();
                if ty != "tun" {
                    return None;
                }
                obj.get("interface_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(ToOwned::to_owned)
            })
        });
    let tun = from_config.unwrap_or_else(|| fallback.trim().to_string()).trim().to_string();
    let tun = if tun.is_empty() { spec.default_tun.to_string() } else { tun };
    if !is_valid_ifname(&tun) || forbidden_tun(&tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    Ok(tun)
}

pub(crate) fn write_json_value_if_changed(
    spec: VariantSpec,
    config_path: &Path,
    original: &str,
    value: &Value,
) -> Result<()> {
    let normalized = serde_json::to_string_pretty(value)?;
    let normalized = format!("{}\n", normalized);
    if normalized != original {
        let tmp = unique_tmp_path(config_path);
        fs::write(&tmp, normalized).with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, config_path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), config_path.display()))?;
        log::info!("{}: normalized config {}", spec.label, config_path.display());
    }
    Ok(())
}

pub(crate) fn output_mentions_dns_mode(out: &str) -> bool {
    out.to_ascii_lowercase().contains("dns_mode")
}

pub(crate) fn remove_tun_dns_mode(spec: VariantSpec, config_path: &Path) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    if let Some(inbounds) = value.get_mut("inbounds").and_then(|v| v.as_array_mut()) {
        for inbound in inbounds {
            let Some(obj) = inbound.as_object_mut() else { continue };
            if obj
                .get("type")
                .and_then(|v| v.as_str())
                .map(|s| s.eq_ignore_ascii_case("tun"))
                .unwrap_or(false)
            {
                obj.remove("dns_mode");
                obj.remove("dns_address");
            }
        }
    }
    write_json_value_if_changed(spec, config_path, &original, &value)
}

pub(crate) fn run_singbox_check(spec: VariantSpec, config_path: &Path) -> Result<(i32, String)> {
    let cfg = config_path
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("non-utf8 config path: {}", config_path.display()))?;
    shell::run_timeout(spec.binary, &["check", "-c", cfg], Capture::Both, SINGBOX_CHECK_TIMEOUT)
        .with_context(|| format!("run {} check -c {}", spec.label, config_path.display()))
}

pub(crate) fn write_check_failure_log(
    spec: VariantSpec,
    log_path: &Path,
    config_path: &Path,
    code: i32,
    out: &str,
) -> Result<()> {
    let text = format!(
        "{} check failed\nconfig: {}\nrc: {}\n{}\n",
        spec.label,
        config_path.display(),
        code,
        out.trim()
    );
    fs::write(log_path, text).with_context(|| format!("write {}", log_path.display()))
}

pub(crate) fn singbox_check_config(spec: VariantSpec, config_path: &Path) -> Result<()> {
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code != 0 {
        bail!("{} check failed rc={code}: {}", spec.label, out.trim());
    }
    Ok(())
}

pub(crate) fn singbox_check_config_with_log(
    spec: VariantSpec,
    config_path: &Path,
    log_path: &Path,
) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code != 0 {
        write_check_failure_log(spec, log_path, config_path, code, &out)?;
        bail!("{} check failed rc={code}: {}", spec.label, out.trim());
    }
    Ok(())
}

pub(crate) fn singbox_check_config_with_vpn_retry(
    spec: VariantSpec,
    config_path: &Path,
    log_path: &Path,
) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code == 0 {
        return Ok(());
    }

    if output_mentions_dns_mode(&out) {
        append_check_log(
            log_path,
            &format!(
                "{} check failed with dns_mode, retrying without dns_mode\nconfig: {}\nrc: {}\n{}\n",
                spec.label,
                config_path.display(),
                code,
                out.trim()
            ),
        )?;
        remove_tun_dns_mode(spec, config_path)?;
        let (retry_code, retry_out) = run_singbox_check(spec, config_path)?;
        if retry_code == 0 {
            append_check_log(
                log_path,
                &format!("{} check succeeded after removing unsupported tun dns_mode\n", spec.label),
            )?;
            return Ok(());
        }
        write_check_failure_log(spec, log_path, config_path, retry_code, &retry_out)?;
        bail!("{} check failed rc={retry_code}: {}", spec.label, retry_out.trim());
    }

    write_check_failure_log(spec, log_path, config_path, code, &out)?;
    bail!("{} check failed rc={code}: {}", spec.label, out.trim())
}

pub(crate) fn prepare_runtime_config_with(
    spec: VariantSpec,
    config_path: &Path,
    endpoint_resolve: bool,
    collect_ipv4s: fn(&Value) -> Vec<String>,
    resolve_servers: fn(&mut Value) -> Result<Vec<String>>,
) -> Result<(PathBuf, Vec<String>)> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;

    if !endpoint_resolve {
        return Ok((config_path.to_path_buf(), collect_ipv4s(&value)));
    }

    let endpoint_escape_ips = resolve_servers(&mut value)?;
    let runtime_path = config_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join("tmp/config.resolved.json");
    write_json_atomic(&runtime_path, &value)
        .with_context(|| format!("write resolved {} config {}", spec.label, runtime_path.display()))?;
    Ok((runtime_path, endpoint_escape_ips))
}

// --- hysteria2 native client config support --------------------------------
//
// A server config.json written in the native hysteria2 client format
// (top-level "server" string, no sing-box "outbounds") is translated in place
// to a sing-box config with a hysteria2 outbound. One-way: after translation
// the file is a regular sing-box config and subsequent runs leave it alone.

fn is_native_hysteria2_config(obj: &Map<String, Value>) -> bool {
    obj.get("server").and_then(Value::as_str).map(str::trim).map(|s| !s.is_empty()).unwrap_or(false)
        && !obj.contains_key("outbounds")
}

pub(crate) fn translate_hysteria2_native_config(spec: VariantSpec, config_path: &Path) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    let Some(obj) = value.as_object() else { return Ok(()) };
    if !is_native_hysteria2_config(obj) { return Ok(()); }
    let translated = hysteria2_native_to_singbox(obj)
        .with_context(|| format!("translate native hysteria2 config {}", config_path.display()))?;
    log::info!("sing-box: translated native hysteria2 config {}", config_path.display());
    write_json_value_if_changed(spec, config_path, &original, &translated)
}

fn hysteria2_native_to_singbox(obj: &Map<String, Value>) -> Result<Value> {
    let server_raw = obj.get("server").and_then(Value::as_str).map(str::trim).unwrap_or("");
    if server_raw.is_empty() { bail!("hysteria2 config requires server"); }
    let (server, server_port) = parse_hysteria2_server_address(server_raw)?;

    let mut outbound = Map::new();
    outbound.insert("type".into(), Value::String("hysteria2".into()));
    outbound.insert("tag".into(), Value::String("proxy".into()));
    outbound.insert("server".into(), Value::String(server.clone()));
    outbound.insert("server_port".into(), Value::from(server_port));

    // auth: plain password string; object form ({type,password}) also accepted.
    let password = match obj.get("auth") {
        Some(Value::String(s)) => Some(s.clone()),
        Some(Value::Object(o)) => o.get("password").and_then(Value::as_str).map(ToOwned::to_owned),
        _ => None,
    };
    if let Some(p) = password { if !p.is_empty() { outbound.insert("password".into(), Value::String(p)); } }

    // bandwidth: "100 mbps" / number -> up_mbps/down_mbps ints; absent -> BBR.
    if let Some(bw) = obj.get("bandwidth").and_then(Value::as_object) {
        for (src, dst) in [("up", "up_mbps"), ("down", "down_mbps")] {
            match bw.get(src) {
                Some(Value::Number(n)) => { if let Some(u) = n.as_u64() { if u > 0 { outbound.insert(dst.into(), Value::from(u)); } } }
                Some(Value::String(s)) => { if let Some(m) = parse_hysteria2_bandwidth_mbps(s) { outbound.insert(dst.into(), Value::from(m)); } }
                _ => {}
            }
        }
    }

    if let Some(obfs) = obj.get("obfs").and_then(Value::as_object) {
        let otype = obfs.get("type").and_then(Value::as_str).unwrap_or("");
        if otype.eq_ignore_ascii_case("salamander") {
            let pw = obfs.get("salamander").and_then(Value::as_object).and_then(|s| s.get("password")).and_then(Value::as_str).unwrap_or("");
            if !pw.is_empty() { outbound.insert("obfs".into(), serde_json::json!({"type": "salamander", "password": pw})); }
        }
    }

    // hysteria2 transport always requires TLS.
    let native_tls = obj.get("tls").and_then(Value::as_object);
    let mut tls = Map::new();
    tls.insert("enabled".into(), Value::Bool(true));
    let mut server_name = native_tls.and_then(|t| t.get("sni")).and_then(Value::as_str).unwrap_or("").trim().to_string();
    if server_name.is_empty() && server.parse::<std::net::IpAddr>().is_err() { server_name = server.clone(); }
    if !server_name.is_empty() { tls.insert("server_name".into(), Value::String(server_name)); }
    if native_tls.and_then(|t| t.get("insecure")).and_then(Value::as_bool).unwrap_or(false) { tls.insert("insecure".into(), Value::Bool(true)); }
    if let Some(alpn) = native_tls.and_then(|t| t.get("alpn")).and_then(Value::as_array) {
        let list: Vec<Value> = alpn.iter().filter_map(Value::as_str).map(|s| Value::String(s.to_string())).collect();
        if !list.is_empty() { tls.insert("alpn".into(), Value::Array(list)); }
    }
    outbound.insert("tls".into(), Value::Object(tls));

    if obj.get("fastOpen").and_then(Value::as_bool).unwrap_or(false) { outbound.insert("tcp_fast_open".into(), Value::Bool(true)); }
    if native_tls.map(|t| t.contains_key("ca") || t.contains_key("pinSHA256")).unwrap_or(false) { log::warn!("sing-box: hysteria2 tls.ca/pinSHA256 have no sing-box equivalent, ignored"); }
    if obj.contains_key("masquerade") { log::warn!("sing-box: hysteria2 masquerade has no sing-box client equivalent, ignored"); }

    Ok(serde_json::json!({
        "log": {"level": "info", "timestamp": true},
        "outbounds": [Value::Object(outbound), {"type": "direct", "tag": "direct"}]
    }))
}

fn parse_hysteria2_server_address(raw: &str) -> Result<(String, u16)> {
    // host:port / [v6]:port; default port 443 (native hysteria2 default).
    let s = raw.trim();
    if let Some(rest) = s.strip_prefix('[') {
        let end = rest.find(']').ok_or_else(|| anyhow::anyhow!("bad server address: {raw}"))?;
        let host = &rest[..end];
        let port = rest[end + 1..].strip_prefix(':').and_then(|p| p.parse::<u16>().ok()).unwrap_or(443);
        if host.is_empty() { bail!("bad server address: {raw}"); }
        return Ok((host.to_string(), port));
    }
    match s.rsplit_once(':') {
        Some((h, p)) if !h.contains(':') => {
            if h.is_empty() { bail!("bad server address: {raw}"); }
            let port = p.parse::<u16>().ok().filter(|p| *p > 0).ok_or_else(|| anyhow::anyhow!("bad server port in {raw}"))?;
            Ok((h.to_string(), port))
        }
        _ => {
            if s.parse::<std::net::Ipv6Addr>().is_ok() { return Ok((s.to_string(), 443)); }
            if s.contains(':') { bail!("bad server address: {raw}"); }
            Ok((s.to_string(), 443))
        }
    }
}

fn parse_hysteria2_bandwidth_mbps(raw: &str) -> Option<u64> {
    let s = raw.trim().to_ascii_lowercase();
    let num: String = s.chars().take_while(|c| c.is_ascii_digit() || *c == '.').collect();
    if num.is_empty() { return None; }
    let base: f64 = num.parse().ok()?;
    let rest = s[num.len()..].replace(' ', "");
    let mbps = if rest.starts_with("gbps") || rest == "g" { base * 1000.0 }
        else if rest.starts_with("kbps") || rest == "k" { base / 1000.0 }
        else if rest.starts_with("bps") || rest == "b" { base / 1_000_000.0 }
        else { base }; // native unit default is mbps
    // sing-box takes integer mbps; sub-mbps values are omitted (BBR fallback)
    // rather than inflated to 1 mbps.
    if mbps >= 1.0 { Some(mbps.round() as u64) } else { None }
}
