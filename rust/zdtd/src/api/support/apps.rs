use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{collections::{BTreeMap, BTreeSet}, fs, path::{Path, PathBuf}};

use crate::{common::apps::parse_package_set, settings};
use super::fs::{read_text_or_empty, write_text_atomic};
use super::super::cache::{collect_assignment_files, invalidate_assignment_cache};
use crate::api::routes::programs::shared::{
    amneziawg_profiles_root, mieru_profiles_root, mihomo_profiles_root,
    myprogram_profiles_root, myproxy_profiles_root, myvpn_profiles_root, openvpn_profiles_root,
    program_root, singbox_lx_profiles_root, singbox_profiles_root, tun2socks_profiles_root,
    wireproxy_profiles_root,
};

#[derive(Debug, Clone, Serialize)]
pub(in crate::api) struct AppAssignmentView {
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
    pub(in crate::api) packages: Vec<String>,
}

#[derive(Debug, Clone)]
pub(in crate::api) struct AppAssignmentFile {
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
    pub(in crate::api) fs_path: PathBuf,
    pub(in crate::api) packages: BTreeSet<String>,
}

#[derive(Debug, Clone, Serialize)]
pub(in crate::api) struct AppConflictView {
    pub(in crate::api) package: String,
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
}

#[derive(Debug, Deserialize)]
pub(in crate::api) struct ProxyInfoSaveReq {
    pub(in crate::api) content: String,
    #[serde(default)]
    pub(in crate::api) remove_conflicts: bool,
}

pub(in crate::api) fn slot_from_kind(kind: &str) -> Option<&'static str> {
    match kind {
        "user" => Some("common"),
        "mobile" => Some("mobile"),
        "wifi" => Some("wifi"),
        _ => None,
    }
}

fn push_assignment_file(
    out: &mut Vec<AppAssignmentFile>,
    program_id: &str,
    profile: Option<String>,
    kind: &str,
    fs_path: PathBuf,
    path: String,
) {
    let Some(slot) = slot_from_kind(kind) else { return; };
    let packages = read_package_set_or_empty(&fs_path).unwrap_or_default();
    out.push(AppAssignmentFile {
        program_id: program_id.to_string(),
        profile,
        slot: slot.to_string(),
        path,
        fs_path,
        packages,
    });
}

pub(in crate::api) fn collect_assignment_files_uncached() -> Vec<AppAssignmentFile> {
    let mut out = Vec::new();

    for id in ["nfqws2", "byedpi"] {
        let root = program_root(id);
        if let Ok(rd) = fs::read_dir(&root) {
            for ent in rd.flatten() {
                let path = ent.path();
                if !path.is_dir() { continue; }
                let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
                match id {
                    "nfqws2" => {
                        for kind in ["user", "mobile", "wifi"] {
                            let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
                            push_assignment_file(
                                &mut out,
                                id,
                                Some(profile.clone()),
                                kind,
                                path.join(format!("app/uid/{fname}")),
                                format!("/api/programs/{id}/profiles/{profile}/apps/{kind}"),
                            );
                        }
                    }
                    "byedpi" => {
                        push_assignment_file(
                            &mut out,
                            id,
                            Some(profile.clone()),
                            "user",
                            path.join("app/uid/user_program"),
                            format!("/api/programs/{id}/profiles/{profile}/apps/user"),
                        );
                    }
                    _ => {}
                }
            }
        }
    }

    for kind in ["user", "mobile", "wifi"] {
        let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
        push_assignment_file(
            &mut out,
            "operaproxy",
            None,
            kind,
            program_root("operaproxy").join(format!("app/uid/{fname}")),
            format!("/api/programs/operaproxy/apps/{kind}"),
        );
    }
    push_assignment_file(
        &mut out,
        "tor",
        None,
        "user",
        program_root("tor").join("app/uid/user_program"),
        "/api/programs/tor/apps".to_string(),
    );
    push_assignment_file(
        &mut out,
        "blockedquic",
        None,
        "user",
        settings::blockedquic_uid_program_path(),
        "/api/blockedquic/apps".to_string(),
    );

    let singbox_root = singbox_profiles_root();
    if let Ok(rd) = fs::read_dir(&singbox_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "sing-box",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/sing-box/profiles/{profile}/apps/user"),
            );
        }
    }

    let singbox_lx_root = singbox_lx_profiles_root();
    if let Ok(rd) = fs::read_dir(&singbox_lx_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "sing-box-lx",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/sing-box-lx/profiles/{profile}/apps/user"),
            );
        }
    }

    let wireproxy_root = wireproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&wireproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "wireproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/wireproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myproxy_root = myproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&myproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myprogram_root = myprogram_profiles_root();
    if let Ok(rd) = fs::read_dir(&myprogram_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myprogram",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myprogram/profiles/{profile}/apps/user"),
            );
        }
    }

    let openvpn_root = openvpn_profiles_root();
    if let Ok(rd) = fs::read_dir(&openvpn_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "openvpn",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/openvpn/profiles/{profile}/apps/user"),
            );
        }
    }

    let amneziawg_root = amneziawg_profiles_root();
    if let Ok(rd) = fs::read_dir(&amneziawg_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "amneziawg",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/amneziawg/profiles/{profile}/apps/user"),
            );
        }
    }

    let tun2socks_root = tun2socks_profiles_root();
    if let Ok(rd) = fs::read_dir(&tun2socks_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "tun2socks",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/tun2socks/profiles/{profile}/apps/user"),
            );
        }
    }

    let myvpn_root = myvpn_profiles_root();
    if let Ok(rd) = fs::read_dir(&myvpn_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myvpn",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myvpn/profiles/{profile}/apps/user"),
            );
        }
    }

    let mihomo_root = mihomo_profiles_root();
    if let Ok(rd) = fs::read_dir(&mihomo_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "mihomo",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/mihomo/profiles/{profile}/apps/user"),
            );
        }
    }

    let mieru_root = mieru_profiles_root();
    if let Ok(rd) = fs::read_dir(&mieru_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "mieru",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/mieru/profiles/{profile}/apps/user"),
            );
        }
    }

    out
}

