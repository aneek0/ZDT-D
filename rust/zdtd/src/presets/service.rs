//! Filesystem service: profile preset loading/saving and layered list files.

use std::fs;
use std::path::{Path, PathBuf};

use super::parser::parse_preset;
use super::validation::{safe_list_file_name, validate_domain, validate_ip_entry, validate_profile_ports};

/// Load and parse a preset file.
pub fn load_preset(path: &Path) -> std::io::Result<super::parser::ParsedPreset> {
    let text = fs::read_to_string(path)?;
    Ok(parse_preset(&text))
}

/// Save preset text atomically (tmp + rename).
pub fn save_preset(path: &Path, text: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, text)?;
    fs::rename(&tmp, path)
}

/// Directory holding the layered list files:
/// `<root>/base/<name>`, `<root>/user/<name>`, final `<lists_root>/<name>`.
pub struct LayeredLists {
    pub lists_root: PathBuf,
}

pub struct LayerPlan {
    /// Final file path under lists_root.
    pub final_path: PathBuf,
    /// User layer path (None when the list is externally owned).
    pub user_layer: Option<PathBuf>,
    /// Base layer path when present.
    pub base_layer: Option<PathBuf>,
    /// Ownership of the final file.
    pub ownership: Ownership,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ownership {
    /// Managed by layers (base/user merged).
    Managed,
    /// Pre-existing final file with no layers: never touched.
    External,
}

impl LayeredLists {
    pub fn new(lists_root: &Path) -> Self {
        LayeredLists { lists_root: lists_root.to_path_buf() }
    }

    fn base_dir(&self) -> PathBuf {
        self.lists_root.join("list-layers/base")
    }
    fn user_dir(&self) -> PathBuf {
        self.lists_root.join("list-layers/user")
    }

    /// Resolve the layer plan for one list name.
    ///
    /// Ownership: a final file with neither layer is `External` until the
    /// first user save — `write_user` then captures the current final
    /// content into the base layer ("lazy base capture") and the list
    /// becomes `Managed`. This keeps builtin (module-shipped) lists
    /// editable without shipping the extra layer directory, while
    /// `delete_user` restores the captured base content.
    pub fn plan(&self, name: &str) -> Option<LayerPlan> {
        let safe = safe_list_file_name(name)?;
        let final_path = self.lists_root.join(&safe);
        let base = self.base_dir().join(&safe);
        let user = self.user_dir().join(&safe);
        let has_base = base.is_file();
        let has_user = user.is_file();
        let ownership = if !has_base && !has_user && final_path.is_file() {
            Ownership::External
        } else {
            Ownership::Managed
        };
        Some(LayerPlan {
            final_path,
            user_layer: has_user.then_some(user).or(Some(self.user_dir().join(&safe))),
            base_layer: has_base.then_some(base),
            ownership,
        })
    }

    /// Read final list entries (one per line).
    pub fn read_final(&self, name: &str) -> std::io::Result<Vec<String>> {
        let plan = self.plan(name).ok_or_else(|| invalid_name(name))?;
        read_lines(&plan.final_path)
    }

    /// Read the user layer (editable part) of a list.
    pub fn read_user(&self, name: &str) -> std::io::Result<Vec<String>> {
        let plan = self.plan(name).ok_or_else(|| invalid_name(name))?;
        let path = plan.user_layer.as_ref().unwrap();
        read_lines(path)
    }

