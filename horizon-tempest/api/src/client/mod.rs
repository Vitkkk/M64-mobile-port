//! The client half: what the player sees, hears and feels.

pub mod fx;
pub mod hud;
pub mod render;

use crate::sim::climate::{Env, DAY};
use crate::sim::config::{ClientConfig, Quality};
use crate::sim::field::{self, Sample};
use crate::sim::net;
use crate::sim::precip::Precip;
use crate::sim::radar::{self, Product};
use crate::sim::rng::Rng;
use crate::sim::spawn::Spawner;
use crate::sim::storm::{StormDef, StormKind, TornadoState};
use horizon_client::mod_api::*;
use std::path::PathBuf;
use std::time::{Duration, Instant};

const OVERWORLD: &str = "minecraft:overworld";
/// A server list older than this is stale (the server went quiet).
const SNAPSHOT_TTL: Duration = Duration::from_secs(15);

/// A tornado warning for the HUD and the sirens.
#[derive(Clone, Copy, Debug)]
pub struct Warning {
    pub ef: u8,
    pub dist: f64,
    pub dir: &'static str,
    /// The tornado is on the ground (else it is expected soon).
    pub confirmed: bool,
}

#[derive(Default)]
struct Timers {
    wind: i64,
    roar: i64,
    thunder: i64,
    siren: i64,
    hail: i64,
}

pub struct ClientState {
    pub cfg: ClientConfig,
    cfg_path: Option<PathBuf>,
    cfg_loaded: bool,
    server_storms: Vec<StormDef>,
    last_snapshot: Option<Instant>,
    local_storms: Vec<StormDef>,
    local_at: i64,
    local_anchor: [f64; 2],
    spawner: Spawner,
    /// Storms in view this tick.
    pub storms: Vec<StormDef>,
    pub tors: Vec<(usize, TornadoState)>,
    /// Game time of the last tick and time of day.
    pub t: i64,
    pub day: i64,
    pub cam: [f64; 3],
    pub active: bool,
    pub env: Env,
    pub here: Sample,
    /// Smoothed darkness (0..1) and visibility (0..1) for the fog.
    pub dark: f32,
    pub vis: f32,
    pub radar: Vec<u32>,
    pub radar_n: usize,
    radar_t: i64,
    pub warning: Option<Warning>,
    pub flash: f32,
    pub shake_amp: f32,
    pub started: Instant,
    timers: Timers,
    rng: Rng,
}

impl Default for ClientState {
    fn default() -> Self {
        let cfg = ClientConfig::default();
        ClientState {
            spawner: Spawner::new(cfg.seed, cfg.year_days),
            cfg,
            cfg_path: None,
            cfg_loaded: false,
            server_storms: Vec::new(),
            last_snapshot: None,
            local_storms: Vec::new(),
            local_at: i64::MIN,
            local_anchor: [0.0, 0.0],
            storms: Vec::new(),
            tors: Vec::new(),
            t: 0,
            day: 0,
            cam: [0.0; 3],
            active: false,
            env: Env::default(),
            here: Sample { tornado_dist: f64::INFINITY, ..Sample::default() },
            dark: 0.0,
            vis: 1.0,
            radar: Vec::new(),
            radar_n: 0,
            radar_t: i64::MIN,
            warning: None,
            flash: 0.0,
            shake_amp: 0.0,
            started: Instant::now(),
            timers: Timers::default(),
            rng: Rng::new(0xC11E),
        }
    }
}

impl ClientState {
    pub fn budget(&self) -> crate::sim::config::Budget {
        self.cfg.quality.budget()
    }

    /// Seconds since the mod started, for animations independent of ticks.
    pub fn seconds(&self) -> f32 {
        self.started.elapsed().as_secs_f32()
    }

    pub fn reset_world(&mut self) {
        self.server_storms.clear();
        self.last_snapshot = None;
        self.local_storms.clear();
        self.local_at = i64::MIN;
        self.storms.clear();
        self.tors.clear();
        self.radar.clear();
        self.radar_t = i64::MIN;
        self.warning = None;
        self.active = false;
        self.timers = Timers::default();
    }

    fn load_config(&mut self, host: &dyn Host) {
        self.cfg_loaded = true;
        let Ok(dir) = host.config_dir() else { return };
        let path = dir.join("tempest-client.cfg");
        match std::fs::read_to_string(&path) {
            Ok(text) => self.cfg = ClientConfig::parse(&text),
            Err(_) => {
                let _ = std::fs::create_dir_all(&dir);
                let _ = std::fs::write(&path, self.cfg.to_text());
            }
        }
        self.cfg_path = Some(path);
        self.spawner = Spawner::new(self.cfg.seed, self.cfg.year_days);
    }

