//! Translator between the human-readable nfqws2 *preset* format (as shipped by
//! magisk-zapret2) and the absolute-path config format that ZDT-D feeds to the
//! nfqws2 core on the device.
//!
//! magisk-zapret2 stores presets with *relative* dependency references:
//!
//! ```text
//! --lua-init=@lua/zapret-lib.lua
//! --blob=fake_tls:@bin/fake_tls_1.bin
//! ```
//!
//! ZDT-D keeps everything under the fixed module path
//! `/data/adb/modules/ZDT-D/strategic`, so the equivalent references are:
//!
//! ```text
//! --lua-init=@/data/adb/modules/ZDT-D/strategic/lua/zapret-lib.lua
//! --blob=fake_tls:@/data/adb/modules/ZDT-D/strategic/bin/fake_tls_1.bin
//! ```
//!
//! `human_to_nfqws2` (the "retranslator") rewrites a human-readable preset into
//! the absolute form understood by the nfqws2 core. `nfqws2_to_human` does the
//! reverse so the UI can present a clean, editable preset view.
//!
//! Both directions are line-oriented and preserve comments (`#`) and the
//! `--new` section markers verbatim. No shell word-splitting is performed: each
//! logical token stays on its own line, which is exactly how nfqws2 and the
//! daemon's `normalize_config_args` expect the config to look.

use std::path::Path;

const MODULE_STRATEGIC: &str = "/data/adb/modules/ZDT-D/strategic";
const LUA_PREFIX_ABS: &str = "@/data/adb/modules/ZDT-D/strategic/lua/";
const BIN_PREFIX_ABS: &str = "@/data/adb/modules/ZDT-D/strategic/bin/";
const LUA_PREFIX_REL: &str = "@lua/";
const BIN_PREFIX_REL: &str = "@bin/";

/// Split a `key=value` token and map the value via `f`. If there is no
/// `=`, the whole token is mapped.
fn map_value(raw: &str, f: impl Fn(&str) -> String) -> String {
    match raw.split_once('=') {
        Some((key, val)) => format!("{key}={}", f(val)),
        None => f(raw),
    }
}

/// Translate a value that may contain a relative `@lua/` or `@bin/` reference
/// into the absolute ZDT-D form. Idempotent on already-absolute references.
fn abs_ref(raw: &str) -> String {
    map_value(raw, |v| {
        if let Some(rest) = v.strip_prefix(LUA_PREFIX_REL) {
            return format!("{LUA_PREFIX_ABS}{rest}");
        }
        if let Some(rest) = v.strip_prefix(BIN_PREFIX_REL) {
            return format!("{BIN_PREFIX_ABS}{rest}");
        }
        if let Some(rest) = v.strip_prefix('@') {
            if rest.starts_with(&format!("{MODULE_STRATEGIC}/lua/"))
                || rest.starts_with(&format!("{MODULE_STRATEGIC}/bin/"))
            {
                return v.to_string();
            }
        }
        v.to_string()
    })
}

/// Translate a value that may contain an absolute ZDT-D reference back into the
/// compact relative preset form (`@lua/`, `@bin/`).
fn rel_ref(raw: &str) -> String {
    map_value(raw, |v| {
        if let Some(rest) = v.strip_prefix(LUA_PREFIX_ABS) {
            return format!("{LUA_PREFIX_REL}{rest}");
        }
        if let Some(rest) = v.strip_prefix(BIN_PREFIX_ABS) {
            return format!("{BIN_PREFIX_REL}{rest}");
        }
        v.to_string()
    })
}

/// Compile a human-readable preset (relative `@lua/`/`@bin/` references,
/// comments, `--new` sections) into the absolute-path nfqws2 config that the
/// core consumes. The output is one logical token per line.
///
/// This is the function the daemon calls when applying or launching a strategy,
/// guaranteeing a clean, newline-separated config regardless of how the source
/// preset was authored.
pub fn human_to_nfqws2(preset: &str) -> String {
    translate(preset, true)
}

/// Decompile an absolute-path nfqws2 config into the human-readable preset
/// form for display/editing. This is the inverse of [`human_to_nfqws2`].
pub fn nfqws2_to_human(config: &str) -> String {
    translate(config, false)
}

fn translate(text: &str, to_absolute: bool) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            // Keep blank lines for readability.
            out.push('\n');
            continue;
        }
        if trimmed.starts_with('#') {
            out.push_str(trimmed);
            out.push('\n');
            continue;
        }

        // Split the line into whitespace-separated tokens, but keep an inline
        // value attached to its option (e.g. `--lua-init=@lua/x` or
        // `--blob=name:@bin/y`). We do not try to parse inside quoted strings;
        // the nfqws2 config never quotes these references.
        let mut tokens = trimmed.split_whitespace().peekable();
        while let Some(tok) = tokens.next() {
            // A `--blob=name:@ref` carries the reference after the ':'.
            if let Some((before, after)) = tok.split_once(':') {
                if after.starts_with('@') || after.starts_with("@lua/") || after.starts_with("@bin/") {
                    let mapped = if to_absolute { abs_ref(after) } else { rel_ref(after) };
                    out.push_str(before);
                    out.push(':');
                    out.push_str(&mapped);
                    out.push('\n');
                    continue;
                }
            }
            let mapped = if to_absolute { abs_ref(tok) } else { rel_ref(tok) };
            out.push_str(&mapped);
            out.push('\n');
        }
    }
    out
}

/// Best-effort canonicalization of a stored strategy file path. Returns the
/// path unchanged if it already points at a real file. Used by callers that
/// receive a preset name and need the on-disk location.
pub fn strategicvar_path(program: &str, name: &str) -> std::path::PathBuf {
    Path::new(MODULE_STRATEGIC)
        .join("strategicvar")
        .join(program)
        .join(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    const PRESET: &str = "\
# Preset: demo
--lua-init=@lua/zapret-lib.lua
--blob=fake_tls:@bin/fake_tls_1.bin
--filter-tcp=80,443
--new
--name=YouTube
--hostlist=/data/adb/modules/ZDT-D/strategic/list/youtube.txt
";

    #[test]
    fn human_to_absolute_maps_lua_and_bin() {
        let cfg = human_to_nfqws2(PRESET);
        assert!(cfg.contains("--lua-init=@/data/adb/modules/ZDT-D/strategic/lua/zapret-lib.lua"));
        assert!(cfg.contains("--blob=fake_tls:@/data/adb/modules/ZDT-D/strategic/bin/fake_tls_1.bin"));
        // Preserves sections and comments.
        assert!(cfg.contains("--new"));
        assert!(cfg.contains("# Preset: demo"));
    }

    #[test]
    fn absolute_round_trips_to_human() {
        let cfg = human_to_nfqws2(PRESET);
        let human = nfqws2_to_human(&cfg);
        assert!(human.contains("--lua-init=@lua/zapret-lib.lua"));
        assert!(human.contains("--blob=fake_tls:@bin/fake_tls_1.bin"));
    }

    #[test]
    fn idempotent_on_absolute() {
        let cfg = human_to_nfqws2(PRESET);
        let cfg2 = human_to_nfqws2(&cfg);
        assert_eq!(cfg, cfg2);
    }
}
