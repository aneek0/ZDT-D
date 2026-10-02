/// Live event forwarded from a concurrent probe task to the single writer.
enum ScanEvent<T> {
    Probe { key: String, name: String, target: String, status: String, detail: String, data: Value },
    Progress { status: String, detail: String, data: Value },
    Done(T),
}

type ScanTx<T> = tokio::sync::mpsc::UnboundedSender<ScanEvent<T>>;

fn send_probe<T>(tx: &ScanTx<T>, key: String, name: &str, target: String, status: &str, detail: String, data: Value) {
    let _ = tx.send(ScanEvent::Probe { key, name: name.to_string(), target, status: status.to_string(), detail, data });
}

fn send_progress<T>(tx: &ScanTx<T>, status: &str, detail: String, data: Value) {
    let _ = tx.send(ScanEvent::Progress { status: status.to_string(), detail, data });
}

/// Runs probe tasks with bounded concurrency while forwarding their live
/// events to the writer in arrival order. Each task receives a channel for
/// live events and returns a value delivered to `on_done` on completion.
async fn run_probes_concurrent<W, T, F, Fut, D>(
    writer: &mut W,
    test_id: &str,
    concurrency: usize,
    tasks: Vec<F>,
    mut on_done: D,
) where
    W: EventWriter + Send,
    T: Send + 'static,
    F: FnOnce(ScanTx<T>) -> Fut + Send + 'static,
    Fut: Future<Output = T> + Send + 'static,
    D: FnMut(&mut W, T),
{
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<ScanEvent<T>>();
    let producer = tokio::spawn(async move {
        futures_util::stream::iter(tasks.into_iter().map(|task| {
            let tx = tx.clone();
            async move {
                let result = task(tx.clone()).await;
                let _ = tx.send(ScanEvent::Done(result));
            }
        }))
        .buffer_unordered(concurrency.max(1))
        .collect::<()>()
        .await;
    });
    while let Some(event) = rx.recv().await {
        match event {
            ScanEvent::Probe { key, name, target, status, detail, data } => {
                let _ = writer.probe(test_id, &key, &name, &target, &status, &detail, data);
            }
            ScanEvent::Progress { status, detail, data } => {
                let _ = writer.progress(test_id, &status, &detail, data);
            }
            ScanEvent::Done(value) => on_done(writer, value),
        }
    }
    let _ = producer.await;
}

struct DnsIntegrityOutcome {
    domain: &'static str,
    key: String,
    status: &'static str,
    reason: String,
    compared: bool,
    suspicious: bool,
    fake_local: bool,
    item: Value,
}