    fn save_config(&self) {
        if let Some(p) = &self.cfg_path {
            let _ = std::fs::write(p, self.cfg.to_text());
        }
    }

    /// The server's list of storms.
    pub fn on_snapshot(&mut self, data: &[u8]) {
        let Some(snap) = net::decode(data) else { return };
        if snap.seed != self.spawner.seed || (snap.year_days as f64 - self.spawner.climate.year_days).abs() > 1e-3 {
            self.spawner = Spawner::new(snap.seed, snap.year_days as f64);
        }
        self.server_storms = snap.storms;
        self.last_snapshot = Some(Instant::now());
    }

    fn synced(&self) -> bool {
        self.last_snapshot.is_some_and(|t| t.elapsed() < SNAPSHOT_TTL)
    }

    /// Once a tick on the window's game.
    pub fn on_tick(&mut self, host: &mut dyn Host, cam: &NativeCamera) {
        if !self.cfg_loaded {
            self.load_config(host);
        }
        self.active = cam.dimension == OVERWORLD;
        if !self.active {
            self.storms.clear();
            self.tors.clear();
            self.warning = None;
            self.shake_amp = 0.0;
            return;
        }
        let Some(clock) = host.clock() else { return };
        self.t = clock.game_time;
        self.day = clock.day_time;
        self.cam = cam.position;
        let t = self.t;
        let tf = t as f64;

        // Which storms: the server's when it talks to us, else our own.
        if self.synced() {
            self.storms.clear();
            self.storms.extend(self.server_storms.iter().filter(|s| s.alive(tf)).copied());
        } else if self.cfg.fallback {
            let moved = (self.local_anchor[0] - self.cam[0]).hypot(self.local_anchor[1] - self.cam[2]);
            if t.saturating_sub(self.local_at) >= 100 || t < self.local_at || moved > 1000.0 {
                let radius = self.budget().draw_distance.max(self.cfg.radar_range * 1.5);
                self.local_storms.clear();
                self.spawner.storms_near(self.cam[0], self.cam[2], t, radius, &mut self.local_storms);
                self.local_at = t;
                self.local_anchor = [self.cam[0], self.cam[2]];
            }
            self.storms.clear();
            self.storms.extend(self.local_storms.iter().filter(|s| s.alive(tf)).copied());
        } else {
            self.storms.clear();
        }
        self.tors = field::tornadoes(&self.storms, tf);
        self.env = self.spawner.climate.sample(self.cam[0], self.cam[2], tf, self.day as f64);
        self.here = field::sample(&self.storms, &self.tors, &self.env, self.cam[0], self.cam[2], tf);

        // Smoothed fog targets.
        let night = night_factor(self.day);
        let mut dark = self.here.cover * (0.55 + 0.25 * night);
        let mut vis = match self.here.precip {
            Precip::Snow => 1.0 - 0.85 * crate::sim::storm::smoothstep(12.0, 40.0, self.here.dbz),
            Precip::None => 1.0,
            _ => 1.0 - 0.7 * crate::sim::storm::smoothstep(20.0, 62.0, self.here.dbz),
        };
        if let Some((_, tor)) = self.nearest_tornado() {
            let d = (tor.pos[0] - self.cam[0]).hypot(tor.pos[1] - self.cam[2]);
            let k = 1.0 - crate::sim::storm::smoothstep(tor.radius, tor.radius * 5.0, d);
            vis *= 1.0 - 0.6 * k;
            dark = dark.max(0.85 * k);
        }
        self.dark += (dark as f32 - self.dark) * 0.08;
        self.vis += (vis as f32 - self.vis) * 0.08;
        self.flash *= 0.75;

        self.update_warning();
        self.effects(host);
        if self.cfg.radar && (t.saturating_sub(self.radar_t) >= self.budget().radar_refresh as i64 || t < self.radar_t) {
            self.sweep();
        }
    }

    pub fn nearest_tornado(&self) -> Option<(usize, TornadoState)> {
        self.tors
            .iter()
            .min_by(|a, b| {
                let da = (a.1.pos[0] - self.cam[0]).hypot(a.1.pos[1] - self.cam[2]);
                let db = (b.1.pos[0] - self.cam[0]).hypot(b.1.pos[1] - self.cam[2]);
                da.total_cmp(&db)
            })
            .copied()
    }

