//! Preset serializer and editing transforms (zapretgui semantics).

use super::model::{MatchFamily, Profile, Segment};
use super::parser::{parse_preset, ParsedPreset};
use super::validation::{validate_profile_ports, validate_user_strategy_lines};

/// Serialize a parsed preset back to text. Round-trip stable for
/// well-formed input: the only changes are trimmed trailing blanks and a
/// final newline.
pub fn serialize_preset(doc: &ParsedPreset) -> String {
    let mut out: Vec<String> = Vec::new();
    out.extend(doc.header.iter().cloned());
    out.extend(doc.preamble.iter().cloned());
    for (i, profile) in doc.profiles.iter().enumerate() {
        let mut body: Vec<&Segment> = profile.segments.iter().collect();
        while body.last().is_some_and(|s| matches!(s, Segment::Blank)) {
            body.pop();
        }
        while body.first().is_some_and(|s| matches!(s, Segment::Blank)) {
            body.remove(0);
        }
        if let Some(new_line) = &profile.new_line {
            out.push(String::new()); // blank separator before --new
            out.push(new_line.clone());
            out.push(String::new());
        } else if i > 0 {
            out.push(String::new()); // safety separator (should not happen)
        }
        for seg in body {
            out.push(seg.text().to_string());
        }
    }
    out.extend(doc.footer.iter().cloned());
    while out.last().is_some_and(|s| s.trim().is_empty()) {
        out.pop();
    }
    format!("{}\n", out.join("\n"))
}

/// Enable/disable a profile by inserting/removing `--skip`.
pub fn set_profile_enabled(source: &str, index: usize, enabled: bool) -> Option<String> {
    let mut doc = parse_preset(source);
    let profile = doc.profiles.get_mut(index)?;
    if enabled {
        profile.segments.retain(|s| !matches!(s, Segment::Directive(d) if d.trim() == "--skip"));
        profile.enabled = true;
    } else {
        if profile.enabled {
            profile.segments.insert(skip_insert_index(profile), Segment::Directive("--skip".into()));
        }
        profile.enabled = false;
    }
    Some(serialize_preset(&doc))
}

/// Insert `--skip` after the `--name` directive (or at the start).
fn skip_insert_index(profile: &Profile) -> usize {
    for (i, seg) in profile.segments.iter().enumerate() {
        if let Segment::Directive(d) = seg {
            if d.trim().starts_with("--name=") {
                return i + 1;
            }
        }
    }
    0
}

/// Replace the strategy of a profile with new lines (plain strategy: same
/// payload/range scope as before). Returns the serialized text.
pub fn with_profile_strategy(source: &str, index: usize, lines: &[String]) -> Option<String> {
    validate_user_strategy_lines(lines)?;
    let mut doc = parse_preset(source);
    let profile = doc.profiles.get_mut(index)?;

    let old_lines: Vec<String> = strategy_lines(profile);
    let replacement = preserve_missing_scope(&old_lines, lines);

    // Remove old strategy + strategy_filter segments.
    profile.segments.retain(|s| !matches!(s, Segment::Strategy { .. } | Segment::StrategyFilter { .. }));
    // Also drop trailing blanks before insertion point.
    while profile.segments.last().is_some_and(|s| matches!(s, Segment::Blank)) {
        profile.segments.pop();
    }

    let insert_at = first_strategy_index(profile);
    let mut insert = replacement;
    if insert.first().map(String::as_str).map(str::trim).is_some_and(|l| !l.is_empty() && l.starts_with("--")) {
        insert.insert(0, String::new());
    }
    for (offset, line) in insert.into_iter().enumerate() {
        profile
            .segments
            .insert(insert_at + offset, classify_strategy_line(&line));
    }
    Some(serialize_preset(&doc))
}

/// Replace the whole strategy block including payload/range scope lines.
pub fn with_profile_whole_strategy(source: &str, index: usize, lines: &[String]) -> Option<String> {
    validate_user_strategy_lines(lines)?;
    let mut doc = parse_preset(source);
    let profile = doc.profiles.get_mut(index)?;

    profile.segments.retain(|s| !matches!(s, Segment::Strategy { .. } | Segment::StrategyFilter { .. }));
    while profile.segments.last().is_some_and(|s| matches!(s, Segment::Blank)) {
        profile.segments.pop();
    }
    // Insert at the first removed position: end of match block.
    let insert_at = profile.segments.len();
    for (offset, line) in lines.to_vec().into_iter().enumerate() {
        profile
            .segments
            .insert(insert_at + offset, classify_strategy_line(line.trim()));
    }
    Some(serialize_preset(&doc))
}