async fn check_dns_integrity<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "dns_integrity";
    let title = "DNS interception / substitution";
    let domains: &'static [&'static str] = if options.quick { &DNS_CHECK_DOMAINS[..DNS_CHECK_DOMAINS.len().min(3)] } else { DNS_CHECK_DOMAINS };
    let _ = writer.started(id, title, json!({"domains": domains.len(), "total_probes": domains.len(), "quick": options.quick, "concurrency": options.concurrency}));
    let _ = writer.progress(id, "running", "preparing DNS comparison endpoints", json!({"phase": "bootstrap", "size_label": "DNS query"}));

    let udp_server = DNS_UDP_SERVERS[0];
    let doh_server = DNS_DOH_WIRE_SERVERS[0];
    let mut suspicious = 0usize;
    let mut fake_local = 0usize;
    let mut compared = 0usize;
    let mut details = Vec::new();
    let total = domains.len();
    let timeout_ms = options.timeout_ms;

    let tasks: Vec<_> = domains.iter().enumerate().map(|(index, &domain)| {
        move |tx: ScanTx<DnsIntegrityOutcome>| async move {
            let key = format!("dns-integrity:{domain}");
            send_probe(&tx, key.clone(), "DNS comparison", domain.to_string(), "checking", "comparing UDP DNS with DoH".to_string(), json!({"domain": domain, "phase": "compare", "ordinal": index + 1, "total": total, "size_label": "DNS query"}));
            let timeout_d = Duration::from_millis(timeout_ms);
            let (udp, doh) = tokio::join!(
                resolve_udp(udp_server.0, domain, timeout_d),
                resolve_doh_wire(doh_server.0, domain, timeout_d),
            );
            let mut status = "ok";
            let mut reason = "answers are compatible".to_string();
            let mut compared = false;
            let mut suspicious = false;
            let mut fake_local = false;
            let item = match (&udp, &doh) {
                (Ok(u), Ok(d)) => {
                    compared = true;
                    if u.iter().any(|ip| fake_ip_type(ip).is_some()) {
                        fake_local = true;
                        status = "suspicious";
                        reason = "UDP DNS returned fake/local/provider address".to_string();
                    } else if !answers_overlap(u, d) {
                        suspicious = true;
                        status = "suspicious";
                        reason = "UDP and DoH answers do not overlap".to_string();
                    }
                    json!({"domain": domain, "status": status, "udp": u, "doh": d, "reason": reason})
                }
                (Err(e), Ok(d)) => {
                    suspicious = true;
                    status = "blocked";
                    reason = format!("UDP DNS failed while DoH works: {e}");
                    json!({"domain": domain, "status": status, "udp_error": e.to_string(), "doh": d, "reason": reason})
                }
                (Ok(u), Err(e)) => {
                    status = "partial";
                    json!({"domain": domain, "status": status, "udp": u, "doh_error": e.to_string()})
                }
                (Err(ue), Err(de)) => {
                    status = "failed";
                    json!({"domain": domain, "status": status, "udp_error": ue.to_string(), "doh_error": de.to_string()})
                }
            };
            DnsIntegrityOutcome { domain, key, status, reason, compared, suspicious, fake_local, item }
        }
    }).collect();

    run_probes_concurrent(writer, id, options.concurrency.min(6), tasks, |writer, outcome| {
        compared += outcome.compared as usize;
        suspicious += outcome.suspicious as usize;
        fake_local += outcome.fake_local as usize;
        let probe_status = if outcome.status == "ok" { "available" } else { outcome.status };
        let _ = writer.probe(id, &outcome.key, "DNS comparison", outcome.domain, probe_status, &outcome.reason, json!({"domain": outcome.domain, "status": outcome.status, "size_label": "DNS query"}));
        let _ = writer.progress(id, outcome.status, &outcome.reason, json!({"domain": outcome.domain, "size_label": "DNS/TLS/HTTP"}));
        details.push(outcome.item);
    }).await;

    let risk = if suspicious + fake_local >= 2 { "high" } else if suspicious + fake_local == 1 { "medium" } else { "low" };
    let status = if risk == "low" { "ok" } else { "suspicious" };
    let detail = format!("checked {}, compared {}, suspicious {}, fake/local {}", total, compared, suspicious, fake_local);
    let diagnosis = if risk == "low" { "clean" } else { "possible_dns_block" };
    let _ = writer.result(id, status, &detail, json!({"udp_server": udp_server, "doh_server": doh_server, "items": details, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

#[derive(Clone, Copy)]
enum DnsProbeKind {
    Udp,
    DohWire,
    DohJson,
}

impl DnsProbeKind {
    fn as_str(self) -> &'static str {
        match self {
            DnsProbeKind::Udp => "udp",
            DnsProbeKind::DohWire => "doh_wire",
            DnsProbeKind::DohJson => "doh_json",
        }
    }

    fn label(self) -> &'static str {
        match self {
            DnsProbeKind::Udp => "UDP",
            DnsProbeKind::DohWire => "DoH",
            DnsProbeKind::DohJson => "DoH JSON",
        }
    }

    fn probe_name(self, name: &str) -> String {
        match self {
            DnsProbeKind::Udp => format!("UDP DNS {name}"),
            DnsProbeKind::DohWire => format!("DoH Wire {name}"),
            DnsProbeKind::DohJson => format!("DoH JSON {name}"),
        }
    }

    fn checking_detail(self) -> &'static str {
        match self {
            DnsProbeKind::Udp => "sending UDP DNS queries",
            DnsProbeKind::DohWire => "sending DNS-over-HTTPS wire queries",
            DnsProbeKind::DohJson => "sending DNS-over-HTTPS JSON queries",
        }
    }

    fn size_label(self) -> &'static str {
        match self {
            DnsProbeKind::Udp => "DNS query",
            DnsProbeKind::DohWire | DnsProbeKind::DohJson => "DoH query",
        }
    }

    fn key_prefix(self) -> &'static str {
        match self {
            DnsProbeKind::Udp => "dns-udp",
            DnsProbeKind::DohWire => "doh-wire",
            DnsProbeKind::DohJson => "doh-json",
        }
    }
}

struct DnsAvailabilityOutcome {
    server: &'static str,
    name: &'static str,
    kind: DnsProbeKind,
    key: String,
    probe_name: String,
    count: usize,
    domain_total: usize,
}