    fn update_warning(&mut self) {
        let here = [self.cam[0], self.cam[2]];
        let tf = self.t as f64;
        let mut w: Option<Warning> = None;
        for (_, tor) in &self.tors {
            let d = (tor.pos[0] - here[0]).hypot(tor.pos[1] - here[1]);
            if d < 3000.0 && w.is_none_or(|o| d < o.dist) {
                w = Some(Warning { ef: tor.ef_peak, dist: d, dir: field::compass_to(here, tor.pos), confirmed: true });
            }
        }
        if w.is_none() {
            // Radar-indicated: a rotating storm about to produce one.
            for s in &self.storms {
                let (Some(td), Some(m)) = (s.tornado, s.meso_pos(tf)) else { continue };
                let until = s.t0 as f64 + td.start as f64 - tf;
                let d = (m[0] - here[0]).hypot(m[1] - here[1]);
                if (0.0..1200.0).contains(&until) && d < 4000.0 && w.is_none_or(|o| d < o.dist) {
                    w = Some(Warning { ef: td.ef, dist: d, dir: field::compass_to(here, m), confirmed: false });
                }
            }
        }
        self.warning = w;
    }

    /// Sounds, pushes and shake.
    fn effects(&mut self, host: &mut dyn Host) {
        let t = self.t;
        let cam = self.cam;
        let speed = self.here.speed();
        // Wind pushes the player; a tornado lifts them.
        if self.cfg.push && speed > 18.0 {
            let k = 0.0012 * ((speed - 18.0) / 30.0).min(2.5);
            let mut kick = [(self.here.wind[0] * k) as f32, 0.0f32, (self.here.wind[1] * k) as f32];
            if self.here.up > 5.0 {
                kick[1] = (self.here.up * 0.0016).min(0.12) as f32;
            }
            host.push_player(kick);
        }
        // Shake near a tornado.
        self.shake_amp = 0.0;
        if let Some((_, tor)) = self.nearest_tornado() {
            let d = (tor.pos[0] - cam[0]).hypot(tor.pos[1] - cam[2]);
            let k = (1.0 - d / (tor.radius * 8.0 + 60.0)).clamp(0.0, 1.0);
            self.shake_amp = (k * k * tor.vmax / 40.0) as f32;
        }
        if !self.cfg.sounds {
            return;
        }
        if speed > 16.0 && t >= self.timers.wind {
            let vol = ((speed - 16.0) / 40.0).clamp(0.05, 0.8) as f32;
            host.play_local_sound("minecraft:item.elytra.flying", [cam[0], cam[1] + 2.0, cam[2]], vol, 0.55 + self.rng.f64() as f32 * 0.1, 0);
            self.timers.wind = t + 50;
        }
        if let Some((_, tor)) = self.nearest_tornado() {
            let d = (tor.pos[0] - cam[0]).hypot(tor.pos[1] - cam[2]);
            if d < 900.0 && t >= self.timers.roar {
                let dir = toward(cam, tor.pos, d.min(24.0));
                let vol = (3.0 * (1.0 - d / 900.0) + 0.3) as f32;
                host.play_local_sound("minecraft:item.elytra.flying", dir, vol, 0.32, 0);
                host.play_local_sound("minecraft:weather.rain", dir, vol * 0.8, 0.5, 0);
                self.timers.roar = t + 40;
            }
        }
        if let Some(w) = self.warning {
            if self.cfg.sirens && t >= self.timers.siren {
                host.play_local_sound("minecraft:event.raid.horn", [cam[0] + 40.0, cam[1] + 10.0, cam[2] + 30.0], 1.2, 0.85, 0);
                self.timers.siren = t + if w.confirmed { 120 } else { 200 };
            }
        }
        if self.here.precip == Precip::Hail && t >= self.timers.hail {
            for _ in 0..2 {
                let at = [cam[0] + self.rng.range(-4.0, 4.0), cam[1], cam[2] + self.rng.range(-4.0, 4.0)];
                host.play_local_sound("minecraft:block.gravel.hit", at, 0.35, self.rng.range(1.3, 1.9) as f32, self.rng.range(0.0, 4.0) as i32);
            }
            self.timers.hail = t + 6;
        }
        // Thunder from storms near and far: the flash now, the sound later.
        if t >= self.timers.thunder {
            self.timers.thunder = t + 20;
            let tf = t as f64;
            for s in &self.storms {
                if !s.kind.convective() {
                    continue;
                }
                let c = s.position(tf);
                let d = (c[0] - cam[0]).hypot(c[1] - cam[2]);
                if d > 15_000.0 {
                    continue;
                }
                let p = 0.12 * s.intensity(tf) * s.strength as f64 * if s.kind == StormKind::Cell { 0.6 } else { 1.0 };
                if !self.rng.chance(p) {
                    continue;
                }
                let delay = ((d / 343.0) * 20.0).min(1200.0) as i32;
                let vol = (1.6 * (1.0 - d / 15_000.0)).max(0.15) as f32;
                host.play_local_sound("minecraft:entity.lightning_bolt.thunder", toward(cam, c, d.min(48.0)), vol, self.rng.range(0.55, 0.9) as f32, delay);
                if d < 8000.0 {
                    self.flash = self.flash.max((0.6 * (1.0 - d / 8000.0)) as f32);
                }
            }
        }
    }