/// Replace user-editable match values: `--name=`, first `--filter-*` line,
/// `--hostlist=`/`--ipset=` lines.
pub fn set_profile_user_match(
    source: &str,
    index: usize,
    name: Option<&str>,
    filter: Option<&str>,
    hostlist: Option<&str>,
    ipset: Option<&str>,
) -> Option<String> {
    if let Some(f) = filter {
        validate_profile_ports(f)?;
    }
    let mut doc = parse_preset(source);
    let profile = doc.profiles.get_mut(index)?;

    if let Some(n) = name {
        profile.name = Some(n.to_string());
        replace_or_insert_directive(profile, "--name=", n);
    }
    if let Some(f) = filter {
        replace_first_filter(profile, f);
    }
    if let Some(h) = hostlist {
        replace_single_match(profile, "--hostlist=", h);
    }
    if let Some(i) = ipset {
        replace_single_match(profile, "--ipset=", i);
    }
    Some(serialize_preset(&doc))
}

/// Append a profile built from template lines (a full profile body starting
/// with `--name=`). `at` is the insertion index; `None` = append at end.
pub fn append_profile(source: &str, template_lines: &[String], at: Option<usize>) -> Option<String> {
    let mut doc = parse_preset(source);
    let body: Vec<Segment> = template_lines
        .iter()
        .map(|l| classify_strategy_line(l.trim()))
        .collect();
    let mut profile = Profile {
        new_line: None,
        name: None,
        enabled: true,
        segments: body,
    };
    if profile.segments.iter().any(|s| matches!(s, Segment::Directive(d) if d.trim() == "--skip")) {
        profile.segments.retain(|s| !matches!(s, Segment::Directive(d) if d.trim() == "--skip"));
    }
    // Ensure a safe default strategy when the template has none.
    if !profile.segments.iter().any(|s| matches!(s, Segment::Strategy { .. })) {
        profile.segments.push(Segment::StrategyFilter { line: "--out-range=-d8".into() });
        profile.segments.push(Segment::Strategy { line: "--lua-desync=pass".into() });
    }
    let at = at.unwrap_or(doc.profiles.len());
    if at > doc.profiles.len() {
        return None;
    }
    profile.new_line = (at > 0).then(|| "--new".to_string());
    doc.profiles.insert(at, profile);
    normalize_new_lines(&mut doc);
    Some(serialize_preset(&doc))
}

/// Delete a profile by index.
pub fn delete_profile(source: &str, index: usize) -> Option<String> {
    let mut doc = parse_preset(source);
    if index >= doc.profiles.len() {
        return None;
    }
    // When deleting the first (no---new) profile, promote the next profile to
    // the "first" position by dropping its `--new` line.
    if index == 0 && doc.profiles.len() > 1 && doc.profiles[0].new_line.is_none() {
        doc.profiles[1].new_line = None;
    }
    doc.profiles.remove(index);
    Some(serialize_preset(&doc))
}

/// Duplicate a profile, giving the copy a unique name.
pub fn duplicate_profile(source: &str, index: usize) -> Option<String> {
    let mut doc = parse_preset(source);
    let original = doc.profiles.get(index)?.clone();
    let base = original.name.clone().unwrap_or_else(|| original.display_name());
    let mut copy = original.clone();
    let mut candidate = format!("{base} copy");
    let mut n = 1;
    while profile_name_taken(&doc, &candidate) {
        n += 1;
        candidate = format!("{base} copy {n}");
    }
    copy.name = Some(candidate.clone());
    copy.enabled = true;
    copy.new_line = Some("--new".to_string());
    replace_or_insert_directive(&mut copy, "--name=", &candidate);
    doc.profiles.insert(index + 1, copy);
    Some(serialize_preset(&doc))
}

/// Move a profile by delta (-1 up, +1 down).
pub fn move_profile(source: &str, index: usize, delta: i32) -> Option<String> {
    let mut doc = parse_preset(source);
    let target = index as i64 + delta as i64;
    if target < 0 || target >= doc.profiles.len() as i64 {
        return None;
    }
    let target = target as usize;
    let profile = doc.profiles.remove(index);
    doc.profiles.insert(target, profile);
    // Keep "first profile has no --new" invariant: ensure exactly the first
    // profile lacks a new_line and all others have one.
    normalize_new_lines(&mut doc);
    Some(serialize_preset(&doc))
}

