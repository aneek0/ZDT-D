//! Line-oriented preset parser (zapretgui semantics, nfqws2 engine family).

use super::model::{basename_of, MatchFamily, Profile, Segment};

/// Fully parsed preset: header, preamble, profiles, footer.
#[derive(Debug, Clone)]
pub struct ParsedPreset {
    /// Leading comment/blank lines before the first non-comment body line.
    pub header: Vec<String>,
    /// Global lines before the first profile (lua-init, blobs, comments, blanks).
    pub preamble: Vec<String>,
    /// Profiles in file order.
    pub profiles: Vec<Profile>,
    /// Trailing lines after the last profile that carry no profile content.
    pub footer: Vec<String>,
}

/// A line "looks like" profile content (start of the first profile when the
/// file opens without `--new`).
fn looks_like_profile_line(line: &str) -> bool {
    let l = line.trim();
    if l.is_empty() || l.starts_with('#') || l.starts_with(';') {
        return false;
    }
    l.starts_with("--match")
        || l.starts_with("--directive")
        || l.starts_with("--filter-")
        || l.starts_with("--hostlist")
        || l.starts_with("--ipset")
        || l.starts_with("--name")
        || l.starts_with("--skip")
        || l.starts_with("--payload")
        || l.starts_with("--in-range")
        || l.starts_with("--out-range")
        || l.starts_with("--lua-desync")
}

fn is_comment(l: &str) -> bool {
    l.trim().starts_with('#') || l.trim().starts_with(';')
}

/// Parse preset text (CRLF/CR normalized to LF by caller or here).
pub fn parse_preset(source: &str) -> ParsedPreset {
    let normalized = source.trim_start_matches('\u{feff}').replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.split('\n').collect();
    let mut lines = lines;
    while lines.last().is_some_and(|l| l.trim().is_empty()) {
        lines.pop();
    }

    // Header: leading comment/blank lines.
    let header_end = lines
        .iter()
        .position(|l| !l.trim().is_empty() && !is_comment(l))
        .unwrap_or(lines.len());
    let header: Vec<String> = lines[..header_end].iter().map(|s| s.to_string()).collect();

    // Split rest into preamble + profile chunks + footer.
    let rest = &lines[header_end..];
    let mut profiles: Vec<Profile> = Vec::new();
    let mut preamble: Vec<String> = Vec::new();
    let mut footer: Vec<String> = Vec::new();

    let mut idx = 0;
    // Preamble: lines until the first profile-looking line or --new.
    while idx < rest.len() {
        let line = rest[idx];
        let t = line.trim();
        if t == "--new" || t.starts_with("--new=") {
            break;
        }
        if looks_like_profile_line(t) {
            break;
        }
        preamble.push(line.to_string());
        idx += 1;
    }

    // Profiles.
    let mut current: Option<Vec<String>> = None; // raw body lines of the open profile
    let mut current_new_line: Option<String> = None;
    let mut started = false;

    let flush = |current: &mut Option<Vec<String>>,
                 new_line: &mut Option<String>,
                 profiles: &mut Vec<Profile>| {
        if let Some(body) = current.take() {
            profiles.push(build_profile(new_line.take().unwrap_or_default(), body));
        }
    };

    while idx < rest.len() {
        let line = rest[idx];
        let t = line.trim();
        if t == "--new" || t.starts_with("--new=") {
            flush(&mut current, &mut current_new_line, &mut profiles);
            current_new_line = Some(t.to_string());
            current = Some(Vec::new());
            started = true;
            idx += 1;
            continue;
        }
        if !started {
            // First profile without --new: begins at the first profile-looking line.
            if looks_like_profile_line(t) {
                current = Some(Vec::new());
                current_new_line = None;
                started = true;
                // fall through: push this line below
            } else {
                // Non-profile line before any profile: keep in footer region.
                preamble.push(line.to_string());
                idx += 1;
                continue;
            }
        }
        if current.is_none() {
            footer.push(line.to_string());
        } else {
            current.as_mut().unwrap().push(line.to_string());
        }
        idx += 1;
    }
    flush(&mut current, &mut current_new_line, &mut profiles);

    ParsedPreset { header, preamble, profiles, footer }
}

/// Build one profile from its `--new` line and body lines.
fn build_profile(new_line: String, body: Vec<String>) -> Profile {
    let mut name: Option<String> = None;
    // --new=NAME provides the name directly.
    if let Some(rest) = new_line.strip_prefix("--new=") {
        if !rest.is_empty() {
            name = Some(strip_quotes(rest));
        }
    }
    let mut enabled = true;
    let mut segments: Vec<Segment> = Vec::new();
    let mut saw_strategy = false;

    for raw in body {
        let t = raw.trim();
        if t.is_empty() {
            segments.push(Segment::Blank);
            continue;
        }
        if is_comment(t) {
            segments.push(Segment::Comment(raw.clone()));
            continue;
        }
        if t == "--skip" {
            enabled = false;
            segments.push(Segment::Directive(raw.clone()));
            continue;
        }
        if t.starts_with("--name=") {
            let value = strip_quotes(&t["--name=".len()..]);
            if !value.is_empty() {
                name = Some(value);
            }
            segments.push(Segment::Directive(raw.clone()));
            continue;
        }
        if t.starts_with("--payload") || t.starts_with("--in-range") || t.starts_with("--out-range") {
            if saw_strategy {
                // payload/range after a strategy belongs to a later composite branch.
                segments.push(Segment::Strategy { line: raw.clone() });
            } else {
                segments.push(Segment::StrategyFilter { line: raw.clone() });
            }
            continue;
        }
        if t.starts_with("--lua-desync") {
            saw_strategy = true;
            segments.push(Segment::Strategy { line: raw.clone() });
            continue;
        }
        if looks_like_match_line(t) {
            segments.push(Segment::Match { family: MatchFamily::classify(t), line: raw.clone() });
            continue;
        }
        segments.push(Segment::Directive(raw.clone()));
    }

    Profile { new_line: (!new_line.is_empty()).then(|| new_line.clone()), name, enabled, segments }
}

