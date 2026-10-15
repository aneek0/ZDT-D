//! Per-item NFQWS2 preset model: parse, edit, serialize.
//!
//! A preset file is split into a comment header, a global preamble
//! (`--lua-init`, `--blob`, `--ctrack-disable`, …) and named profiles
//! delimited by `--new`. The first profile starts without a `--new` line
//! (ZDT-D builtin format); subsequent profiles are introduced by `--new`
//! optionally followed by `--name=`.
//!
//! Editing operations are pure `text -> text` transforms over
//! [`PresetDocument`] so the strict compiler (`command-builder.sh`) stays the
//! single authority on runtime semantics.

pub mod model;
pub mod parser;
pub mod serializer;
pub mod service;
pub mod validation;

pub use model::{MatchFamily, Profile, Segment};
pub use parser::{parse_preset, ParsedPreset};
pub use serializer::{
    append_profile, delete_profile, duplicate_profile, move_profile, serialize_preset,
    set_profile_enabled, set_profile_user_match, with_profile_raw_text,
    with_profile_strategy, with_profile_whole_strategy,
};