/// Replace the raw text of one profile. The text must parse as exactly one
/// profile; the current `--new` marker is preserved.
pub fn with_profile_raw_text(source: &str, index: usize, raw: &str) -> Option<String> {
    let text = format!("--new\n{raw}");
    let probe = parse_preset(&text);
    if probe.profiles.len() != 1 {
        return None;
    }
    let mut replacement = probe.profiles.into_iter().next()?;
    let mut doc = parse_preset(source);
    replacement.new_line = doc.profiles.get(index)?.new_line.clone();
    doc.profiles[index] = replacement;
    Some(serialize_preset(&doc))
}

// --- helpers -----------------------------------------------------------

fn strategy_lines(profile: &Profile) -> Vec<String> {
    profile
        .segments
        .iter()
        .filter_map(|s| match s {
            Segment::Strategy { line } | Segment::StrategyFilter { line } => Some(line.clone()),
            _ => None,
        })
        .collect()
}

/// Prepend scope options (`--payload`, `--in-range`, `--out-range`) from the
/// old strategy that the new lines do not provide (zapretgui
/// `_preserve_missing_winws2_strategy_filters`).
fn preserve_missing_scope(old: &[String], new: &[String]) -> Vec<String> {
    let mut prepend: Vec<String> = Vec::new();
    for option in ["--payload", "--in-range", "--out-range"] {
        if new.iter().any(|l| l.trim().starts_with(option)) {
            continue;
        }
        // Last occurrence in old.
        if let Some(last) = old.iter().rev().find(|l| l.trim().starts_with(option)) {
            prepend.push(last.trim().to_string());
        }
    }
    let mut out = prepend;
    out.extend(new.iter().map(|l| l.trim().to_string()));
    out
}

fn first_strategy_index(profile: &Profile) -> usize {
    profile
        .segments
        .iter()
        .position(|s| matches!(s, Segment::Strategy { .. } | Segment::StrategyFilter { .. }))
        .unwrap_or(profile.segments.len())
}

fn classify_strategy_line(line: &str) -> Segment {
    let t = line.trim();
    if t.is_empty() {
        return Segment::Blank;
    }
    if t.starts_with('#') || t.starts_with(';') {
        return Segment::Comment(t.to_string());
    }
    if t.starts_with("--lua-desync") {
        return Segment::Strategy { line: t.to_string() };
    }
    if t.starts_with("--payload") || t.starts_with("--in-range") || t.starts_with("--out-range") {
        return Segment::StrategyFilter { line: t.to_string() };
    }
    if t.starts_with("--filter-") || t.starts_with("--hostlist") || t.starts_with("--ipset") || t.starts_with("--match") {
        return Segment::Match { family: MatchFamily::classify(t), line: t.to_string() };
    }
    Segment::Directive(t.to_string())
}

fn replace_or_insert_directive(profile: &mut Profile, prefix: &str, value: &str) {
    let line = format!("{prefix}{value}");
    for seg in profile.segments.iter_mut() {
        if let Segment::Directive(d) = seg {
            if d.trim().starts_with(prefix) {
                *d = line;
                return;
            }
        }
    }
    let insert_at = skip_insert_index(profile);
    profile.segments.insert(insert_at, Segment::Directive(line));
}

fn replace_first_filter(profile: &mut Profile, value: &str) {
    for seg in profile.segments.iter_mut() {
        if let Segment::Match { family, line } = seg {
            if matches!(family, MatchFamily::FilterTcp | MatchFamily::FilterUdp | MatchFamily::FilterL7 | MatchFamily::FilterOther) {
                let option = line.split_once('=').map(|x| x.0).unwrap_or(line).to_string();
                *line = format!("{option}={value}");
                *family = MatchFamily::classify(line);
                return;
            }
        }
    }
    profile.segments.insert(0, Segment::Match { family: MatchFamily::FilterTcp, line: format!("--filter-tcp={value}") });
}

fn replace_single_match(profile: &mut Profile, prefix: &str, value: &str) {
    let line = format!("{prefix}{value}");
    let mut replaced = false;
    for seg in profile.segments.iter_mut() {
        if let Segment::Match { family, line: l } = seg {
            let matches = (prefix == "--hostlist=" && matches!(family, MatchFamily::Hostlist))
                || (prefix == "--ipset=" && matches!(family, MatchFamily::Ipset));
            if matches {
                *l = line.clone();
                replaced = true;
                break;
            }
        }
    }
    if !replaced {
        let family = if prefix == "--hostlist=" { MatchFamily::Hostlist } else { MatchFamily::Ipset };
        let insert_at = first_strategy_index(profile);
        profile.segments.insert(insert_at, Segment::Match { family, line });
    }
}