fn looks_like_match_line(t: &str) -> bool {
    t.starts_with("--filter-")
        || t.starts_with("--hostlist")
        || t.starts_with("--ipset")
        || t.starts_with("--match")
}

fn strip_quotes(s: &str) -> String {
    let s = s.trim();
    if s.len() >= 2
        && ((s.starts_with('"') && s.ends_with('"')) || (s.starts_with('\'') && s.ends_with('\'')))
    {
        s[1..s.len() - 1].to_string()
    } else {
        s.to_string()
    }
}

/// Persistent key used to group identical profiles:
/// `name:NAME` when named, else `sig:LOGICAL-SIGNATURE`.
pub fn profile_persistent_key(p: &Profile) -> String {
    if let Some(n) = &p.name {
        return format!("name:{n}");
    }
    format!("sig:{}", logical_match_signature(p))
}

/// Logical match signature: match-line families with list file names
/// normalized to their stem.
pub fn logical_match_signature(p: &Profile) -> String {
    let mut parts: Vec<String> = Vec::new();
    for seg in &p.segments {
        if let Segment::Match { family, line } = seg {
            let option = line.split_once('=').map(|x| x.0).unwrap_or(line.as_str()).to_string();
            let value = line.split_once('=').map(|x| x.1).unwrap_or("");
            let part = match family {
                MatchFamily::Hostlist | MatchFamily::Ipset | MatchFamily::HostlistExclude | MatchFamily::IpsetExclude => {
                    format!("{option}={}", list_stem(basename_of(value)))
                }
                _ => format!("{option}={}", value.trim().to_ascii_lowercase()),
            };
            parts.push(part);
        }
    }
    parts.sort();
    parts.join("|")
}

/// File stem used in logical keys: lowercase, list extensions and
/// hostlist-/ipset- prefixes stripped (zapretgui `_logical_file_name`).
pub fn list_stem(basename: &str) -> String {
    let mut name = basename.to_ascii_lowercase();
    for ext in [".txt", ".lst", ".list", ".json"] {
        if let Some(stripped) = name.strip_suffix(ext) {
            name = stripped.to_string();
            break;
        }
    }
    for prefix in ["ipset-", "hostlist-"] {
        if let Some(stripped) = name.strip_prefix(prefix) {
            name = stripped.to_string();
            break;
        }
    }
    name
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIRST_PROFILE_NO_NEW: &str = "\
# Preset: demo
# BuiltinVersion: 1

--lua-init=@/x/lua/zapret-lib.lua
--blob=fake_tls:@/x/bin/fake_tls_1.bin

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
    fn parses_first_profile_without_new() {
        let p = parse_preset(FIRST_PROFILE_NO_NEW);
        assert_eq!(p.header.len(), 3);
        assert_eq!(p.preamble.len(), 3); // lua-init, blob, blank
        assert_eq!(p.profiles.len(), 2);
        assert!(p.profiles[0].new_line.is_none());
        assert_eq!(p.profiles[0].name.as_deref(), Some("First"));
        assert_eq!(p.profiles[1].new_line.as_deref(), Some("--new"));
        assert_eq!(p.profiles[1].name.as_deref(), Some("Second"));
    }

    #[test]
    fn skip_disables_profile() {
        let text = "--new\n--name=X\n--skip\n--filter-tcp=80\n--lua-desync=pass\n";
        let p = parse_preset(text);
        assert!(!p.profiles[0].enabled);
    }

    #[test]
    fn payload_after_lua_is_strategy_not_filter() {
        let text = "\
--new
--name=X
--filter-tcp=80
--lua-desync=fake:blob=a
--payload=custom
--lua-desync=fake:blob=b
";
        let p = parse_preset(text);
        let segs = &p.profiles[0].segments;
        // All payload lines in this text come after the first lua: Strategy.
        let as_filter = segs
            .iter()
            .any(|s| matches!(s, Segment::StrategyFilter { line } if line.starts_with("--payload")));
        assert!(!as_filter);
        let after_lua_payload = segs
            .iter()
            .skip_while(|s| !matches!(s, Segment::Strategy { .. }))
            .any(|s| matches!(s, Segment::Strategy { line } if line.starts_with("--payload")));
        assert!(after_lua_payload);
    }

    #[test]
    fn payload_before_lua_is_strategy_filter() {
        let text = "\
--new
--name=X
--filter-udp=443
--payload=quic_initial
--lua-desync=fake:blob=a
";
        let p = parse_preset(text);
        let segs = &p.profiles[0].segments;
        assert!(segs
            .iter()
            .any(|s| matches!(s, Segment::StrategyFilter { line } if line.starts_with("--payload"))));
    }

    #[test]
    fn logical_signature_normalizes_list_stems() {
        let text = "--new\n--name=A\n--hostlist=/data/adb/list/hostlist-YouTube.TXT\n--filter-tcp=80\n--lua-desync=pass\n";
        let p = parse_preset(text);
        assert!(logical_match_signature(&p.profiles[0]).contains("youtube"));
        assert!(logical_match_signature(&p.profiles[0]).contains("hostlist=youtube"));
    }
}
