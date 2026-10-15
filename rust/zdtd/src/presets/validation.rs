//! Validation mirroring the strict compiler (`command-builder.sh`):
//! ports, l7 names, option syntax. Fast pre-check so bad edits fail before
//! the file is written; the compiler remains the final authority on device.

/// Port list accepted by nfqws2 source syntax (matches
/// `validate_filter_ports`): `*`, comma-separated single ports, `a-b` ranges,
/// `~` negation, 0..=65535, no empty tokens.
pub fn validate_profile_ports(list: &str) -> Option<()> {
    let list = list.trim();
    if list.is_empty() {
        return None;
    }
    if list == "*" {
        return Some(());
    }
    // Only [0-9,*~-] allowed, no leading/trailing/double commas.
    if !list.chars().all(|c| c.is_ascii_digit() || matches!(c, ',' | '*' | '~' | '-')) {
        return None;
    }
    if list.starts_with(',') || list.ends_with(',') || list.contains(",,") {
        return None;
    }
    for token in list.split(',') {
        if token.is_empty() || token == "*" {
            continue;
        }
        let range = token.strip_prefix('~').unwrap_or(token);
        if range.is_empty() || range.contains('*') {
            return None;
        }
        let (first, last) = match range.split_once('-') {
            Some((a, b)) => {
                if b.contains('-') || a.is_empty() || b.is_empty() {
                    return None;
                }
                (a, b)
            }
            None => (range, range),
        };
        let first: u32 = first.parse().ok()?;
        let last: u32 = last.parse().ok()?;
        if first > last || last > 65535 {
            return None;
        }
    }
    Some(())
}

/// l7 protocol names accepted by nfqws2 1.0.x (upstream `protocol_name[]`).
const L7_NAMES: [&str; 16] = [
    "all", "unknown", "known", "http", "tls", "dtls", "quic", "wireguard", "dht", "discord",
    "stun", "xmpp", "dns", "mtproto", "bt", "utp_bt",
];

/// Validate an l7 filter value: comma-separated known protocol names.
pub fn validate_l7(value: &str) -> Option<()> {
    let value = value.trim();
    if value.is_empty() || value.starts_with(',') || value.ends_with(',') || value.contains(",,") {
        return None;
    }
    if !value.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == ',') {
        return None;
    }
    for token in value.split(',') {
        if !L7_NAMES.contains(&token) {
            return None;
        }
    }
    Some(())
}

/// Validate a strategy line set for `with_profile_strategy`:
/// non-empty `--lua-desync=`, safe option chars, no control characters.
pub fn validate_user_strategy_lines(lines: &[String]) -> Option<()> {
    let mut saw_lua = false;
    for line in lines {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') || t.starts_with(';') {
            continue;
        }
        if !t.starts_with("--") {
            return None;
        }
        if t.chars().any(|c| c.is_control()) {
            return None;
        }
        if t.starts_with("--lua-desync=") {
            if t["--lua-desync=".len()..].trim().is_empty() {
                return None;
            }
            saw_lua = true;
        }
        if t.starts_with("--ipcache") {
            return None;
        }
    }
    if !saw_lua {
        return None;
    }
    Some(())
}


/// Windows-forbidden filename sanitization for user list files
/// (zapretgui `safe_list_file_name`).
pub fn safe_list_file_name(name: &str) -> Option<String> {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.chars().any(|c| matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*')) {
        return None;
    }
    if trimmed.ends_with('.') || trimmed.ends_with(' ') {
        return None;
    }
    const RESERVED: [&str; 22] = [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
        "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];
    let stem = trimmed.strip_suffix(".txt").unwrap_or(trimmed);
    if RESERVED.contains(&stem.to_ascii_uppercase().as_str()) {
        return None;
    }
    if stem.chars().any(|c| (c as u32) < 0x20) {
        return None;
    }
    Some(trimmed.to_string())
}

/// Domain validation for hostlist entries (zapretgui `validate_domain`).
pub fn validate_domain(domain: &str) -> Option<()> {
    let mut d = domain.trim().to_ascii_lowercase();
    // Leading wildcards/dots are stripped before validation.
    while let Some(rest) = d.strip_prefix('^') {
        d = rest.to_string();
    }
    while let Some(rest) = d.strip_prefix("*.") {
        d = rest.to_string();
    }
    while let Some(rest) = d.strip_prefix('.') {
        d = rest.to_string();
    }
    while let Some(rest) = d.strip_suffix('.') {
        d = rest.to_string();
    }
    if d.is_empty() {
        return None;
    }
    if d.contains("://") || d.contains(':') {
        return None;
    }
    // Reject IP literals.
    if d.split('.').all(|octet| octet.parse::<u8>().is_ok()) {
        return None;
    }
    if d.len() > 253 {
        return None;
    }
    for label in d.split('.') {
        if label.is_empty() || label.len() > 63 {
            return None;
        }
        let mut chars = label.chars();
        let first = chars.next()?;
        let last = label.chars().last()?;
        if !(first.is_ascii_alphanumeric()) || !(last.is_ascii_alphanumeric()) {
            return None;
        }
        if !label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
            return None;
        }
    }
    let tld = d.split('.').last()?;
    if tld.chars().next().is_some_and(|c| c.is_ascii_digit()) {
        return None;
    }
    Some(())
}