fn profile_name_taken(doc: &ParsedPreset, name: &str) -> bool {
    doc.profiles
        .iter()
        .any(|p| p.name.as_deref().map(|n| n.eq_ignore_ascii_case(name)).unwrap_or(false))
}

/// Enforce the "first profile has no `--new` line" file-shape invariant.
fn normalize_new_lines(doc: &mut ParsedPreset) {
    for (i, profile) in doc.profiles.iter_mut().enumerate() {
        if i == 0 {
            profile.new_line = None;
        } else {
            if profile.new_line.is_none() {
                profile.new_line = Some("--new".to_string());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::parser::parse_preset;

    const TWO_PROFILES: &str = "\
--lua-init=@/x/lua/zapret-lib.lua

--name=First
--filter-tcp=80,443
--out-range=-d8
--lua-desync=fake:blob=fake_tls

--new

--name=Second
--filter-udp=443
--payload=quic_initial
--lua-desync=fake:blob=fake_tls
";

    #[test]
    fn roundtrip_stable() {
        let once = serialize_preset(&parse_preset(TWO_PROFILES));
        let twice = serialize_preset(&parse_preset(&once));
        assert_eq!(once, twice);
        assert!(once.starts_with("--lua-init=@/x/lua/zapret-lib.lua"));
        assert_eq!(once.matches("\n--new\n").count(), 1);
        assert!(once.ends_with("--lua-desync=fake:blob=fake_tls\n"));
    }

    #[test]
    fn roundtrip_real_builtin_shape() {
        // Shape of the real builtin preset: header + preamble + many profiles,
        // first without --new, blank line between profiles.
        let text = "\
# Preset: general SIMPLE FAKE
# BuiltinVersion: 2.24

--ctrack-disable=0
--blob=fake_tls:@/x/bin/fake_tls_1.bin

--name=Исключения (RU сайты)
--filter-tcp=80,443
--ipset=/data/adb/modules/ZDT-D/strategic/list/ipset-ru.txt
--out-range=-d8
--payload=tls_client_hello
--lua-desync=pass

--new

--name=Все сайты (айпи)
--filter-tcp=80,443
--out-range=-d8
--lua-desync=fake:blob=tls_google:repeats=6:tcp_ts=-600000

--new

--name=Голосовые звонки/чаты
--filter-l7=stun,discord
--out-range=-n8
--payload=stun,discord_ip_discovery
--lua-desync=fake:blob=fake_default_udp:repeats=6
";
        let out = serialize_preset(&parse_preset(text));
        assert_eq!(parse_preset(&out).profiles.len(), 3);
        assert_eq!(out, text);
    }

    #[test]
    fn skip_toggle_roundtrip() {
        let on = set_profile_enabled(TWO_PROFILES, 0, false).unwrap();
        assert!(on.contains("\n--skip\n"));
        let doc = parse_preset(&on);
        assert!(!doc.profiles[0].enabled);
        assert!(doc.profiles[1].enabled); // two-item independence
        let back = set_profile_enabled(&on, 0, true).unwrap();
        assert!(!back.contains("--skip"));
        // Idempotent enable.
        assert_eq!(back, set_profile_enabled(&back, 0, true).unwrap());
    }

    #[test]
    fn strategy_replace_plain() {
        let lines = vec!["--lua-desync=fake:blob=other".to_string()];
        let out = with_profile_strategy(TWO_PROFILES, 0, &lines).unwrap();
        let doc = parse_preset(&out);
        let p = &doc.profiles[0];
        assert!(strategy_lines(p).iter().any(|l| l.contains("blob=other")));
        // Preserved old scope when not provided.
        assert!(strategy_lines(p).iter().any(|l| l.trim() == "--out-range=-d8"));
    }

    #[test]
    fn strategy_whole_replaces_payload_too() {
        let lines = vec!["--out-range=-n8".to_string(), "--lua-desync=pass".to_string()];
        let out = with_profile_whole_strategy(TWO_PROFILES, 1, &lines).unwrap();
        let doc = parse_preset(&out);
        let p = &doc.profiles[1];
        let lines = strategy_lines(p);
        assert!(lines.iter().any(|l| l.trim() == "--out-range=-n8"));
        assert!(lines.iter().any(|l| l.trim() == "--lua-desync=pass"));
        assert!(!lines.iter().any(|l| l.contains("--payload")));
    }

    #[test]
    fn duplicate_appends_unique_copy() {
        let out = duplicate_profile(TWO_PROFILES, 0).unwrap();
        let doc = parse_preset(&out);
        assert_eq!(doc.profiles.len(), 3);
        assert_eq!(doc.profiles[1].name.as_deref(), Some("First copy"));
        assert!(doc.profiles[2].name.as_deref() != Some("First copy"));
        // Second duplicate gets "copy 2".
        let out2 = duplicate_profile(&out, 0).unwrap();
        assert!(out2.contains("First copy 2"));
    }

    #[test]
    fn delete_first_promotes_second() {
        let out = delete_profile(TWO_PROFILES, 0).unwrap();
        let doc = parse_preset(&out);
        assert_eq!(doc.profiles.len(), 1);
        assert!(doc.profiles[0].new_line.is_none());
        assert_eq!(doc.profiles[0].name.as_deref(), Some("Second"));
        assert!(!out.contains("--new"));
    }

    #[test]
    fn move_up_keeps_first_without_new() {
        let out = move_profile(TWO_PROFILES, 1, -1).unwrap();
        let doc = parse_preset(&out);
        assert_eq!(doc.profiles[0].name.as_deref(), Some("Second"));
        assert!(doc.profiles[0].new_line.is_none());
        assert!(doc.profiles[1].new_line.is_some());
    }

    #[test]
    fn append_and_delete_profile() {
        let template = vec![
            "--name=New".to_string(),
            "--filter-tcp=80".to_string(),
            "--lua-desync=pass".to_string(),
        ];
        let out = append_profile(TWO_PROFILES, &template, None).unwrap();
        let doc = parse_preset(&out);
        assert_eq!(doc.profiles.len(), 3);
        assert_eq!(doc.profiles[2].name.as_deref(), Some("New"));
        assert!(doc.profiles[2].new_line.is_some());
        // Append as first profile.
        let first = append_profile(TWO_PROFILES, &template, Some(0)).unwrap();
        let doc2 = parse_preset(&first);
        assert_eq!(doc2.profiles[0].name.as_deref(), Some("New"));
        assert!(doc2.profiles[0].new_line.is_none());
    }

    #[test]
    fn raw_text_replace_keeps_new_marker() {
        let raw = "--name=Renamed\n--filter-udp=53\n--lua-desync=pass\n";
        let out = with_profile_raw_text(TWO_PROFILES, 1, raw).unwrap();
        let doc = parse_preset(&out);
        assert_eq!(doc.profiles.len(), 2);
        assert_eq!(doc.profiles[1].name.as_deref(), Some("Renamed"));
        assert!(doc.profiles[1].new_line.is_some());
    }

    #[test]
    fn user_match_edit_updates_name_filter_lists() {
        let out = set_profile_user_match(TWO_PROFILES, 0, Some("Custom"), Some("8080"), None, None).unwrap();
        let doc = parse_preset(&out);
        let p = &doc.profiles[0];
        assert_eq!(p.name.as_deref(), Some("Custom"));
        assert!(p.segments.iter().any(|s| s.text().trim() == "--filter-tcp=8080"));
        let with_list = set_profile_user_match(
            &out,
            0,
            None,
            None,
            Some("/data/adb/modules/ZDT-D/strategic/list/custom.txt"),
            None,
        )
        .unwrap();
        assert!(with_list.contains("--hostlist=/data/adb/modules/ZDT-D/strategic/list/custom.txt"));
    }

    #[test]
    fn invalid_strategy_rejected() {
        let lines = vec!["--lua-desync=".to_string()];
        assert!(with_profile_strategy(TWO_PROFILES, 0, &lines).is_none());
    }

    #[test]
    fn invalid_filter_rejected() {
        assert!(set_profile_user_match(TWO_PROFILES, 0, None, Some("99999"), None, None).is_none());
    }

    #[test]
    fn roundtrip_all_builtin_presets_exact() {
        let dir = std::path::Path::new(
            "/home/aneeko/ZDT-D/module_template/strategic/strategicvar/nfqws2",
        );
        if !dir.is_dir() {
            return; // device build: fixtures not present
        }
        let mut exact = 0;
        let mut total = 0;
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().and_then(|e| e.to_str()) != Some("txt") {
                continue;
            }
            total += 1;
            let raw = std::fs::read_to_string(&path).unwrap();
            let once = serialize_preset(&parse_preset(&raw));
            let twice = serialize_preset(&parse_preset(&once));
            assert_eq!(once, twice, "unstable for {:?}", path);
            let normalized = raw.replace("\r\n", "\n");
            if once == normalized {
                exact += 1;
            }
        }
        // All 98 builtin presets must round-trip byte-exact.
        assert_eq!(exact, total, "{} presets changed on roundtrip", total - exact);
        assert_eq!(total, 98);
    }
}