async fn check_dns_availability<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "dns_availability";
    let title = "DNS server availability";
    let availability_domains: &'static [&'static str] = if options.quick { &DNS_AVAILABILITY_DOMAINS[..DNS_AVAILABILITY_DOMAINS.len().min(2)] } else { DNS_AVAILABILITY_DOMAINS };
    let servers: Vec<(&'static str, &'static str, DnsProbeKind)> = DNS_UDP_SERVERS.iter().take(if options.quick { 4 } else { DNS_UDP_SERVERS.len() }).map(|&(server, name)| (server, name, DnsProbeKind::Udp))
        .chain(DNS_DOH_WIRE_SERVERS.iter().take(if options.quick { 4 } else { DNS_DOH_WIRE_SERVERS.len() }).map(|&(server, name)| (server, name, DnsProbeKind::DohWire)))
        .chain(DNS_DOH_JSON_SERVERS.iter().take(if options.quick { 3 } else { DNS_DOH_JSON_SERVERS.len() }).map(|&(server, name)| (server, name, DnsProbeKind::DohJson)))
        .collect();
    let dns_total = servers.len();
    let _ = writer.started(id, title, json!({"domains": availability_domains.len(), "total_probes": dns_total, "quick": options.quick, "concurrency": options.concurrency}));
    let _ = writer.progress(id, "running", "preparing DNS availability sweep", json!({"phase": "bootstrap", "size_label": "DNS query"}));
    let timeout_ms = options.timeout_ms;
    let mut ok = 0usize;
    let mut failed = 0usize;
    let mut rows = Vec::new();

    let tasks: Vec<_> = servers.into_iter().map(|(server, name, kind)| {
        move |tx: ScanTx<DnsAvailabilityOutcome>| async move {
            let key = format!("{}:{server}", kind.key_prefix());
            let probe_name = kind.probe_name(name);
            send_probe(&tx, key.clone(), &probe_name, server.to_string(), "checking", kind.checking_detail().to_string(), json!({"server": server, "kind": kind.as_str(), "size_label": kind.size_label()}));
            let timeout_d = Duration::from_millis(timeout_ms);
            let count = match kind {
                DnsProbeKind::Udp => probe_dns_udp_many(server, availability_domains, timeout_d).await,
                DnsProbeKind::DohWire => probe_doh_wire_many(server, availability_domains, timeout_d).await,
                DnsProbeKind::DohJson => probe_doh_json_many(server, availability_domains, timeout_d).await,
            };
            DnsAvailabilityOutcome { server, name, kind, key, probe_name, count, domain_total: availability_domains.len() }
        }
    }).collect();

    run_probes_concurrent(writer, id, options.concurrency.min(12), tasks, |writer, outcome| {
        let status = if outcome.count > 0 { ok += 1; "ok" } else { failed += 1; "blocked" };
        let kind = outcome.kind;
        let detail = format!("{} {} ({}) answered {}/{} domains", kind.label(), outcome.server, outcome.name, outcome.count, outcome.domain_total);
        rows.push(json!({"server": outcome.server, "name": outcome.name, "kind": kind.as_str(), "ok_domains": outcome.count, "status": status}));
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe(id, &outcome.key, &outcome.probe_name, outcome.server, probe_status, &detail, json!({"server": outcome.server, "kind": kind.as_str(), "ok_domains": outcome.count, "size_label": kind.size_label()}));
        let _ = writer.progress(id, status, &detail, json!({"server": outcome.server, "kind": kind.as_str(), "size_label": kind.size_label()}));
    }).await;

    let risk = if ok == 0 { "high" } else if failed > ok { "medium" } else { "low" };
    let status = if ok == 0 { "blocked" } else if failed > 0 { "partial" } else { "ok" };
    let detail = format!("available DNS endpoints: {ok}, unavailable: {failed}");
    let diagnosis = if ok == 0 { "possible_dns_block" } else if failed > 0 { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "failed": failed, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

struct DomainOutcome {
    domain: String,
    key: String,
    status: &'static str,
    diagnosis: &'static str,
    dns: String,
    tls12: String,
    tls13: String,
    http: String,
    https: String,
    checks: Vec<Value>,
}

async fn probe_domain_checks(domain: String, timeout_ms: u64, proxy: Option<String>, tx: &ScanTx<DomainOutcome>) -> DomainOutcome {
    let key = format!("domain:{domain}");
    let mut checks: Vec<Value> = Vec::new();
    send_progress(tx, "running", format!("checking {domain}"), json!({"domain": domain, "size_label": "DNS/TLS/HTTP"}));
    send_probe(tx, key.clone(), "Domain reachability", domain.clone(), "checking", "starting DNS/TLS/HTTP checks".to_string(), json!({"domain": domain, "size_label": "DNS/TLS/HTTP", "checks": checks}));

    let timeout_d = Duration::from_millis(timeout_ms);
    let dns = resolve_system(&domain, timeout_d).await;
    let dns_status = if is_ip_address(&dns) {
        match fake_ip_type(&dns) {
            Some("fakeip") | Some("local") | Some("isp") => "suspicious",
            _ => "available",
        }
    } else if dns == "empty" || dns.contains("nxdomain") || dns.contains("dns_fail") {
        "blocked"
    } else {
        "suspicious"
    };
    let dns_detail = if dns_status == "available" { format!("resolved to {dns}") } else { format!("DNS result: {dns}") };
    checks.push(json!({"name": "DNS", "status": dns_status, "detail": dns_detail, "value": dns, "size_label": "DNS query"}));
    send_probe(tx, key.clone(), "Domain reachability", domain.clone(), "checking", "DNS check completed, checking TLS and HTTP".to_string(), json!({"domain": domain, "dns": dns, "size_label": "DNS/TLS/HTTP", "checks": checks}));

    let client = http_client(timeout_d, false, proxy.as_deref());
    let http_url = format!("http://{domain}/");
    let https_url = format!("https://{domain}/");
    let (tls13, tls12, http, https) = tokio::join!(
        tls_http_probe(&domain, reqwest::tls::Version::TLS_1_3, timeout_d, proxy.as_deref()),
        tls_http_probe(&domain, reqwest::tls::Version::TLS_1_2, timeout_d, proxy.as_deref()),
        http_status_probe(&client, &http_url, timeout_d),
        http_status_probe(&client, &https_url, timeout_d),
    );
    let tls13_status = classify_probe_status(&tls13);
    checks.push(json!({"name": "TLS 1.3", "status": tls13_status, "detail": format_probe_detail(&tls13), "value": compact(&tls13), "size_label": "TLS handshake"}));
    let tls12_status = classify_probe_status(&tls12);
    checks.push(json!({"name": "TLS 1.2", "status": tls12_status, "detail": format_probe_detail(&tls12), "value": compact(&tls12), "size_label": "TLS handshake"}));
    let http_status = classify_probe_status(&http);
    checks.push(json!({"name": "HTTP", "status": http_status, "detail": format_probe_detail(&http), "value": compact(&http), "size_label": "HTTP HEAD"}));
    let https_status = classify_probe_status(&https);
    checks.push(json!({"name": "HTTPS", "status": https_status, "detail": format_probe_detail(&https), "value": compact(&https), "size_label": "HTTPS HEAD"}));

    let status = classify_domain(&dns, &tls12, &tls13, &http, &https);
    let diagnosis = domain_diagnosis(&dns, &tls12, &tls13, &http, &https, status);
    DomainOutcome { domain, key, status, diagnosis, dns, tls12, tls13, http, https, checks }
}

async fn check_domains<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "domains";
    let title = "Domain DNS/TLS/HTTP availability";
    let mut domains = if options.domains_override.is_empty() {
        load_lines(DOMAINS_TXT, options.max_domains)
    } else {
        options.domains_override.iter().map(|d| clean_hostname(d)).filter(|d| !d.is_empty()).collect()
    };
    domains.truncate(options.max_domains.max(1));
    let _ = writer.started(id, title, json!({"domains": domains.len(), "total_probes": domains.len(), "mode": "dns_tls_http", "concurrency": options.concurrency}));
    let mut ok = 0usize;
    let mut suspicious = 0usize;
    let mut blocked = 0usize;
    let mut rows = Vec::new();
    let timeout_ms = options.timeout_ms;
    let proxy = options.proxy.clone();

    let tasks: Vec<_> = domains.into_iter().map(|domain| {
        let proxy = proxy.clone();
        move |tx: ScanTx<DomainOutcome>| async move {
            let domain = clean_hostname(&domain);
            probe_domain_checks(domain, timeout_ms, proxy, &tx).await
        }
    }).collect();

    run_probes_concurrent(writer, id, options.concurrency.min(16), tasks, |writer, outcome| {
        match outcome.status {
            "ok" => ok += 1,
            "blocked" => blocked += 1,
            _ => suspicious += 1,
        }
        let detail = format!("{}: dns={}, tls12={}, tls13={}, http={}, https={}", outcome.domain, compact(&outcome.dns), compact(&outcome.tls12), compact(&outcome.tls13), compact(&outcome.http), compact(&outcome.https));
        let probe_status = if outcome.status == "ok" { "available" } else { outcome.status };
        let _ = writer.probe(id, &outcome.key, "Domain reachability", &outcome.domain, probe_status, &detail, json!({
            "domain": outcome.domain,
            "dns": outcome.dns,
            "tls12": outcome.tls12,
            "tls13": outcome.tls13,
            "http": outcome.http,
            "https": outcome.https,
            "diagnosis": outcome.diagnosis,
            "size_label": "DNS/TLS/HTTP",
            "checks": outcome.checks
        }));
        let _ = writer.progress(id, outcome.status, &detail, json!({"domain": outcome.domain, "diagnosis": outcome.diagnosis, "size_label": "DNS/TLS/HTTP"}));
        rows.push(json!({"domain": outcome.domain, "status": outcome.status, "diagnosis": outcome.diagnosis, "dns": outcome.dns, "tls12": outcome.tls12, "tls13": outcome.tls13, "http": outcome.http, "https": outcome.https}));
    }).await;

    let risk = if blocked > 3 { "high" } else if suspicious + blocked > 0 { "medium" } else { "low" };
    let status = if blocked > 0 { "blocked" } else if suspicious > 0 { "partial" } else { "ok" };
    let diagnosis = if blocked > 0 { "possible_domain_block" } else if suspicious > 0 { "partial_unavailable" } else { "clean" };
    let detail = format!("domains ok={ok}, suspicious={suspicious}, blocked={blocked}");
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "suspicious": suspicious, "blocked": blocked, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

fn domain_diagnosis(dns: &str, tls12: &str, tls13: &str, http: &str, https: &str, status: &str) -> &'static str {
    let dns_text = compact(dns).to_lowercase();
    let tls12_text = compact(tls12).to_lowercase();
    let tls13_text = compact(tls13).to_lowercase();
    let http_text = compact(http).to_lowercase();
    let https_text = compact(https).to_lowercase();
    let tls_bad = [tls12_text.as_str(), tls13_text.as_str(), https_text.as_str()].iter().any(|v| {
        v.contains("blocked") || v.contains("rst") || v.contains("alert") || v.contains("timeout") || v.contains("tls_") || v.contains("refused") || v.contains("unreachable")
    });
    if dns_text.contains("fake") || dns_text.contains("local") || dns_text.contains("isp") || dns_text.contains("dns_fail") || dns_text.contains("nxdomain") || dns_text.contains("timeout") {
        "possible_dns_block"
    } else if tls_bad {
        "possible_tls_sni_block"
    } else if http_text.contains("451") || http_text.contains("redirect_suspicious") || https_text.contains("451") || https_text.contains("redirect_suspicious") {
        "possible_http_filtering"
    } else if status == "blocked" {
        "blocked_unknown"
    } else if status == "ok" {
        "clean"
    } else {
        "partial_unavailable"
    }
}