/// Validate one ipset entry: ip or ip_network (non-strict host bits allowed).
pub fn validate_ip_entry(entry: &str) -> Option<()> {
    let e = entry.trim();
    if e.is_empty() || e.contains(char::is_whitespace) {
        return None;
    }
    if let Ok(ip) = e.parse::<std::net::IpAddr>() {
        let _ = ip;
        return Some(());
    }
    // CIDR: split host part off, parse base address and prefix.
    let (base, prefix) = e.split_once('/')?;
    if prefix.is_empty() || !prefix.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let prefix: u32 = prefix.parse().ok()?;
    let base: std::net::IpAddr = base.parse().ok()?;
    let max = if base.is_ipv4() { 32 } else { 128 };
    if prefix > max {
        return None;
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ports_valid_and_invalid() {
        assert!(validate_profile_ports("*").is_some());
        assert!(validate_profile_ports("80,443").is_some());
        assert!(validate_profile_ports("443-65535").is_some());
        assert!(validate_profile_ports("~80").is_some());
        assert!(validate_profile_ports("80-443,1000").is_some());
        assert!(validate_profile_ports("").is_none());
        assert!(validate_profile_ports("99999").is_none());
        assert!(validate_profile_ports("443-80").is_none());
        assert!(validate_profile_ports("80,,443").is_none());
        assert!(validate_profile_ports(",80").is_none());
        assert!(validate_profile_ports("a80").is_none());
        assert!(validate_profile_ports("~*").is_none());
    }

    #[test]
    fn l7_valid_and_invalid() {
        assert!(validate_l7("tls").is_some());
        assert!(validate_l7("stun,discord").is_some());
        assert!(validate_l7("all").is_some());
        assert!(validate_l7("http").is_some());
        assert!(validate_l7("ssh").is_none());
        assert!(validate_l7("").is_none());
        assert!(validate_l7("tls,").is_none());
        assert!(validate_l7("tls,,http").is_none());
    }

    #[test]
    fn strategy_lines_valid_and_invalid() {
        assert!(validate_user_strategy_lines(&["--lua-desync=pass".into()]).is_some());
        assert!(validate_user_strategy_lines(&["--lua-desync=".into()]).is_none());
        assert!(validate_user_strategy_lines(&["--payload=quic".into()]).is_none()); // no lua
        assert!(validate_user_strategy_lines(&["--ipcache=1".into(), "--lua-desync=pass".into()]).is_none());
        assert!(validate_user_strategy_lines(&[]).is_none());
    }

    #[test]
    fn safe_list_names() {
        assert!(safe_list_file_name("custom").is_some());
        assert!(safe_list_file_name("custom.txt").is_some());
        assert!(safe_list_file_name("CON").is_none());
        assert!(safe_list_file_name("con.txt").is_none());
        assert!(safe_list_file_name("a/b").is_none());
        assert!(safe_list_file_name("name.").is_none());
        assert!(safe_list_file_name("").is_none());
    }

    #[test]
    fn domains_valid_and_invalid() {
        assert!(validate_domain("youtube.com").is_some());
        assert!(validate_domain("*.youtube.com").is_some());
        assert!(validate_domain("a.b.example.org").is_some());
        assert!(validate_domain("1.2.3.4").is_none());
        assert!(validate_domain("http://x.com").is_none());
        assert!(validate_domain("x.com:80").is_none());
        assert!(validate_domain("-bad.com").is_none());
        assert!(validate_domain("bad-.com").is_none());
        assert!(validate_domain("com.1").is_none());
        assert!(validate_domain("").is_none());
    }

    #[test]
    fn ip_entries() {
        assert!(validate_ip_entry("1.2.3.4").is_some());
        assert!(validate_ip_entry("10.0.0.0/8").is_some());
        assert!(validate_ip_entry("2001:db8::/32").is_some());
        assert!(validate_ip_entry("::1").is_some());
        assert!(validate_ip_entry("1.2.3.4/33").is_none());
        assert!(validate_ip_entry("1.2.3").is_none());
        assert!(validate_ip_entry("").is_none());
        assert!(validate_ip_entry("1.2.3.4/").is_none());
    }
}
