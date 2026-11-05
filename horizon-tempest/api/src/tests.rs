//! The game halves driven by a fake game (`FakeHost`), as Horizon's own
//! trait mod tests do with test doubles.

use crate::client::{self, ClientState};
use crate::server::{ServerState, OVERWORLD};
use crate::sim::damage::FILTERS;
use crate::sim::net;
use crate::sim::spawn::Spawner;
use crate::sim::storm::StormKind;
use horizon_client::mod_api::*;
use std::path::PathBuf;

struct FakeHost {
    game_time: i64,
    dir: PathBuf,
    edits: Vec<NativeBlockEdit>,
    commands: Vec<String>,
    sent: Vec<(u128, String, Vec<u8>)>,
    feedback: Vec<String>,
    sounds: Vec<String>,
    pushes: Vec<[f32; 3]>,
    fills: std::cell::Cell<usize>,
    camera: Option<NativeCamera>,
    refuse_filter: Option<&'static str>,
}

impl FakeHost {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("tempest-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        FakeHost {
            game_time: 100_000,
            dir,
            edits: Vec::new(),
            commands: Vec::new(),
            sent: Vec::new(),
            feedback: Vec::new(),
            sounds: Vec::new(),
            pushes: Vec::new(),
            fills: std::cell::Cell::new(0),
            camera: None,
            refuse_filter: None,
        }
    }
    fn camera_at(&mut self, pos: [f64; 3]) {
        self.camera = Some(NativeCamera {
            position: pos,
            eye_height: 1.62,
            partial_tick: 0.0,
            yaw: 0.0,
            pitch: 0.0,
            fov_degrees: 70.0,
            near: 0.05,
            far: 512.0,
            view: [0.0; 16],
            projection: [0.0; 16],
            dimension: OVERWORLD.into(),
        });
    }
}

impl Host for FakeHost {
    fn level(&self, dimension: &str) -> Option<LevelRef> {
        (dimension == OVERWORLD).then(|| LevelRef::new(NativeWorldEpoch::for_tests(1), OVERWORLD))
    }
    fn level_game_time(&self, _level: LevelRef) -> Option<i64> {
        Some(self.game_time)
    }
    fn level_day_time(&self, _level: LevelRef) -> Option<i64> {
        Some(6000)
    }
    fn edit_blocks(&mut self, _dimension: &str, edit: &NativeBlockEdit) -> Result<u64, String> {
        if let Some(f) = self.refuse_filter {
            if edit.filters.iter().any(|x| x == f) {
                return Err(format!("unknown block predicate {f}"));
            }
        }
        self.edits.push(edit.clone());
        Ok(edit.runs.len() as u64)
    }
    fn surface_heights(&self, _d: &str, columns: &[(i32, i32)], _h: NativeHeightmap) -> Result<Vec<Option<i32>>, String> {
        Ok(columns.iter().map(|_| Some(64)).collect())
    }
    fn run_command(&mut self, line: &str) -> bool {
        self.commands.push(line.to_owned());
        true
    }
    fn net_send_to_player(&mut self, player: u128, channel: &str, data: &[u8]) -> Result<(), String> {
        self.sent.push((player, channel.to_owned(), data.to_vec()));
        Ok(())
    }
    fn feedback(&mut self, line: Text) {
        self.feedback.push(line.plain());
    }
    fn config_dir(&self) -> Result<PathBuf, String> {
        Ok(self.dir.clone())
    }
    fn camera(&self) -> Option<NativeCamera> {
        self.camera.clone()
    }
    fn clock(&self) -> Option<NativeClock> {
        Some(NativeClock { game_time: self.game_time, day_time: 6000 })
    }
    fn play_local_sound(&mut self, sound: &str, _at: [f64; 3], volume: f32, pitch: f32, delay: i32) -> bool {
        assert!(volume > 0.0 && pitch > 0.0 && (0..=1200).contains(&delay), "{sound} {volume} {pitch} {delay}");
        self.sounds.push(sound.to_owned());
        true
    }
    fn push_player(&mut self, kick: [f32; 3]) {
        self.pushes.push(kick);
    }
    fn fill(&self, _batch: &mut HudBatch, rect: [f32; 4], _argb: u32) {
        assert!(rect[2] >= 0.0 && rect[3] >= 0.0);
        self.fills.set(self.fills.get() + 1);
    }
    fn text(&self, _batch: &mut HudBatch, _text: &str, _at: [f32; 2], _rgb: u32, _shadow: bool) {}
}

const PLAYER: u128 = 42;

fn server_with_player(host: &mut FakeHost) -> ServerState {
    let mut s = ServerState::default();
    s.open_world(Some(host.dir.join("tempest")));
    s.perform_command(host, [0.0, 64.0, 0.0], "tempest set storm_rate 0");
    s
}

fn run_ticks(s: &mut ServerState, host: &mut FakeHost, n: i64) {
    for _ in 0..n {
        host.game_time += 1;
        s.on_player_tick(PLAYER, "Alex", [0.0, 64.0, 0.0], OVERWORLD, 0);
        s.on_overworld_tick(host);
    }
}

