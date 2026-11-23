//! gobley's Kotlin Multiplatform bindings generator, in library mode only:
//!
//! ```text
//! uniffi-bindgen LIBRARY OUT_DIR [CONFIG]
//! ```
//!
//! reads the UniFFI metadata out of `LIBRARY` (librocky_prism_ffi, a cdylib
//! or staticlib of any target) and writes the Kotlin sources (`commonMain`,
//! `jvmMain`, `androidMain`, `nativeMain`) and the C header for Kotlin/Native's
//! cinterop (`nativeInterop`) under `OUT_DIR`. `CONFIG` is the crate's
//! `uniffi.toml`. gobley's own CLI does the same; this one keeps the bindgen in
//! the workspace's Cargo.lock, so Nix builds it offline with the rest.

use anyhow::{Context, bail};
use camino::Utf8PathBuf;
use gobley_uniffi_bindgen::KotlinBindingGenerator;
use uniffi_bindgen::BindgenCrateConfigSupplier;

/// `CONFIG` for the crate the bindings are for, nothing for the others.
struct Config(Option<Utf8PathBuf>);

impl BindgenCrateConfigSupplier for Config {
    fn get_toml(&self, crate_name: &str) -> anyhow::Result<Option<toml::value::Table>> {
        let Some(path) = self.0.as_ref().filter(|_| crate_name == "rocky_prism_ffi") else {
            return Ok(None);
        };
        let text = std::fs::read_to_string(path).with_context(|| format!("reading {path}"))?;
        Ok(Some(
            toml::from_str(&text).with_context(|| format!("parsing {path}"))?,
        ))
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (library, out_dir, config) = match args.as_slice() {
        [library, out_dir] => (library, out_dir, None),
        [library, out_dir, config] => (library, out_dir, Some(Utf8PathBuf::from(config))),
        _ => bail!("usage: uniffi-bindgen LIBRARY OUT_DIR [CONFIG]"),
    };
    uniffi_bindgen::library_mode::generate_bindings(
        &Utf8PathBuf::from(library),
        None,
        &KotlinBindingGenerator,
        &Config(config),
        None,
        &Utf8PathBuf::from(out_dir),
        false,
    )
    .context("generating the Kotlin bindings")?;
    Ok(())
}