    /// Write the user layer and rebuild the final file (base + user).
    ///
    /// A final file with no layers yet (builtin/module-shipped list) is
    /// captured into the base layer before the first user write, so the
    /// original entries survive `delete_user`. New lists start empty.
    pub fn write_user(&self, name: &str, entries: &[String], kind: ListKind) -> Result<(), String> {
        let mut plan = self.plan(name).ok_or_else(|| format!("unsafe list name: {name}"))?;
        for entry in entries {
            let ok = match kind {
                ListKind::Hostlist => validate_domain(entry).is_some(),
                ListKind::Ipset => validate_ip_entry(entry).is_some(),
            };
            if !ok {
                return Err(format!("invalid entry: {entry}"));
            }
        }
        // Lazy base capture: builtin final → base layer on first edit.
        if matches!(plan.ownership, Ownership::External) {
            let base = self.base_dir().join(plan.final_path.file_name().unwrap());
            fs::create_dir_all(self.base_dir()).map_err(|e| e.to_string())?;
            fs::copy(&plan.final_path, &base).map_err(|e| e.to_string())?;
            plan.base_layer = Some(base);
            plan.ownership = Ownership::Managed;
        }
        let user = plan.user_layer.as_ref().unwrap();
        if let Some(parent) = user.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut text = String::new();
        for entry in entries {
            text.push_str(entry);
            text.push('\n');
        }
        fs::write(user, text).map_err(|e| e.to_string())?;
        self.rebuild_final(&plan, entries)
    }

