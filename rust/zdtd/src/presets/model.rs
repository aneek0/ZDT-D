//! Data model for a parsed NFQWS2 preset.

/// One physical line of a profile, classified.
#[derive(Debug, Clone, PartialEq)]
pub enum Segment {
    /// Blank line (only whitespace).
    Blank,
    /// Comment (`#` or `;`).
    Comment(String),
    /// `--name=`, `--skip`, `--new`, and other non-match non-strategy directives.
    Directive(String),
    /// Match-scope line: filters, hostlists, ipsets.
    Match {
        family: MatchFamily,
        line: String,
    },
    /// Strategy-scope line emitted before the first `--lua-desync` of the
    /// profile: `--payload=`, `--in-range`, `--out-range`.
    StrategyFilter {
        line: String,
    },
    /// `--lua-desync=` strategy line.
    Strategy {
        line: String,
    },
}

impl Segment {
    pub fn text(&self) -> &str {
        match self {
            Segment::Blank => "",
            Segment::Comment(s) | Segment::Directive(s) => s,
            Segment::Match { line, .. } | Segment::StrategyFilter { line } | Segment::Strategy { line } => line,
        }
    }
}

/// Match-line family (mirrors zapretgui `_add_match_line`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MatchFamily {
    FilterTcp,
    FilterUdp,
    FilterL7,
    FilterOther,
    Hostlist,
    HostlistExclude,
    HostlistDomains,
    HostlistAuto,
    Ipset,
    IpsetExclude,
    InlineIpset,
    Other,
}

impl MatchFamily {
    pub fn classify(line: &str) -> MatchFamily {
        if line.starts_with("--filter-tcp") {
            MatchFamily::FilterTcp
        } else if line.starts_with("--filter-udp") {
            MatchFamily::FilterUdp
        } else if line.starts_with("--filter-l7") {
            MatchFamily::FilterL7
        } else if line.starts_with("--filter-") {
            MatchFamily::FilterOther
        } else if line.starts_with("--hostlist-exclude") {
            MatchFamily::HostlistExclude
        } else if line.starts_with("--hostlist-domains") {
            MatchFamily::HostlistDomains
        } else if line.starts_with("--hostlist-auto") {
            MatchFamily::HostlistAuto
        } else if line.starts_with("--hostlist") {
            MatchFamily::Hostlist
        } else if line.starts_with("--ipset-exclude") {
            MatchFamily::IpsetExclude
        } else if line.starts_with("--ipset-ip") {
            MatchFamily::InlineIpset
        } else if line.starts_with("--ipset") {
            MatchFamily::Ipset
        } else {
            MatchFamily::Other
        }
    }
}

/// A parsed profile.
#[derive(Debug, Clone)]
pub struct Profile {
    /// `--new` / `--new=NAME` line that starts this profile (empty for the
    /// first profile when it begins directly with content).
    pub new_line: Option<String>,
    /// Explicit name from `--new=NAME` or `--name=` (quotes stripped).
    pub name: Option<String>,
    /// True when a `--skip` directive is present.
    pub enabled: bool,
    /// Lines after the `--new` marker, in order.
    pub segments: Vec<Segment>,
}

impl Profile {
    /// Infer a display name (zapretgui semantics, Russian-free English form).
    pub fn display_name(&self) -> String {
        if let Some(n) = &self.name {
            return n.clone();
        }
        let mut parts: Vec<String> = Vec::new();
        let mut tcp = false;
        let mut udp = false;
        let mut l7 = false;
        let mut hostlist: Option<String> = None;
        let mut ipset: Option<String> = None;
        let mut hostlist_count = 0;
        let mut ipset_count = 0;
        let mut complex = false;
        for seg in &self.segments {
            if let Segment::Match { family, line } = seg {
                let value = line.split_once('=').map(|x| x.1).unwrap_or("");
                match family {
                    MatchFamily::FilterTcp => {
                        tcp = true;
                        complex |= udp || l7;
                    }
                    MatchFamily::FilterUdp => {
                        udp = true;
                        complex |= tcp || l7;
                    }
                    MatchFamily::FilterL7 => {
                        l7 = true;
                        complex |= tcp || udp;
                    }
                    MatchFamily::FilterOther => complex = true,
                    MatchFamily::Hostlist | MatchFamily::HostlistExclude | MatchFamily::HostlistDomains | MatchFamily::HostlistAuto => {
                        hostlist_count += 1;
                        hostlist = Some(basename_of(value).to_string());
                    }
                    MatchFamily::Ipset | MatchFamily::IpsetExclude | MatchFamily::InlineIpset => {
                        ipset_count += 1;
                        ipset = Some(basename_of(value).to_string());
                    }
                    MatchFamily::Other => {}
                }
            }
        }
        let mut transport = String::new();
        if tcp {
            transport.push_str("TCP");
        }
        if udp {
            if !transport.is_empty() {
                transport.push_str("+");
            }
            transport.push_str("UDP");
        }
        if l7 {
            if !transport.is_empty() {
                transport.push_str("+");
            }
            transport.push_str("L7");
        }
        if !transport.is_empty() {
            parts.push(transport);
        }
        if complex {
            parts.push("complex match".into());
        } else if hostlist_count == 1 && ipset_count == 0 {
            if let Some(h) = hostlist {
                parts.push(h);
            }
        } else if ipset_count == 1 && hostlist_count == 0 {
            if let Some(i) = ipset {
                parts.push(i);
            }
        } else if hostlist_count + ipset_count > 1 {
            parts.push("multi-list".into());
        }
        if parts.is_empty() {
            "profile".into()
        } else {
            parts.join(" ")
        }
    }

    /// Stable id: `profile:{index}` is assigned by the parser.
    pub fn id(index: usize) -> String {
        format!("profile:{index}")
    }
}

/// Basename of a `@path` or plain `path` value (no Windows separators).
pub fn basename_of(value: &str) -> &str {
    let value = value.trim().trim_start_matches('@').trim_matches(['\'', '"']);
    match value.rsplit_once('/') {
        Some((_, base)) => base,
        None => value,
    }
}
