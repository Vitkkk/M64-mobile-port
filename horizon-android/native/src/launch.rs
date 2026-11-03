//! The launch file the launcher writes before it starts the game, and the exit
//! file the game leaves behind.
//!
//! ```json
//! {
//!   "version": 1,
//!   "game_dir": "/storage/emulated/0/Android/data/io.github.zenith/files/instances/main",
//!   "args": ["--resources", "...", "--game-dir", "...", "--control-socket", "..."],
//!   "env": { "HORIZON_OFFLINE_SKIN": ".../skin.png", "HORIZON_OFFLINE_SKIN_MODEL": "slim" }
//! }
//! ```
use serde::Deserialize;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// The launch file's format; a newer launcher writes a higher number only when an
/// older game would misread it.
pub const VERSION: u32 = 1;

#[derive(Debug, Deserialize)]
pub struct Launch {
    pub version: u32,
    pub game_dir: PathBuf,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
}

impl Launch {
    pub fn read(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let launch: Self = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
        if launch.version > VERSION {
            return Err(format!(
                "launch file version {} is newer than this game ({VERSION}): update the game",
                launch.version
            ));
        }
        if launch.args.iter().any(|a| a == "--session-stdin") {
            return Err("--session-stdin has no standard input on Android: use --session-file".into());
        }
        Ok(launch)
    }

    /// The command line for the log, with paths kept and nothing secret in it
    /// (the session goes through a file, never the command line).
    pub fn redacted_args(&self) -> &[String] {
        &self.args
    }
}

/// `exit.json`: how the game ended, for the launcher's next screen.
pub fn write_exit(dir: &Path, code: i32, error: Option<&str>) {
    let _ = std::fs::create_dir_all(dir);
    let value = serde_json::json!({
        "code": code,
        "error": error,
        "time": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs()),
    });
    let _ = std::fs::write(dir.join("exit.json"), value.to_string());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_launch_file() {
        let dir = std::env::temp_dir().join(format!("horizon-android-launch-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("launch.json");
        std::fs::write(
            &file,
            r#"{"version":1,"game_dir":"/x","args":["--resources","/r"],"env":{"A":"b"}}"#,
        )
        .unwrap();
        let launch = Launch::read(&file).unwrap();
        assert_eq!(launch.game_dir, PathBuf::from("/x"));
        assert_eq!(launch.args, ["--resources", "/r"]);
        assert_eq!(launch.env["A"], "b");
        std::fs::write(&file, r#"{"version":99,"game_dir":"/x"}"#).unwrap();
        assert!(Launch::read(&file).is_err());
        std::fs::write(&file, r#"{"version":1,"game_dir":"/x","args":["--session-stdin"]}"#).unwrap();
        assert!(Launch::read(&file).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
