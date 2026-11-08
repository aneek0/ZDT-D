mod cache;
mod http;
mod server;
pub(crate) mod support;
mod routes;

pub use server::serve;
pub(crate) use support::apps::{any_program_enabled, find_enabled_app_overlaps, EnabledAppList};
use cache::API_SLOW_WARN_THRESHOLD;
use http::{is_authorized, parse_http_request, write_empty_404};

use anyhow::Result;
use serde::Deserialize;
use std::{collections::HashMap, net::TcpStream, time::Instant};

use crate::{
    api_status,
    common::config::deserialize_boolish,
    runtime::daemon,
    runtime::daemon::SharedState,
};

#[derive(Debug, Deserialize)]
struct NewProfileReq {
    program: String,
    #[serde(default)]
    profile: Option<String>,
}



#[derive(Debug, Deserialize)]
struct EnabledReq {
    #[serde(deserialize_with = "deserialize_boolish")]
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct ContentReq {
    content: String,
}

fn handle_programs_subroutes(stream: TcpStream, method: &str, path: &str, headers: &HashMap<String, String>, body: &[u8], services_running: bool) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();

    match seg.get(2).copied() {
        Some("tgwsproxy") => routes::programs::tgwsproxy::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("openvpn") => routes::programs::openvpn::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("amneziawg") => routes::programs::amneziawg::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("tun2socks") => routes::programs::tun2socks::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("myvpn") => routes::programs::myvpn::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("mihomo") => routes::programs::mihomo::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("mieru") => routes::programs::mieru::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("sing-box") => routes::programs::singbox::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("sing-box-lx") => routes::programs::singbox_lx::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("wireproxy") => routes::programs::wireproxy::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("nfqws2") | Some("byedpi") => {
            routes::programs::nfqws::handle(stream, method, seg.as_slice(), headers, body, services_running)
        }
        Some("dnscrypt") => routes::programs::dnscrypt::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("myproxy") => routes::programs::myproxy::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("myprogram") => routes::programs::myprogram::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("tor") => routes::programs::tor::handle(stream, method, seg.as_slice(), headers, body, services_running),
        Some("operaproxy") => routes::programs::operaproxy::handle(stream, method, seg.as_slice(), headers, body, services_running),
        _ => write_empty_404(stream),
    }
}