pub(in crate::api) fn assignment_to_view(v: &AppAssignmentFile) -> AppAssignmentView {
    AppAssignmentView {
        program_id: v.program_id.clone(),
        profile: v.profile.clone(),
        slot: v.slot.clone(),
        path: v.path.clone(),
        packages: v.packages.iter().cloned().collect(),
    }
}

fn conflict_label(v: &AppConflictView) -> String {
    match &v.profile {
        Some(profile) if !profile.is_empty() => format!("{} / {} / {}", v.program_id, profile, v.slot),
        _ => format!("{} / {}", v.program_id, v.slot),
    }
}

fn packages_to_text(pkgs: &BTreeSet<String>) -> String {
    pkgs.iter().cloned().collect::<Vec<_>>().join("\n")
}

pub(in crate::api) fn flatten_conflicts(map: &BTreeMap<String, Vec<AppConflictView>>) -> Vec<AppConflictView> {
    let mut out = Vec::new();
    for items in map.values() {
        out.extend(items.iter().cloned());
    }
    out
}

pub(in crate::api) fn find_proxyinfo_conflicts(candidate: &BTreeSet<String>) -> BTreeMap<String, Vec<AppConflictView>> {
    let mut out: BTreeMap<String, Vec<AppConflictView>> = BTreeMap::new();
    for item in collect_assignment_files() {
        for pkg in candidate.intersection(&item.packages) {
            out.entry(pkg.clone()).or_default().push(AppConflictView {
                package: pkg.clone(),
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    out
}

fn launch_marker_allowed_for_program(program_id: &str) -> bool {
    !matches!(program_id, "blockedquic" | "proxyInfo" | "proxyinfo")
}

pub(in crate::api) fn validate_launch_marker_usage(content: &str, program_id: &str) -> Result<()> {
    if crate::common::apps::content_has_launch_marker(content) && !launch_marker_allowed_for_program(program_id) {
        anyhow::bail!(
            "ZDT-D launch marker {} is not allowed for this program",
            crate::common::apps::LAUNCH_MARKER_PACKAGE
        );
    }
    Ok(())
}

pub(in crate::api) fn validate_program_apps_content(content: &str, current_api_path: &str, program_id: &str, _slot: &str) -> Result<()> {
    validate_launch_marker_usage(content, program_id)?;
    let candidate = parse_package_set(content);
    if candidate.is_empty() { return Ok(()); }

    let blocked = crate::features::proxyinfo::read_proxy_packages().unwrap_or_default();
    let mut overlap: Vec<String> = candidate.intersection(&blocked).cloned().collect();
    overlap.sort();
    if !overlap.is_empty() {
        anyhow::bail!("apps are blocked by proxyInfo: {}", overlap.join(", "));
    }

    Ok(())
}

pub(in crate::api) fn validate_proxyinfo_apps_content(content: &str) -> Result<()> {
    validate_launch_marker_usage(content, "proxyInfo")?;
    let candidate = parse_package_set(content);
    if candidate.is_empty() {
        return Ok(());
    }

    let conflicts = find_proxyinfo_conflicts(&candidate);
    if !conflicts.is_empty() {
        let mut parts = Vec::new();
        for (pkg, uses) in &conflicts {
            let labels = uses.iter().map(conflict_label).collect::<Vec<_>>().join(", ");
            parts.push(format!("{pkg} ({labels})"));
        }
        parts.sort();
        anyhow::bail!("apps are already used by ZDT-D programs: {}", parts.join("; "));
    }
    Ok(())
}

pub(in crate::api) fn remove_packages_from_program_lists(packages: &BTreeSet<String>) -> Result<Vec<AppConflictView>> {
    let mut removed = Vec::new();
    if packages.is_empty() { return Ok(removed); }
    for item in collect_assignment_files() {
        let intersection: BTreeSet<String> = item.packages.intersection(packages).cloned().collect();
        if intersection.is_empty() { continue; }
        let updated: BTreeSet<String> = item.packages.difference(packages).cloned().collect();
        write_text_atomic(&item.fs_path, &packages_to_text(&updated))?;
        for pkg in intersection {
            removed.push(AppConflictView {
                package: pkg,
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    if !removed.is_empty() {
        invalidate_assignment_cache();
    }
    Ok(removed)
}

fn read_package_set_or_empty(p: &Path) -> Result<BTreeSet<String>> {
    Ok(parse_package_set(&read_text_or_empty(p)?))
}


// --- global enabled app-list rule ---------------------------------------
//
// One application may be assigned to as many profiles and programs as the
// user likes, but two *different* enabled lists must never claim it at the
// same time, because then the daemon would drive the same traffic twice.
// Discovery walks `working_folder`, so no program id is named here.

#[derive(Debug, Clone)]
pub(crate) struct EnabledAppList {
    pub(crate) program_id: String,
    /// Empty for single-instance programs (tor, operaproxy), else the profile name.
    pub(crate) profile: String,
    pub(crate) packages: BTreeSet<String>,
}

impl EnabledAppList {
    pub(crate) fn label(&self) -> String {
        if self.profile.is_empty() {
            self.program_id.clone()
        } else {
            format!("{}/{}", self.program_id, self.profile)
        }
    }
}

/// Read every enabled app list from `working_folder`, discovering programs by
/// walking the tree instead of matching known program ids, so a rule written
/// here applies to every program without naming any of them.
///
/// Two layouts exist on disk and both are discovered:
///   - profile based: `<program>/<profile>/app/uid/*_program` (nfqws2) or
///     `<program>/profile/<profile>/app/uid/*_program` (everything else),
///     enabled per profile in `<program>/active.json`.
///   - single instance: `<program>/app/uid/*_program`, enabled by
///     `<program>/enabled.json` (tor, operaproxy).
///
/// `.deleted` holds trash from previous runs and is skipped.
pub(crate) fn collect_enabled_app_lists() -> Vec<EnabledAppList> {
    collect_enabled_app_lists_under(&crate::programs::registry::working_root())
}

/// [`collect_enabled_app_lists`] against an arbitrary `working_folder`, so the
/// rule can be exercised on a scratch tree.
fn collect_enabled_app_lists_under(root: &Path) -> Vec<EnabledAppList> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(root) else { return out; };
    for program in entries.flatten() {
        let program_id = program.file_name().to_string_lossy().to_string();
        let program_root = program.path();
        if !program_root.is_dir() || program_id.starts_with('.') {
            continue;
        }
        collect_program_app_lists(&program_id, &program_root, &mut out);
    }
    out.sort_by(|a, b| a.label().cmp(&b.label()));
    out
}

/// Slot files a list may keep its packages in, relative to an `app/uid` dir.
const APP_LIST_SLOTS: [&str; 3] = ["user_program", "mobile_program", "wifi_program"];

fn read_app_list_dir(uid_dir: &Path) -> BTreeSet<String> {
    let mut pkgs = BTreeSet::new();
    for slot in APP_LIST_SLOTS {
        if let Ok(content) = read_text_or_empty(&uid_dir.join(slot)) {
            pkgs.extend(parse_package_set(&content));
        }
    }
    pkgs
}

fn collect_program_app_lists(program_id: &str, root: &Path, out: &mut Vec<EnabledAppList>) {
    let enabled = |name: &str| -> bool {
        super::fs::read_json::<super::model::ProfilesActive>(&root.join("active.json"))
            .map(|a| a.profiles.get(name).is_some_and(|st| st.enabled))
            .unwrap_or(false)
    };

    // Single-instance program: the whole program is one list, enabled through
    // its own flag file. Both names in use on disk are accepted so that no
    // program id has to be named here.
    let single_enabled = ["enabled.json", "active.json"].iter().any(|file| {
        super::fs::read_json::<super::model::EnabledActive>(&root.join(file))
            .map(|v| v.enabled)
            .unwrap_or(false)
    });
    if single_enabled {
        let uid_dir = root.join("app/uid");
        let packages = read_app_list_dir(&uid_dir);
        if !packages.is_empty() {
            out.push(EnabledAppList {
                program_id: program_id.to_string(),
                profile: String::new(),
                packages,
            });
        }
    }

    // Profile based: `<program>/<profile>/app/uid` (nfqws2) and
    // `<program>/profile/<profile>/app/uid` (the rest). Both are walked.
    for container in [root.to_path_buf(), root.join("profile")] {
        let Ok(profiles) = std::fs::read_dir(&container) else { continue; };
        for entry in profiles.flatten() {
            let profile = entry.file_name().to_string_lossy().to_string();
            if profile.starts_with('.') {
                continue;
            }
            let uid_dir = entry.path().join("app/uid");
            if !uid_dir.is_dir() || !enabled(&profile) {
                continue;
            }
            let packages = read_app_list_dir(&uid_dir);
            if packages.is_empty() {
                continue;
            }
            out.push(EnabledAppList {
                program_id: program_id.to_string(),
                profile,
                packages,
            });
        }
    }
}

/// The single app-list rule. A list compared against itself is not a pair, so
/// re-enabling the list that already holds an app never trips the rule.
pub(crate) fn find_enabled_app_overlaps() -> Vec<(EnabledAppList, EnabledAppList, usize)> {
    find_enabled_app_overlaps_under(&crate::programs::registry::working_root())
}

/// [`find_enabled_app_overlaps`] against an arbitrary `working_folder`, so the
/// rule can be exercised on a scratch tree.
fn find_enabled_app_overlaps_under(root: &Path) -> Vec<(EnabledAppList, EnabledAppList, usize)> {
    let lists = collect_enabled_app_lists_under(root);
    let mut out = Vec::new();
    for i in 0..lists.len() {
        for j in (i + 1)..lists.len() {
            let common = lists[i].packages.intersection(&lists[j].packages).count();
            if common > 0 {
                out.push((lists[i].clone(), lists[j].clone(), common));
            }
        }
    }
    out
}

/// True when at least one program would start something: a profile is
/// enabled in `active.json`, or a single-instance program has its flag file
/// set. Discovered by walking `working_folder`, so no program id is named
/// here either — a new program is covered without touching this function.
///
/// Deliberately broader than [`collect_enabled_app_lists`]: a profile can be
/// enabled with no app list and still be the user's intent (whole-device
/// mode), so enablement alone counts.
pub(crate) fn any_program_enabled() -> bool {
    any_program_enabled_under(&crate::programs::registry::working_root())
}

fn any_program_enabled_under(root: &Path) -> bool {
    let Ok(entries) = std::fs::read_dir(root) else { return false; };
    for program in entries.flatten() {
        let program_root = program.path();
        if !program_root.is_dir() || program.file_name().to_string_lossy().starts_with('.') {
            continue;
        }

        // Single-instance flag.
        if ["enabled.json", "active.json"].iter().any(|file| {
            super::fs::read_json::<super::model::EnabledActive>(&program_root.join(file))
                .map(|v| v.enabled)
                .unwrap_or(false)
        }) {
            return true;
        }

        // Profile flags: both on-disk layouts.
        for container in [program_root.clone(), program_root.join("profile")] {
            let Ok(profiles) = std::fs::read_dir(&container) else { continue; };
            for entry in profiles.flatten() {
                if entry.file_name().to_string_lossy().starts_with('.') {
                    continue;
                }
                let enabled = super::fs::read_json::<super::model::ProfilesActive>(&program_root.join("active.json"))
                    .ok()
                    .and_then(|a| {
                        let name = entry.file_name().to_string_lossy().to_string();
                        a.profiles.get(&name).map(|st| st.enabled)
                    });
                if enabled == Some(true) {
                    return true;
                }
            }
        }
    }
    false
}