    /// One radar sweep around the player.
    fn sweep(&mut self) {
        let n = self.budget().radar_cells;
        let product = if self.cfg.velocity { Product::Velocity } else { Product::Reflectivity };
        let mut img = std::mem::take(&mut self.radar);
        radar::image(&self.storms, &self.tors, self.env.wind, [self.cam[0], self.cam[2]], self.cfg.radar_range, n, product, self.t as f64, &mut img);
        self.radar = img;
        self.radar_n = n;
        self.radar_t = self.t;
    }

    /// `/radar ...`, typed on the client.
    pub fn perform_command(&mut self, host: &mut dyn Host, command: &str) {
        if !self.cfg_loaded {
            self.load_config(host);
        }
        let words: Vec<&str> = command.split_whitespace().collect();
        let msg = match words.get(1).copied() {
            None => {
                self.cfg.radar = !self.cfg.radar;
                format!("Radar {}", if self.cfg.radar { "on" } else { "off" })
            }
            Some("vel" | "velocity") => {
                self.cfg.velocity = true;
                "Radar: velocity (green toward you, red away)".into()
            }
            Some("ref" | "reflectivity") => {
                self.cfg.velocity = false;
                "Radar: reflectivity".into()
            }
            Some("range") => match words.get(2).and_then(|r| r.parse::<f64>().ok()) {
                Some(r) if self.cfg.set("radar_range", &r.to_string()) => format!("Radar range {:.1} km", r / 1000.0),
                _ => "Usage: /radar range <500-20000>".into(),
            },
            Some("quality") => match words.get(2).and_then(|q| Quality::parse(q)) {
                Some(q) => {
                    self.cfg.quality = q;
                    format!("Tempest quality: {}", q.name())
                }
                None => "Usage: /radar quality <low|medium|high>".into(),
            },
            Some("info") => {
                self.cfg.info = !self.cfg.info;
                format!("Weather info {}", if self.cfg.info { "on" } else { "off" })
            }
            Some("set") if words.len() >= 4 => {
                let v = words[3..].join(" ");
                if self.cfg.set(words[2], &v) {
                    if words[2] == "seed" || words[2] == "year_days" {
                        self.spawner = Spawner::new(self.cfg.seed, self.cfg.year_days);
                        self.local_at = i64::MIN;
                    }
                    format!("Tempest: {} = {v}", words[2])
                } else {
                    format!("Tempest: unknown setting or value: {} {v}", words[2])
                }
            }
            _ => "/radar [vel|ref|range <m>|quality <low|medium|high>|info|set <key> <value>]".into(),
        };
        self.radar_t = i64::MIN;
        self.save_config();
        host.feedback(Text::literal(msg));
    }
}

/// 0 by day, 1 at midnight.
pub fn night_factor(day_time: i64) -> f64 {
    let tod = day_time.rem_euclid(DAY) as f64 / DAY as f64;
    // Sunrise at 0, noon at 0.25, sunset at 0.5, midnight at 0.75.
    let sun = (std::f64::consts::TAU * tod).sin();
    (0.5 - sun * 1.5).clamp(0.0, 1.0)
}

/// A point `dist` blocks from `cam` toward `to` (x, z), for directional sounds.
fn toward(cam: [f64; 3], to: [f64; 2], dist: f64) -> [f64; 3] {
    let dx = to[0] - cam[0];
    let dz = to[1] - cam[2];
    let d = dx.hypot(dz).max(1e-6);
    [cam[0] + dx / d * dist, cam[1] + 4.0, cam[2] + dz / d * dist]
}