#[test]
fn spawned_tornado_damages_syncs_and_hurts() {
    let mut host = FakeHost::new("tornado");
    let mut s = server_with_player(&mut host);
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest spawn tornado 4");
    assert!(host.feedback.last().unwrap().contains("EF4"), "{:?}", host.feedback);
    run_ticks(&mut s, &mut host, 1300);

    // Damage: bulk edits whose runs point inside their own lists.
    assert!(!host.edits.is_empty(), "the tornado damaged the world");
    for e in &host.edits {
        assert_eq!(e.filters.len(), FILTERS.len());
        for r in &e.runs {
            assert!((r.block as usize) < e.palette.len());
            assert!((r.only.unwrap() as usize) < e.filters.len());
            assert!(r.surface.is_some());
        }
    }
    // Sync: the player got decodable lists with the storm in them.
    assert!(!host.sent.is_empty());
    let (who, channel, data) = host.sent.last().unwrap();
    assert_eq!((*who, channel.as_str()), (PLAYER, net::CHANNEL));
    let snap = net::decode(data).expect("valid snapshot");
    assert!(snap.storms.iter().any(|st| st.manual && st.tornado.is_some()));
    // Hazards: lightning or damage commands ran as the console.
    assert!(host.commands.iter().any(|c| c.starts_with("damage Alex")), "{:?}", host.commands);
    assert!(host.commands.iter().any(|c| c.starts_with("weather ")));
    // Saved for the next session.
    assert!(host.dir.join("tempest/storms.bin").exists());
}

#[test]
fn refused_filter_falls_back_to_safe_list() {
    let mut host = FakeHost::new("safe");
    host.refuse_filter = Some("#minecraft:impermeable");
    let mut s = server_with_player(&mut host);
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest spawn tornado 5");
    run_ticks(&mut s, &mut host, 1300);
    assert!(!host.edits.is_empty(), "damage continued in safe mode");
    assert!(host.edits.iter().all(|e| e.filters.len() == 6));
}

#[test]
fn commands_answer() {
    let mut host = FakeHost::new("cmd");
    let mut s = server_with_player(&mut host);
    run_ticks(&mut s, &mut host, 5);
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest info");
    assert!(host.feedback.last().unwrap().contains("CAPE"));
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest spawn hurricane");
    run_ticks(&mut s, &mut host, 120);
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest list");
    assert!(host.feedback.iter().any(|f| f.contains("hurricane")), "{:?}", host.feedback);
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest clear");
    run_ticks(&mut s, &mut host, 120);
    host.feedback.clear();
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest list");
    assert!(host.feedback[0].contains("no storms"));
    s.perform_command(&mut host, [0.0, 64.0, 0.0], "tempest spawn nonsense");
    assert!(host.feedback.last().unwrap().starts_with("Usage"));
}

#[test]
fn client_sees_hears_and_draws() {
    let mut host = FakeHost::new("client");
    host.camera_at([0.0, 70.0, 0.0]);
    let mut c = ClientState::default();
    // The server sends a tornado about to arrive.
    let sp = Spawner::new(crate::sim::config::DEFAULT_SEED, 48.0);
    let storm = sp.manual(StormKind::Supercell, [0.0, 0.0], host.game_time, Some(3), 200);
    let snap = net::Snapshot { seed: sp.seed, year_days: 48.0, game_time: host.game_time, storms: vec![storm] };
    c.on_snapshot(&net::encode(&snap));
    let cam = host.camera.clone().unwrap();
    for _ in 0..240 {
        host.game_time += 1;
        c.on_tick(&mut host, &cam);
    }
    assert!(!c.storms.is_empty());
    assert!(!c.tors.is_empty(), "tornado on the ground");
    assert!(c.warning.is_some_and(|w| w.confirmed));
    assert!(c.radar_n > 0 && c.radar.iter().any(|&px| px != 0), "radar shows echoes");
    assert!(host.sounds.iter().any(|s| s.contains("raid.horn")), "sirens");
    assert!(host.sounds.iter().any(|s| s.contains("elytra")), "roar or wind");
    assert!(!host.pushes.is_empty(), "the wind pushes");
    assert!(c.shake_amp > 0.0);

    let mut prims = WorldPrimitives::default();
    client::render::draw(&c, 0.5, cam.position, &mut prims);
    assert!(prims.len() > 10 && prims.len() <= c.budget().boxes, "{} boxes", prims.len());

    let mut batch = HudBatch::for_tests();
    let mut corners = Corners::new(400.0, 240.0, 0.0);
    client::hud::draw(&c, &host, &mut batch, &mut corners);
    assert!(host.fills.get() > 3);

    let mut fog = FogData { color: [0.5; 4], environmental_start: 0.0, environmental_end: 200.0, render_distance_start: 100.0, render_distance_end: 200.0, sky_end: 200.0, cloud_end: 200.0 };
    client::fx::fog(&c, &mut fog);
    assert!(fog.render_distance_end < 200.0, "denser fog in the storm");
    let mut params = [[0.0f32; 4]; 8];
    client::fx::shader_params(&c, &mut params);
    assert!(params[0][3] > 0.0);
}

#[test]
fn client_falls_back_and_takes_commands() {
    let mut host = FakeHost::new("fallback");
    host.camera_at([0.0, 70.0, 0.0]);
    let mut c = ClientState::default();
    let cam = host.camera.clone().unwrap();
    c.on_tick(&mut host, &cam);
    assert!(host.dir.join("tempest-client.cfg").exists(), "default settings written");
    c.perform_command(&mut host, "radar quality high");
    assert_eq!(c.cfg.quality, crate::sim::config::Quality::High);
    c.perform_command(&mut host, "radar vel");
    assert!(c.cfg.velocity);
    c.perform_command(&mut host, "radar");
    assert!(!c.cfg.radar);
    let saved = std::fs::read_to_string(host.dir.join("tempest-client.cfg")).unwrap();
    assert!(saved.contains("quality = high") && saved.contains("radar = false"));
    // Not in the overworld: nothing is drawn or heard.
    let mut nether = cam.clone();
    nether.dimension = "minecraft:the_nether".into();
    c.on_tick(&mut host, &nether);
    assert!(!c.active && c.storms.is_empty());
}