    /// Rebuild the final file from layers. An empty user layer gets the
    /// placeholder entry so the file exists for the daemon.
    fn rebuild_final(&self, plan: &LayerPlan, user_entries: &[String]) -> Result<(), String> {
        let placeholder = match plan.final_path.file_name().and_then(|s| s.to_str()) {
            Some(name) if name.starts_with("ipset-") => "123.123.123.123",
            _ => "www.example.com",
        };
        let mut entries: Vec<String> = Vec::new();
        if let Some(base) = &plan.base_layer {
            if let Ok(base_lines) = read_lines(base) {
                entries.extend(base_lines);
            }
        }
        if user_entries.is_empty() && plan.base_layer.is_none() {
            entries.push(placeholder.to_string());
        } else {
            entries.extend(user_entries.iter().cloned());
        }
        // Dedupe while preserving order.
        let mut seen = std::collections::HashSet::new();
        entries.retain(|e| seen.insert(e.clone()));
        let mut text = String::new();
        for entry in &entries {
            text.push_str(entry);
            text.push('\n');
        }
        if entries.is_empty() && plan.base_layer.is_none() && plan.user_layer.is_none() {
            return Ok(());
        }
        // No layers at all and no final file: remove.
        if plan.base_layer.is_none() && user_entries_empty(plan) {
            // user layer file exists (possibly empty) — still write final.
        }
        if let Some(parent) = plan.final_path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        fs::write(&plan.final_path, text).map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Delete the user layer; final is rebuilt from base (restoring the
    /// captured builtin content) or removed when no base exists.
    pub fn delete_user(&self, name: &str) -> Result<(), String> {
        let plan = self.plan(name).ok_or_else(|| format!("unsafe list name: {name}"))?;
        if matches!(plan.ownership, Ownership::External) {
            return Err(format!("list {name} is externally owned"));
        }
        let user = plan.user_layer.as_ref().unwrap();
        let _ = fs::remove_file(user);
        if let Some(base) = &plan.base_layer {
            let base_lines = read_lines(base).map_err(|e| e.to_string())?;
            let mut text = String::new();
            for line in base_lines {
                text.push_str(&line);
                text.push('\n');
            }
            fs::write(&plan.final_path, text).map_err(|e| e.to_string())?;
        } else {
            let _ = fs::remove_file(&plan.final_path);
        }
        Ok(())
    }
}

fn user_entries_empty(plan: &LayerPlan) -> bool {
    plan.user_layer
        .as_ref()
        .map(|p| p.is_file())
        .unwrap_or(false)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ListKind {
    Hostlist,
    Ipset,
}

fn read_lines(path: &Path) -> std::io::Result<Vec<String>> {
    match fs::read_to_string(path) {
        Ok(text) => Ok(text
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty() && !l.starts_with('#'))
            .collect()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

fn invalid_name(name: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("unsafe list name: {name}"))
}

/// Parse profile ports for UI validation (re-export convenience).
pub fn check_ports(list: &str) -> bool {
    validate_profile_ports(list).is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("zdtd-presets-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn managed_list_write_and_read() {
        let root = temp_dir("managed");
        let lists = LayeredLists::new(&root);
        lists
            .write_user("custom", &["youtube.com".into(), "google.com".into()], ListKind::Hostlist)
            .unwrap();
        assert_eq!(lists.read_final("custom").unwrap(), vec!["youtube.com".to_string(), "google.com".to_string()]);
        // Overwrite is authoritative.
        lists.write_user("custom", &["ya.ru".into()], ListKind::Hostlist).unwrap();
        assert_eq!(lists.read_final("custom").unwrap(), vec!["ya.ru".to_string()]);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn builtin_final_captured_to_base_on_first_edit() {
        let root = temp_dir("external");
        fs::write(root.join("existing.txt"), "module-managed\n").unwrap();
        let lists = LayeredLists::new(&root);
        // First save captures the builtin final into the base layer.
        lists.write_user("existing.txt", &["x.com".into()], ListKind::Hostlist).unwrap();
        assert_eq!(
            read_lines(&root.join("list-layers/base/existing.txt")).unwrap(),
            vec!["module-managed".to_string()]
        );
        assert_eq!(
            lists.read_final("existing.txt").unwrap(),
            vec!["module-managed".to_string(), "x.com".to_string()]
        );
        // Deleting the user layer restores the captured builtin content.
        lists.delete_user("existing.txt").unwrap();
        assert_eq!(read_lines(&root.join("existing.txt")).unwrap(), vec!["module-managed".to_string()]);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn base_plus_user_merges_deduped() {
        let root = temp_dir("layers");
        let lists = LayeredLists::new(&root);
        fs::create_dir_all(root.join("list-layers/base")).unwrap();
        fs::write(root.join("list-layers/base/shared"), "a.com\nb.com\n").unwrap();
        lists.write_user("shared", &["b.com".into(), "c.com".into()], ListKind::Hostlist).unwrap();
        assert_eq!(
            lists.read_final("shared").unwrap(),
            vec!["a.com".to_string(), "b.com".to_string(), "c.com".to_string()]
        );
        // Deleting user layer falls back to base content.
        lists.delete_user("shared").unwrap();
        assert_eq!(lists.read_final("shared").unwrap(), vec!["a.com".to_string(), "b.com".to_string()]);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn empty_user_gets_placeholder() {
        let root = temp_dir("placeholder");
        let lists = LayeredLists::new(&root);
        lists.write_user("hostlist-new", &[], ListKind::Hostlist).unwrap();
        assert_eq!(lists.read_final("hostlist-new").unwrap(), vec!["www.example.com".to_string()]);
        lists.write_user("ipset-new", &[], ListKind::Ipset).unwrap();
        assert_eq!(lists.read_final("ipset-new").unwrap(), vec!["123.123.123.123".to_string()]);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn invalid_entries_rejected() {
        let root = temp_dir("invalid");
        let lists = LayeredLists::new(&root);
        let err = lists.write_user("bad", &["http://x.com".into()], ListKind::Hostlist).unwrap_err();
        assert!(err.contains("invalid entry"));
        let err = lists.write_user("bad2", &["1.2.3.4/99".into()], ListKind::Ipset).unwrap_err();
        assert!(err.contains("invalid entry"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn unsafe_names_rejected() {
        let root = temp_dir("unsafe");
        let lists = LayeredLists::new(&root);
        assert!(lists.plan("../escape").is_none());
        assert!(lists.plan("CON").is_none());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn save_and_load_preset_roundtrip() {
        let root = temp_dir("preset");
        let path = root.join("p.txt");
        let text = "--name=X\n--filter-tcp=80\n--lua-desync=pass\n";
        save_preset(&path, text).unwrap();
        let doc = load_preset(&path).unwrap();
        assert_eq!(doc.profiles.len(), 1);
        assert_eq!(doc.profiles[0].name.as_deref(), Some("X"));
        let _ = fs::remove_dir_all(&root);
    }
}