fn handle_connection(mut stream: TcpStream, state: SharedState) -> Result<()> {
    let request_started = Instant::now();
    let (method, path, headers, body) = parse_http_request(&mut stream)?;

    struct SlowRequestGuard {
        started: Instant,
        method: String,
        path: String,
    }
    impl Drop for SlowRequestGuard {
        fn drop(&mut self) {
            let elapsed = self.started.elapsed();
            if elapsed > API_SLOW_WARN_THRESHOLD {
                log::warn!(
                    "api slow request: method={} path={} duration_ms={}",
                    self.method,
                    self.path,
                    elapsed.as_millis()
                );
            }
        }
    }
    let _slow_request_guard = SlowRequestGuard {
        started: request_started,
        method: method.clone(),
        path: path.clone(),
    };

    let (token, services_running, memory_runtime_state, memory_start_in_progress, memory_stop_in_progress, memory_services_partial) = {
        let st = daemon::lock_state(&state);
        (
            st.token.clone(),
            st.services_running,
            daemon::runtime_state_label(&st).to_string(),
            st.start_in_progress,
            st.stop_in_progress,
            st.services_partial,
        )
    };
    let api_status_snapshot = api_status::read().ok().flatten();
    let runtime_state = api_status::runtime_state_for_api(api_status_snapshot.as_ref(), &memory_runtime_state);
    let start_in_progress = api_status_snapshot
        .as_ref()
        .map(|s| s.start_in_progress)
        .unwrap_or(memory_start_in_progress);
    let stop_in_progress = api_status_snapshot
        .as_ref()
        .map(|s| s.stop_in_progress)
        .unwrap_or(memory_stop_in_progress);
    let services_partial = api_status_snapshot
        .as_ref()
        .map(|s| s.services_partial || s.partial)
        .unwrap_or(memory_services_partial);

    // Only /api/* is exposed. Everything else -> empty 404.
    if !path.starts_with("/api/") {
        return write_empty_404(stream);
    }

    if !is_authorized(&headers, &token) {
        // Hide API from unauthenticated clients: empty 404.
        return write_empty_404(stream);
    }

    // Global subscription library API. Keep this dispatch before `/api/programs/*`:
    // subscriptions are shared resources, not Mihomo program subroutes.
    if path == "/api/subscriptions"
        || path.starts_with("/api/subscriptions/")
        || path == "/api/subscription-links"
        || path.starts_with("/api/subscription-links/")
        || path == "/api/subscription-import"
        || path.starts_with("/api/subscription-import/")
    {
        return routes::subscriptions::handle_subscriptions_subroutes(stream, method.as_str(), path.as_str(), &body);
    }


    // Construction Studio API
    if path.starts_with("/api/construction/") {
        return routes::construction::handle_construction_subroutes(stream, method.as_str(), path.as_str(), &body, services_running);
    }

    // Settings API (typed, safe)
    if method == "GET" && path == "/api/programs" {
        return routes::programs::catalog::handle_get_programs(stream);
    }
    if path.starts_with("/api/programs/") {
        return handle_programs_subroutes(stream, method.as_str(), path.as_str(), &headers, &body, services_running);
    }

    // Strategic folders API (nfqws/nfqws2 shared lists/binaries and nfqws2 lua scripts)
    if path.starts_with("/api/strategic/") {
        return routes::strategic::handle_strategic(stream, method.as_str(), path.as_str(), &headers, &body);
    }

    // Item/list editors under /api/presets/nfqws2/profile/{profile}/… must be
    // matched before the file-level user-preset CRUD (which 404s unknown
    // subpaths).
    if path.starts_with("/api/presets/nfqws2/profile/") {
        return routes::presets::handle_nfqws2_items(stream, method.as_str(), path.as_str(), &body, services_running);
    }
    if path.starts_with("/api/presets/nfqws2") {
        return routes::presets::handle_user_presets(stream, method.as_str(), path.as_str(), &body, services_running);
    }

    // NFQWS2 per-item cutover (legacy global-selection -> per-item lists).
    if method == "GET" && path == "/api/cutover/nfqws2-items" {
        // `pending` must reflect the full decision (marker OR legacy configs),
        // not just the marker state: a fresh install with hand-migrated
        // legacy profiles has no marker yet but still needs the cutover.
        if crate::cutover::is_pending_cutover() {
            return http::write_json(
                stream,
                200,
                serde_json::json!({
                    "ok": true,
                    "state": "pending",
                    "pending_profiles": crate::cutover::pending_profiles(),
                }),
            );
        }
        if let crate::cutover::State::Done(v) = crate::cutover::check() {
            return http::write_json(
                stream,
                200,
                serde_json::json!({"ok": true, "state": "done", "version": v}),
            );
        }
        return http::write_json(
            stream,
            200,
            serde_json::json!({"ok": true, "state": "not_needed", "pending_profiles": []}),
        );
    }
    if method == "POST" && path == "/api/cutover/nfqws2-items/run" {
        let res = (|| -> Result<serde_json::Value> {
            let dir = crate::cutover::run()?;
            Ok(serde_json::json!({
                "ok": true,
                "backup_dir": dir.display().to_string(),
            }))
        })();
        return match res {
            Ok(v) => http::write_json(stream, 200, v),
            Err(e) => http::write_err(stream, e),
        };
    }
    // Strategy variants API (strategic/strategicvar/<program>/*.txt)
    if path.starts_with("/api/strategicvar/") {
        return routes::strategic::handle_strategicvar(stream, method.as_str(), path.as_str(), &body);
    }

    routes::global::handle_global_routes(
        stream,
        method.as_str(),
        path.as_str(),
        body,
        state,
        services_running,
        runtime_state,
        start_in_progress,
        stop_in_progress,
        services_partial,
        api_status_snapshot,
    )
}