async fn check_tcp16<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "tcp16";
    let title = "TCP 12-64KB payload threshold";
    let targets = load_tcp_targets(options.max_tcp_targets);
    let concurrency = options.concurrency.min(8);
    let _ = writer.started(id, title, json!({"targets": targets.len(), "total_probes": targets.len(), "min_kb": TCP_BLOCK_MIN_KB, "max_kb": TCP_BLOCK_MAX_KB, "steps_kb": TCP_PAYLOAD_STEPS_KB, "concurrency": concurrency}));
    let mut ok = 0usize;
    let mut detected = 0usize;
    let mut failed = 0usize;
    let mut rows = Vec::new();
    let proxy = options.proxy.clone();

    let tasks: Vec<_> = targets.into_iter().map(|target| {
        let proxy = proxy.clone();
        move |tx: ScanTx<(TcpTarget, ProbeResult)>| async move {
            let key = format!("tcp16:{}", target.id);
            let target_addr = format!("{}:{}", target.ip, target.port());
            send_probe(&tx, key.clone(), "TCP payload threshold", target_addr, "checking", "measuring RTT and sending increasing X-Pad payloads".to_string(), json!({"target": target.id, "provider": target.provider, "size_label": format!("{}-{} KB", TCP_BLOCK_MIN_KB, TCP_PAYLOAD_STEPS_KB.iter().copied().filter(|kb| *kb <= TCP_BLOCK_MAX_KB).max().unwrap_or(TCP_BLOCK_MAX_KB)), "checks": []}));
            send_progress(&tx, "running", format!("{} {}:{}", target.id, target.ip, target.port()), json!({"target": target.id}));
            let res = tcp_payload_probe_staged(&target, Duration::from_millis(READ_TIMEOUT_MS), proxy.as_deref(), Some((&tx, &key))).await;
            (target, res)
        }
    }).collect();

    run_probes_concurrent(writer, id, concurrency, tasks, |writer, (target, res)| {
        let key = format!("tcp16:{}", target.id);
        let target_addr = format!("{}:{}", target.ip, target.port());
        match res.status.as_str() {
            "ok" => ok += 1,
            "tcp16" | "reset" | "timeout" | "tls_rst" | "tcp_rst" => detected += 1,
            _ => failed += 1,
        }
        let detail = format!("{} {} {}:{} => {} ({})", target.id, target.provider, target.ip, target.port(), res.status, res.detail);
        let probe_status = match res.status.as_str() { "ok" => "available", "tcp16" | "reset" | "timeout" | "tls_rst" | "tcp_rst" => "suspicious", "refused" | "unreachable" => "blocked", other => other };
        let _ = writer.probe(id, &key, "TCP payload threshold", &target_addr, probe_status, &detail, json!({"target": target.id, "bytes": res.bytes, "elapsed_ms": res.elapsed_ms, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb, "size_label": res.size_label.clone(), "checks": res.checks.clone()}));
        let _ = writer.progress(id, &res.status, &detail, json!({"target": target.id, "bytes": res.bytes, "break_kb": res.break_kb}));
        rows.push(json!({"target": target, "status": res.status.clone(), "detail": res.detail.clone(), "bytes": res.bytes, "elapsed_ms": res.elapsed_ms, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb, "checks": res.checks.clone()}));
    }).await;

    let risk = if detected >= 3 { "high" } else if detected > 0 || failed > ok { "medium" } else { "low" };
    let status = if detected > 0 { "suspicious" } else if ok > 0 { "ok" } else { "blocked" };
    let detail = format!("tcp threshold targets ok={ok}, detected={detected}, failed={failed}");
    let diagnosis = if detected > 0 { "possible_tcp_block" } else if failed > ok { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "detected": detected, "failed": failed, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn check_whitelist_sni<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "whitelist_sni";
    let title = "Whitelist SNI probing";
    let targets = load_tcp_targets(if options.quick { 3 } else { 8 });
    let sni_list = load_lines(WHITELIST_SNI_TXT, options.max_sni);
    let sni_total = targets.iter().filter(|t| t.port() == 443).count().saturating_mul(sni_list.len().saturating_add(1));
    let concurrency = options.concurrency.min(12);
    let _ = writer.started(id, title, json!({"targets": targets.len(), "sni": sni_list.len(), "total_probes": sni_total, "phase_model": "baseline_then_whitelist", "concurrency": concurrency}));
    let mut success = 0usize;
    let mut skipped = 0usize;
    let mut rows = Vec::new();
    let proxy = options.proxy.clone();

    // Phase 1: baseline probes for all 443 targets, concurrently.
    let baseline_targets: Vec<TcpTarget> = targets.into_iter().filter(|t| t.port() == 443).collect();
    let baseline_tasks: Vec<_> = baseline_targets.into_iter().map(|target| {
        let proxy = proxy.clone();
        move |tx: ScanTx<(TcpTarget, ProbeResult)>| async move {
            let baseline_key = format!("sni-baseline:{}", target.id);
            let target_addr = format!("{}:{}", target.ip, target.port());
            send_probe(&tx, baseline_key, "Baseline AS/IP probe", target_addr, "checking", "checking whether target is blocked without alternate SNI".to_string(), json!({"target": target.id, "provider": target.provider, "size_label": "TLS connect"}));
            let baseline = tcp_connect_probe(&target, Duration::from_millis(CONNECT_TIMEOUT_MS), proxy.as_deref()).await;
            (target, baseline)
        }
    }).collect();
    let mut blocked_targets: Vec<TcpTarget> = Vec::new();
    run_probes_concurrent(writer, id, concurrency, baseline_tasks, |writer, (target, baseline)| {
        let baseline_key = format!("sni-baseline:{}", target.id);
        let target_addr = format!("{}:{}", target.ip, target.port());
        let baseline_status = match baseline.status.as_str() { "ok" => "available", "timeout" | "reset" | "tls_rst" | "tcp_rst" => "suspicious", other => other };
        let _ = writer.probe(id, &baseline_key, "Baseline AS/IP probe", &target_addr, baseline_status, &baseline.detail, json!({"target": target.id, "status": baseline.status, "rtt_ms": baseline.rtt_ms, "size_label": "TLS connect"}));
        if baseline.status == "ok" {
            skipped += 1;
            rows.push(json!({"target": target.id, "ip": target.ip, "phase": "baseline", "status": "not_blocked", "detail": "baseline target is already reachable"}));
        } else {
            blocked_targets.push(target);
        }
    }).await;

    // Phase 2: SNI sweep for blocked targets only, all pairs concurrently.
    let pairs: Vec<(TcpTarget, String)> = blocked_targets
        .into_iter()
        .flat_map(|target| sni_list.iter().map(move |sni| (target.clone(), sni.clone())))
        .collect();
    let total = pairs.len();
    let sni_tasks: Vec<_> = pairs.into_iter().map(|(target, sni)| {
        let proxy = proxy.clone();
        move |tx: ScanTx<(TcpTarget, String, ProbeResult)>| async move {
            let key = format!("sni:{}:{}", target.id, sni);
            let target_label = format!("{} -> {}", target.ip, sni);
            send_probe(&tx, key, "Whitelist SNI", target_label, "checking", "probing blocked target with alternate SNI".to_string(), json!({"target": target.id, "sni": sni, "size_label": "TLS connect"}));
            let mut probe_target = target.clone();
            probe_target.sni = Some(sni.clone());
            let res = tcp_connect_probe(&probe_target, Duration::from_millis(READ_TIMEOUT_MS), proxy.as_deref()).await;
            (target, sni, res)
        }
    }).collect();
    run_probes_concurrent(writer, id, concurrency, sni_tasks, |writer, (target, sni, res)| {
        let key = format!("sni:{}:{}", target.id, sni);
        let target_label = format!("{} -> {}", target.ip, sni);
        if res.status == "ok" { success += 1; }
        let detail = format!("{} with SNI {} => {} ({})", target.id, sni, res.status, res.detail);
        let probe_status = if res.status == "ok" { "available" } else if res.status == "timeout" || res.status == "reset" { "suspicious" } else { res.status.as_str() };
        let _ = writer.probe(id, &key, "Whitelist SNI", &target_label, probe_status, &detail, json!({"target": target.id, "sni": sni, "rtt_ms": res.rtt_ms, "size_label": "TLS connect"}));
        let _ = writer.progress(id, &res.status, &detail, json!({"target": target.id, "sni": sni, "rtt_ms": res.rtt_ms, "size_label": "TLS connect"}));
        rows.push(json!({"target": target.id, "ip": target.ip, "sni": sni, "status": res.status, "detail": res.detail, "rtt_ms": res.rtt_ms}));
    }).await;

    let risk = if success == 0 && total > 0 { "medium" } else { "low" };
    let status = if success > 0 { "ok" } else if skipped > 0 && total == 0 { "skipped" } else { "not_found" };
    let detail = format!("working SNI combinations {success}/{total}, baseline reachable {skipped}");
    let diagnosis = if success > 0 { "possible_sni_bypass_found" } else if total > 0 { "possible_tls_sni_block" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"success": success, "total": total, "skipped": skipped, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn check_telegram<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "telegram";
    let title = "Telegram DC/download/upload";
    let _ = writer.started(id, title, json!({"dc_count": TELEGRAM_DC_IPS.len(), "total_probes": TELEGRAM_DC_IPS.len() + 2}));

    let dc = telegram_dc_probe(writer).await;
    let download = telegram_download_probe(writer, options.quick).await;
    let upload = telegram_upload_probe(writer, options.quick).await;

    let bad_dc = dc.iter().filter(|d| d["status"] != "ok").count();
    let bad_media = download["status"] != "ok" || upload["status"] != "ok";
    let risk = if bad_dc == TELEGRAM_DC_IPS.len() || (download["status"] == "blocked" && upload["status"] == "blocked") { "high" }
        else if bad_dc > 0 || bad_media { "medium" } else { "low" };
    let status = if risk == "low" { "ok" } else if risk == "high" { "blocked" } else { "partial" };
    let detail = format!("dc blocked {}/{}, download={}, upload={}", bad_dc, TELEGRAM_DC_IPS.len(), download["status"], upload["status"]);
    let diagnosis = if risk == "high" { "possible_telegram_block" } else if risk == "medium" { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"dc": dc, "download": download, "upload": upload, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn telegram_dc_probe<W: EventWriter + Send>(writer: &mut W) -> Vec<Value> {
    let mut rows = Vec::new();
    for (ip, label) in TELEGRAM_DC_IPS {
        let key = format!("telegram-dc:{ip}");
        let _ = writer.probe("telegram", &key, &format!("Telegram {label}"), ip, "checking", "opening TCP connection to Telegram DC", json!({"ip": ip, "label": label, "phase": "dc", "size_label": "TCP connect"}));
    }
    let results = futures_util::future::join_all(TELEGRAM_DC_IPS.iter().map(|(ip, label)| async move {
        let start = Instant::now();
        let addr = format!("{ip}:443");
        let status = match timeout(Duration::from_millis(CONNECT_TIMEOUT_MS), TcpStream::connect(&addr)).await {
            Ok(Ok(stream)) => {
                drop(stream);
                "ok"
            }
            Ok(Err(_)) => "blocked",
            Err(_) => "timeout",
        };
        (*ip, *label, status, start.elapsed().as_millis())
    })).await;
    for (ip, label, status, elapsed) in results {
        let key = format!("telegram-dc:{ip}");
        let detail = format!("Telegram {label} {ip}:443 => {status} ({elapsed}ms)");
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe("telegram", &key, &format!("Telegram {label}"), ip, probe_status, &detail, json!({"ip": ip, "label": label, "rtt_ms": elapsed, "phase": "dc", "size_label": "TCP connect"}));
        let _ = writer.progress("telegram", status, &detail, json!({"ip": ip, "label": label, "rtt_ms": elapsed}));
        rows.push(json!({"ip": ip, "label": label, "status": status, "rtt_ms": elapsed}));
    }
    rows
}

async fn telegram_download_probe<W: EventWriter + Send>(writer: &mut W, quick: bool) -> Value {
    let client = http_client(Duration::from_millis(TOTAL_TIMEOUT_MS), true, options_proxy_none());
    let limit = if quick { 4 * 1024 * 1024 } else { TELEGRAM_MEDIA_LIMIT };
    let _ = writer.probe("telegram", "telegram-download", "Telegram download", TELEGRAM_MEDIA_URL, "checking", "downloading Telegram media test file", json!({"phase":"download", "limit": limit, "size_label": fmt_size(limit as u64)}));
    let start = Instant::now();
    let mut total = 0usize;
    let mut peak = 0usize;
    let mut last_tick = Instant::now();
    let mut tick_bytes = 0usize;
    let mut last_data = Instant::now();
    let mut status = "blocked";
    let result = async {
        let resp = client.get(TELEGRAM_MEDIA_URL).send().await?;
        let mut stream = resp.bytes_stream();
        while let Some(item) = stream.next().await {
            let chunk = item?;
            if chunk.is_empty() { continue; }
            total += chunk.len();
            tick_bytes += chunk.len();
            last_data = Instant::now();
            if last_tick.elapsed() >= Duration::from_millis(1000) {
                peak = peak.max(tick_bytes);
                let avg = total as f64 / start.elapsed().as_secs_f64().max(0.001);
                let _ = writer.progress("telegram", "download", &format!("download {} avg {}/s", fmt_size(total as u64), fmt_size(avg as u64)), json!({"phase":"download", "bytes": total, "avg_bps": avg}));
                tick_bytes = 0;
                last_tick = Instant::now();
            }
            if total >= limit || last_data.elapsed() >= Duration::from_millis(STALL_TIMEOUT_MS) { break; }
        }
        Ok::<(), anyhow::Error>(())
    };
    let _ = timeout(Duration::from_millis(TOTAL_TIMEOUT_MS), result).await;
    let elapsed = start.elapsed().as_secs_f64();
    let avg = total as f64 / elapsed.max(0.001);
    if total == 0 { status = "blocked"; } else if total >= limit * 95 / 100 { status = "ok"; } else if last_data.elapsed() >= Duration::from_millis(STALL_TIMEOUT_MS) { status = "stalled"; } else { status = "slow"; }
    let detail = format!("Telegram download {status}: {} in {:.1}s avg {}/s", fmt_size(total as u64), elapsed, fmt_size(avg as u64));
    let probe_status = match status { "ok" => "available", "slow" | "stalled" => "suspicious", other => other };
    let _ = writer.probe("telegram", "telegram-download", "Telegram download", TELEGRAM_MEDIA_URL, probe_status, &detail, json!({"phase":"download", "bytes": total, "avg_bps": avg, "peak_bps": peak, "size_label": fmt_size(limit as u64)}));
    let _ = writer.progress("telegram", status, &detail, json!({"phase":"download", "bytes": total, "avg_bps": avg, "peak_bps": peak, "size_label": fmt_size(limit as u64)}));
    json!({"status": status, "bytes": total, "duration": elapsed, "avg_bps": avg, "peak_bps": peak})
}

async fn telegram_upload_probe<W: EventWriter + Send>(writer: &mut W, quick: bool) -> Value {
    let size = if quick { 1024 * 1024 } else { TELEGRAM_UPLOAD_SIZE };
    let _ = writer.probe("telegram", "telegram-upload", "Telegram upload", TELEGRAM_UPLOAD_IP, "checking", "uploading Telegram media probe payload", json!({"phase":"upload", "bytes": size, "size_label": fmt_size(size as u64)}));
    let client = http_client(Duration::from_millis(TOTAL_TIMEOUT_MS), true, options_proxy_none());
    let url = format!("https://{TELEGRAM_UPLOAD_IP}:443/upload");
    let body = vec![0u8; size];
    let start = Instant::now();
    let res = timeout(
        Duration::from_millis(TOTAL_TIMEOUT_MS),
        client.post(url).header("Host", "telegram.org").body(body).send(),
    ).await;
    let elapsed = start.elapsed().as_secs_f64();
    let status = match res {
        Ok(Ok(resp)) if resp.status().is_success() || resp.status().is_client_error() || resp.status().is_server_error() => "ok",
        Ok(Ok(_)) => "slow",
        Ok(Err(_)) => "blocked",
        Err(_) => "timeout",
    };
    let avg = size as f64 / elapsed.max(0.001);
    let detail = format!("Telegram upload {status}: {} in {:.1}s avg {}/s", fmt_size(size as u64), elapsed, fmt_size(avg as u64));
    let probe_status = match status { "ok" => "available", "slow" => "suspicious", other => other };
    let _ = writer.probe("telegram", "telegram-upload", "Telegram upload", TELEGRAM_UPLOAD_IP, probe_status, &detail, json!({"phase":"upload", "bytes": size, "avg_bps": avg, "size_label": fmt_size(size as u64)}));
    let _ = writer.progress("telegram", status, &detail, json!({"phase":"upload", "bytes": size, "avg_bps": avg, "size_label": fmt_size(size as u64)}));
    json!({"status": status, "bytes": size, "duration": elapsed, "avg_bps": avg})
}

