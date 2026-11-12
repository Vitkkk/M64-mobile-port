//! Horizon on Android.
//!
//! The launcher (Kotlin) writes `files/launch/launch.json` and starts
//! `GameActivity` in its own process (`:game`). GameActivity loads this library
//! and calls [`android_main`] on a thread of its own, which:
//!
//! 1. sends stdout and stderr to logcat (Horizon reports on stderr);
//! 2. reads the launch file: Horizon's command line (the Launch API's options,
//!    `docs/api/launch.md`), the environment (offline skin, cape...) and the
//!    folder to start in;
//! 3. hands the activity and the command line to `horizon_client::embed` and
//!    runs the game exactly as `horizon-client` does;
//! 4. writes `files/launch/exit.json` and ends the process, which takes the
//!    player back to the launcher.
//!
//! Touch controls do not go through here: the launcher's overlay talks to the
//! game's control socket (`--control-socket`), Horizon's own automation API.

mod launch;
mod logcat;

use std::path::{Path, PathBuf};

pub use winit::platform::android::activity::AndroidApp;

#[global_allocator]
static ALLOCATOR: horizon_threads::Allocator = horizon_threads::ALLOCATOR;

/// The mod API level this game serves (see horizon-client's main.rs): kept in the
/// library so a tool can read it without running the game.
static MOD_API_LEVEL: [u8; 27] = horizon_mods::api_level::MARK;

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    std::hint::black_box(&MOD_API_LEVEL);
    logcat::redirect_stdio("Horizon");
    let files = app
        .internal_data_path()
        .unwrap_or_else(|| PathBuf::from("/data/local/tmp/horizon"));
    let launch_dir = files.join("launch");
    let code = match launch::Launch::read(&launch_dir.join("launch.json")) {
        Ok(launch) => run(app, &files, launch),
        Err(error) => {
            eprintln!("Horizon: no launch file: {error}");
            launch::write_exit(&launch_dir, 2, Some(&error));
            std::process::exit(2);
        }
    };
    launch::write_exit(&launch_dir, code, None);
    // The game's process is the game's alone (android:process=":game"): ending it
    // frees its memory at once and shows the launcher again.
    std::process::exit(code);
}

fn run(app: AndroidApp, files: &Path, launch: launch::Launch) -> i32 {
    // Where Horizon would look on a desktop: HOME, the temporary folder, the
    // runtime folder (the control socket's default place).
    let cache = files.parent().map_or_else(|| files.join("cache"), |p| p.join("cache"));
    let run_dir = files.join("run");
    for dir in [&cache, &run_dir, &launch.game_dir] {
        let _ = std::fs::create_dir_all(dir);
    }
    // SAFETY: nothing else runs yet: this is the first thing the game's process
    // does on its main thread, before Horizon starts any thread of its own.
    unsafe {
        std::env::set_var("HOME", files);
        std::env::set_var("TMPDIR", &cache);
        std::env::set_var("XDG_RUNTIME_DIR", &run_dir);
        std::env::set_var("RUST_BACKTRACE", "1");
        for (key, value) in &launch.env {
            std::env::set_var(key, value);
        }
    }
    if let Err(error) = std::env::set_current_dir(&launch.game_dir) {
        eprintln!(
            "Horizon: can't enter the game folder {}: {error}",
            launch.game_dir.display()
        );
        return 1;
    }
    eprintln!("Horizon: starting with {:?}", launch.redacted_args());
    horizon_client::embed::set_android_app(app);
    horizon_client::embed::set_args(launch.args);
    // A panic on this thread ends the game with a crash report already written
    // by Horizon's handler (crash::install); report it as the desktop's 101.
    match std::panic::catch_unwind(horizon_client::entry::main) {
        Ok(()) => 0,
        Err(_) => 101,
    }
}
