//! Tempest's client logic, written against `Io` so it can be tested on the
//! host with a fake game; `lib.rs` gives it the real one.

use crate::cfg::Cfg;
use crate::cmds;
use crate::sim::climate::{season_name, Env};
use crate::sim::damage;
use crate::sim::field::{self, compass_from, compass_to, Sample};
use crate::sim::net;
use crate::sim::precip::{rain_rate, Precip};
use crate::sim::radar::{self, dbz_color, Product};
use crate::sim::rng::Rng;
use crate::sim::spawn::Spawner;
use crate::sim::storm::{hurricane_category, StormDef, StormKind, TornadoState};

pub const OVERWORLD: &str = "minecraft:overworld";

#[derive(Clone, Debug, PartialEq)]
pub struct Player {
    pub pos: [f64; 3],
    pub dimension: String,
}

/// A HUD shape, as the game's `render` interface draws them.
#[derive(Clone, Debug, PartialEq)]
pub enum Shape {
    Fill { x: f32, y: f32, w: f32, h: f32, argb: u32 },
    Text { x: f32, y: f32, text: String, rgb: u32, centered: bool },
}

/// What Tempest needs from the game.
pub trait Io {
    fn player(&self) -> Option<Player>;
    /// (game time, day time)
    fn clock(&self) -> Option<(u64, u64)>;
    fn run(&mut self, lines: &[String]);
    fn set_corner(&mut self, w: f32, h: f32, shapes: &[Shape]);
    fn clear_corner(&mut self);
    fn set_hud(&mut self, shapes: &[Shape]);
    fn gui_size(&self) -> (f32, f32);
    fn shake(&mut self, strength: f32, seconds: f32);
    fn load(&self, key: &str) -> Option<Vec<u8>>;
    fn save(&mut self, key: &str, value: &[u8]);
    fn chat(&mut self, text: &str);
}

#[derive(Clone, Copy, Debug)]
struct Warning {
    ef: u8,
    dist: f64,
    dir: &'static str,
    confirmed: bool,
}

pub struct Tempest {
    pub cfg: Cfg,
    spawner: Spawner,
    manual: Vec<StormDef>,
    suppressed: Vec<u64>,
    natural: Vec<StormDef>,
    pub storms: Vec<StormDef>,
    pub tors: Vec<(usize, TornadoState)>,
    refreshed: i64,
    anchor: [f64; 2],
    pub t: i64,
    day: i64,
    pub pos: [f64; 3],
    env: Env,
    here: Sample,
    radar: Vec<u32>,
    radar_n: usize,
    radar_t: i64,
    warning: Option<Warning>,
    hud_shown: bool,
    corner_shown: bool,
    vanilla: u8,
    vanilla_t: i64,
    ticks: u64,
    rng: Rng,
    queue: Vec<String>,
    timers: [i64; 6],
}

const T_WIND: usize = 0;
const T_ROAR: usize = 1;
const T_SIREN: usize = 2;
const T_HAIL: usize = 3;
const T_THUNDER: usize = 4;
const T_HURT: usize = 5;

impl Tempest {
    pub fn new(io: &dyn Io) -> Self {
        let cfg = io.load("config").map(|b| Cfg::parse(&String::from_utf8_lossy(&b))).unwrap_or_default();
        let manual = io.load("storms").and_then(|b| net::decode(&b)).map(|s| s.storms).unwrap_or_default();
        let suppressed = io
            .load("suppressed")
            .map(|b| String::from_utf8_lossy(&b).lines().filter_map(|l| l.trim().parse().ok()).collect())
            .unwrap_or_default();
        let mut t = Tempest {
            spawner: Spawner::new(cfg.seed, cfg.year_days),
            cfg,
            manual,
            suppressed,
            natural: Vec::new(),
            storms: Vec::new(),
            tors: Vec::new(),
            refreshed: i64::MIN,
            anchor: [0.0, 0.0],
            t: 0,
            day: 0,
            pos: [0.0; 3],
            env: Env::default(),
            here: Sample { tornado_dist: f64::INFINITY, ..Sample::default() },
            radar: Vec::new(),
            radar_n: 0,
            radar_t: i64::MIN,
            warning: None,
            hud_shown: false,
            corner_shown: false,
            vanilla: 255,
            vanilla_t: i64::MIN,
            ticks: 0,
            rng: Rng::new(0x7E57_AD01),
            queue: Vec::new(),
            timers: [0; 6],
        };
        t.apply_cfg();
        t
    }

    fn apply_cfg(&mut self) {
        self.spawner = Spawner::new(self.cfg.seed, self.cfg.year_days);
        self.spawner.rate = self.cfg.storm_rate;
        self.spawner.tornado_rate = self.cfg.tornado_rate;
        self.refreshed = i64::MIN;
        self.radar_t = i64::MIN;
    }

    fn save_all(&self, io: &mut dyn Io) {
        io.save("config", self.cfg.to_text().as_bytes());
        let snap = net::Snapshot { seed: self.cfg.seed, year_days: self.cfg.year_days as f32, game_time: self.t, storms: self.manual.clone() };
        io.save("storms", &net::encode(&snap));
        let ids: Vec<String> = self.suppressed.iter().map(|i| i.to_string()).collect();
        io.save("suppressed", ids.join("\n").as_bytes());
    }

    fn budget(&self) -> usize {
        self.cfg.command_budget()
    }

    fn push(&mut self, cmd: String) -> bool {
        if self.queue.len() < self.budget() {
            self.queue.push(cmd);
            true
        } else {
            false
        }
    }

    pub fn tick(&mut self, io: &mut dyn Io) {
        let (Some(p), Some((gt, dt))) = (io.player(), io.clock()) else { return };
        if p.dimension != OVERWORLD {
            self.hide(io);
            return;
        }
        self.ticks += 1;
        self.t = gt as i64;
        self.day = dt as i64;
        self.pos = p.pos;
        let t = self.t;
        let tf = t as f64;

        let moved = (self.anchor[0] - p.pos[0]).hypot(self.anchor[1] - p.pos[2]);
        if t.saturating_sub(self.refreshed) >= 100 || t < self.refreshed || moved > 1000.0 {
            let radius = self.cfg.quality.budget().draw_distance.max(self.cfg.radar_range * 1.5);
            self.natural.clear();
            self.spawner.storms_near(p.pos[0], p.pos[2], t, radius, &mut self.natural);
            let sup = &self.suppressed;
            self.natural.retain(|s| !sup.contains(&s.id));
            let before = self.manual.len();
            self.manual.retain(|s| s.t0 > t || s.alive(tf));
            if before != self.manual.len() {
                self.save_all(io);
            }
            self.refreshed = t;
            self.anchor = [p.pos[0], p.pos[2]];
        }
        self.storms.clear();
        self.storms.extend(self.natural.iter().chain(&self.manual).filter(|s| s.alive(tf)).copied());
        self.tors = field::tornadoes(&self.storms, tf);
        self.env = self.spawner.climate.sample(p.pos[0], p.pos[2], tf, self.day as f64);
        self.here = field::sample(&self.storms, &self.tors, &self.env, p.pos[0], p.pos[2], tf);
        self.update_warning();

        self.queue.clear();
        self.tornado_effects(io);
        if self.ticks % 5 == 0 {
            self.damage();
        }
        if self.ticks % 20 == 3 {
            self.lightning();
        }
        if self.ticks % 40 == 11 {
            self.winter();
        }
        if self.ticks % 200 == 17 {
            self.vanilla_weather();
        }
        self.sounds();
        if !self.queue.is_empty() {
            let q = std::mem::take(&mut self.queue);
            io.run(&q);
        }
        if self.cfg.radar && (t.saturating_sub(self.radar_t) >= self.cfg.quality.budget().radar_refresh as i64 || t < self.radar_t) {
            self.sweep();
        }
        if self.ticks % 10 == 0 || self.radar_t == t {
            self.draw(io);
        }
    }

    fn hide(&mut self, io: &mut dyn Io) {
        if self.corner_shown {
            io.clear_corner();
            self.corner_shown = false;
        }
        if self.hud_shown {
            io.set_hud(&[]);
            self.hud_shown = false;
        }
    }

    fn nearest_tornado(&self) -> Option<TornadoState> {
        let p = self.pos;
        self.tors
            .iter()
            .map(|(_, t)| *t)
            .min_by(|a, b| {
                let da = (a.pos[0] - p[0]).hypot(a.pos[1] - p[2]);
                let db = (b.pos[0] - p[0]).hypot(b.pos[1] - p[2]);
                da.total_cmp(&db)
            })
    }

    fn update_warning(&mut self) {
        let here = [self.pos[0], self.pos[2]];
        let tf = self.t as f64;
        let mut w: Option<Warning> = None;
        for (_, tor) in &self.tors {
            let d = (tor.pos[0] - here[0]).hypot(tor.pos[1] - here[1]);
            if d < 3000.0 && w.is_none_or(|o| d < o.dist) {
                w = Some(Warning { ef: tor.ef_peak, dist: d, dir: compass_to(here, tor.pos), confirmed: true });
            }
        }
        if w.is_none() {
            for s in &self.storms {
                let (Some(td), Some(m)) = (s.tornado, s.meso_pos(tf)) else { continue };
                let until = s.t0 as f64 + td.start as f64 - tf;
                let d = (m[0] - here[0]).hypot(m[1] - here[1]);
                if (0.0..1200.0).contains(&until) && d < 4000.0 && w.is_none_or(|o| d < o.dist) {
                    w = Some(Warning { ef: td.ef, dist: d, dir: compass_to(here, m), confirmed: false });
                }
            }
        }
        self.warning = w;
    }

    /// Funnel and debris particles, the pull on mobs and items, and what
    /// the player feels in the core.
    fn tornado_effects(&mut self, io: &mut dyn Io) {
        let Some(tor) = self.nearest_tornado() else { return };
        let p = self.pos;
        let d = (tor.pos[0] - p[0]).hypot(tor.pos[1] - p[2]);
        let t = self.t as f64;
        if self.cfg.shake && self.ticks % 10 == 0 {
            let k = (1.0 - d / (tor.radius * 8.0 + 60.0)).clamp(0.0, 1.0);
            if k > 0.0 {
                io.shake((k * k * tor.vmax / 100.0).min(1.0) as f32, 0.8);
            }
        }
        if self.cfg.particles && d < 700.0 {
            let per_tick = match self.cfg.quality {
                crate::sim::config::Quality::Low => 2,
                crate::sim::config::Quality::Medium => 4,
                crate::sim::config::Quality::High => 7,
            };
            let height = 95.0;
            let rope = if tor.life_frac > 0.75 { 0.45 } else { 1.0 };
            for _ in 0..per_tick {
                let f = self.rng.f64();
                let rf = tor.radius * (0.18 + 0.95 * f.powf(2.2)) * rope;
                let sway = tor.radius * 0.7 * f * if rope < 1.0 { 2.5 } else { 1.0 };
                let ox = (f * 3.1 + t * 0.021).sin() * sway;
                let oz = (f * 2.3 + t * 0.017).cos() * sway;
                let kind = if f < 0.15 { "minecraft:large_smoke" } else { "minecraft:campfire_cosy_smoke" };
                let cmd = cmds::particle(kind, tor.pos[0] + ox, tor.pos[1] + oz, f * height, rf * 0.5, 2.0, rf * 0.5, 0.01, 4 + (rf / 6.0).min(8.0) as u32);
                self.push(cmd);
            }
            if self.ticks % 2 == 0 {
                let r = tor.radius * 1.4;
                let cmd = cmds::particle("minecraft:poof", tor.pos[0], tor.pos[1], 1.5, r, 1.5 + tor.ef_now as f64, r, 0.25, 6 + 2 * tor.ef_now as u32);
                self.push(cmd);
            }
        }
        if self.cfg.entity_push && d < 250.0 + tor.radius * 4.0 && self.ticks % 2 == 0 {
            let reach = tor.radius * 4.0;
            let k = (tor.vmax / 60.0).min(1.6);
            let cmd = cmds::suck(tor.pos[0], p[1], tor.pos[1], reach, 0.45 * k, 0.12 * k, 0.35 * k);
            self.push(cmd);
        }
        if self.cfg.hurt && d < tor.radius * 1.1 && self.t >= self.timers[T_HURT] {
            self.timers[T_HURT] = self.t + 20;
            if tor.vmax >= 45.0 {
                self.push(cmds::hurt(((tor.vmax - 40.0) / 12.0).clamp(1.0, 8.0)));
            }
            if tor.vmax >= 50.0 {
                self.push(cmds::lift(1, ((tor.vmax - 40.0) / 20.0) as u32));
            }
        }
    }

    /// Wind damage near the player, as `fill ... replace` over small squares.
    fn damage(&mut self) {
        if !self.cfg.damage || self.storms.is_empty() {
            return;
        }
        let p = self.pos;
        let tf = self.t as f64;
        let mut samples: Vec<(f64, f64, i32)> = Vec::new();
        for (_, tor) in &self.tors {
            let d = (tor.pos[0] - p[0]).hypot(tor.pos[1] - p[2]);
            if d > 220.0 + tor.radius * 3.0 {
                continue; // outside the loaded chunks, most likely
            }
            let zone = tor.radius * 2.4;
            for _ in 0..4 {
                let a = self.rng.f64() * std::f64::consts::TAU;
                let r = zone * self.rng.f64().sqrt();
                samples.push((tor.pos[0] + r * a.cos(), tor.pos[1] + r * a.sin(), 2));
            }
        }
        let (w, _) = field::wind_at(&self.storms, &self.tors, self.env.wind, p[0], p[2], tf);
        if w[0].hypot(w[1]) >= 27.0 {
            for _ in 0..3 {
                let a = self.rng.f64() * std::f64::consts::TAU;
                let r = 80.0 * self.rng.f64().sqrt();
                samples.push((p[0] + r * a.cos(), p[2] + r * a.sin(), 1));
            }
        }
        let mut plan = Vec::new();
        for (x, z, half) in samples {
            let (w, _) = field::wind_at(&self.storms, &self.tors, self.env.wind, x, z, tf);
            plan.clear();
            damage::plan_column(w[0].hypot(w[1]), &mut self.rng, &mut plan);
            // A few of the column's edits at a time: the rest come on later passes.
            for _ in 0..plan.len().min(3) {
                let i = (self.rng.f64() * plan.len() as f64) as usize;
                let run = plan.swap_remove(i);
                if !self.push(cmds::damage_fill(x.floor() as i32, z.floor() as i32, half, &run)) {
                    return;
                }
            }
        }
    }

    fn lightning(&mut self) {
        if !self.cfg.lightning || self.storms.is_empty() {
            return;
        }
        let p = self.pos;
        let a = self.rng.f64() * std::f64::consts::TAU;
        let r = 24.0 + 150.0 * self.rng.f64().sqrt();
        let (x, z) = (p[0] + r * a.cos(), p[2] + r * a.sin());
        let Some((i, dbz)) = field::dominant(&self.storms, x, z, self.t as f64) else { return };
        if self.storms[i].kind.convective() && dbz >= 45.0 && self.rng.chance(0.35 * ((dbz - 45.0) / 25.0).min(1.0)) {
            self.push(cmds::lightning(x.floor() as i32, z.floor() as i32));
        }
    }

    fn winter(&mut self) {
        if !self.cfg.snow || !self.cfg.damage {
            return;
        }
        let n = match self.here.precip {
            Precip::Snow => 2 + (self.here.dbz / 12.0) as usize,
            Precip::FreezingRain | Precip::Sleet => 2,
            _ => return,
        };
        let mut plan = Vec::new();
        for _ in 0..n {
            let a = self.rng.f64() * std::f64::consts::TAU;
            let r = 48.0 * self.rng.f64().sqrt();
            let (x, z) = ((self.pos[0] + r * a.cos()).floor() as i32, (self.pos[2] + r * a.sin()).floor() as i32);
            plan.clear();
            if self.here.precip == Precip::Snow {
                damage::snow_column(&mut plan);
            } else {
                damage::freeze_column(&mut plan);
            }
            for run in &plan {
                self.push(cmds::damage_fill(x, z, 1, run));
            }
        }
    }

    fn vanilla_weather(&mut self) {
        if !self.cfg.vanilla_weather {
            return;
        }
        let want = match field::dominant(&self.storms, self.pos[0], self.pos[2], self.t as f64) {
            Some((i, dbz)) if dbz >= 45.0 && self.storms[i].kind.convective() => 2,
            Some((_, dbz)) if dbz >= 20.0 => 1,
            _ => 0,
        };
        if want != self.vanilla || self.t.saturating_sub(self.vanilla_t) > 9000 || self.t < self.vanilla_t {
            if self.push(cmds::weather(want).to_string()) {
                self.vanilla = want;
                self.vanilla_t = self.t;
            }
        }
    }

    fn sounds(&mut self) {
        if !self.cfg.sounds {
            return;
        }
        let t = self.t;
        let p = self.pos;
        let speed = self.here.speed();
        if speed > 16.0 && t >= self.timers[T_WIND] {
            self.timers[T_WIND] = t + 50;
            let vol = ((speed - 16.0) / 40.0).clamp(0.05, 0.8);
            let pitch = 0.55 + self.rng.f64() * 0.1;
            self.push(cmds::sound("minecraft:item.elytra.flying", "weather", [p[0], p[1] + 2.0, p[2]], vol, pitch));
        }
        if let Some(tor) = self.nearest_tornado() {
            let d = (tor.pos[0] - p[0]).hypot(tor.pos[1] - p[2]);
            if d < 900.0 && t >= self.timers[T_ROAR] {
                self.timers[T_ROAR] = t + 40;
                let at = toward(p, tor.pos, d.min(24.0));
                let vol = 3.0 * (1.0 - d / 900.0) + 0.3;
                self.push(cmds::sound("minecraft:item.elytra.flying", "weather", at, vol, 0.5));
                self.push(cmds::sound("minecraft:weather.rain", "weather", at, vol * 0.8, 0.5));
            }
        }
        if let Some(w) = self.warning {
            if self.cfg.sirens && t >= self.timers[T_SIREN] {
                self.timers[T_SIREN] = t + if w.confirmed { 120 } else { 200 };
                self.push(cmds::sound("minecraft:event.raid.horn", "master", [p[0] + 40.0, p[1] + 10.0, p[2] + 30.0], 1.2, 0.85));
            }
        }
        if self.here.precip == Precip::Hail && t >= self.timers[T_HAIL] {
            self.timers[T_HAIL] = t + 8;
            let at = [p[0] + self.rng.range(-4.0, 4.0), p[1], p[2] + self.rng.range(-4.0, 4.0)];
            let pitch = self.rng.range(1.3, 1.9);
            self.push(cmds::sound("minecraft:block.gravel.hit", "weather", at, 0.4, pitch));
            if self.cfg.hurt && self.here.dbz >= 62.0 && self.rng.chance(0.08) {
                self.push(cmds::hurt_if_exposed(1.0));
            }
        }
        if t >= self.timers[T_THUNDER] {
            self.timers[T_THUNDER] = t + 20;
            let tf = t as f64;
            for i in 0..self.storms.len() {
                let s = self.storms[i];
                if !s.kind.convective() {
                    continue;
                }
                let c = s.position(tf);
                let d = (c[0] - p[0]).hypot(c[1] - p[2]);
                if d < 300.0 || d > 12_000.0 {
                    continue; // near ones have real lightning
                }
                let chance = 0.1 * s.intensity(tf) * s.strength as f64;
                if self.rng.chance(chance) {
                    let vol = (1.4 * (1.0 - d / 12_000.0)).max(0.2);
                    let pitch = self.rng.range(0.55, 0.85);
                    self.push(cmds::sound("minecraft:entity.lightning_bolt.thunder", "weather", toward(p, c, 32.0), vol, pitch));
                }
            }
        }
    }

    fn sweep(&mut self) {
        let n = self.cfg.quality.budget().radar_cells;
        let product = if self.cfg.velocity { Product::Velocity } else { Product::Reflectivity };
        let mut img = std::mem::take(&mut self.radar);
        radar::image(&self.storms, &self.tors, self.env.wind, [self.pos[0], self.pos[2]], self.cfg.radar_range, n, product, self.t as f64, &mut img);
        self.radar = img;
        self.radar_n = n;
        self.radar_t = self.t;
    }

    /// The radar and readout in the top-right corner; the warning banner.
    fn draw(&mut self, io: &mut dyn Io) {
        let mut shapes = Vec::new();
        let width = 136.0f32;
        let mut y = 0.0f32;
        if self.cfg.radar && self.radar_n > 0 && self.radar.len() == self.radar_n * self.radar_n {
            let n = self.radar_n;
            let cell = 2.0f32;
            let side = n as f32 * cell;
            let ox = (width - side) / 2.0;
            shapes.push(Shape::Fill { x: 0.0, y: 0.0, w: width, h: side + 14.0, argb: 0xA000_0000 });
            let label = format!("{} {:.1} km", if self.cfg.velocity { "VEL" } else { "REF" }, self.cfg.radar_range / 1000.0);
            shapes.push(Shape::Text { x: 3.0, y: 2.0, text: label, rgb: 0xFFFFFF, centered: false });
            for row in 0..n {
                let line = &self.radar[row * n..(row + 1) * n];
                let mut col = 0;
                while col < n {
                    let color = line[col];
                    let start = col;
                    while col < n && line[col] == color {
                        col += 1;
                    }
                    if color != 0 && shapes.len() < 900 {
                        shapes.push(Shape::Fill { x: ox + start as f32 * cell, y: 12.0 + row as f32 * cell, w: (col - start) as f32 * cell, h: cell, argb: color });
                    }
                }
            }
            let mid = side / 2.0;
            shapes.push(Shape::Fill { x: ox + mid - 1.0, y: 12.0 + mid - 1.0, w: 2.0, h: 2.0, argb: 0xFFFF_FFFF });
            let range = self.cfg.radar_range;
            for (_, tor) in &self.tors {
                let dx = (tor.pos[0] - self.pos[0]) / range;
                let dz = (tor.pos[1] - self.pos[2]) / range;
                if dx.abs() < 1.0 && dz.abs() < 1.0 {
                    let px = ox + mid + dx as f32 * mid;
                    let pz = 12.0 + mid + dz as f32 * mid;
                    shapes.push(Shape::Fill { x: px - 2.5, y: pz - 2.5, w: 5.0, h: 5.0, argb: 0xFFFF_FFFF });
                    shapes.push(Shape::Fill { x: px - 1.5, y: pz - 1.5, w: 3.0, h: 3.0, argb: 0xFFFF_0000 });
                }
            }
            y = side + 14.0;
        }
        if self.cfg.info {
            let e = &self.env;
            let h = &self.here;
            let risk = e.risk();
            let lines = [
                (format!("{:.0}°C  Td {:.0}°C  {}", e.temp_c, e.dew_c, risk.label()), risk.rgb()),
                (format!("Wind {:.0} m/s {}", h.speed(), compass_from(h.wind)), 0xFFFFFF),
                (format!("CAPE {:.0} SRH {:.0} {:.0}kt", e.cape, e.srh, e.shear_kt), 0xD0D0D0),
                (
                    if h.dbz >= 5.0 {
                        format!("{} {:.0}dBZ {:.0}mm/h", h.precip.label(), h.dbz, rain_rate(h.dbz))
                    } else {
                        "No precipitation".into()
                    },
                    dbz_color(h.dbz.max(5.0)) & 0xFF_FFFF,
                ),
            ];
            let hgt = lines.len() as f32 * 10.0 + 4.0;
            shapes.push(Shape::Fill { x: 0.0, y, w: width, h: hgt, argb: 0x8000_0000 });
            for (i, (text, rgb)) in lines.into_iter().enumerate() {
                shapes.push(Shape::Text { x: 3.0, y: y + 2.0 + i as f32 * 10.0, text, rgb, centered: false });
            }
            y += hgt;
        }
        if shapes.is_empty() {
            if self.corner_shown {
                io.clear_corner();
                self.corner_shown = false;
            }
        } else {
            io.set_corner(width, y, &shapes);
            self.corner_shown = true;
        }
        match self.warning {
            Some(w) => {
                let text = if w.confirmed {
                    format!("TORNADO WARNING  EF{}  {:.1} km {}", w.ef, w.dist / 1000.0, w.dir)
                } else {
                    format!("Tornado possible: rotating storm {:.1} km {}", w.dist / 1000.0, w.dir)
                };
                let (sw, _) = io.gui_size();
                let tw = text.chars().count() as f32 * 6.0;
                let blink = (self.t / 10) % 2 == 0;
                let banner = [
                    Shape::Fill { x: (sw - tw) / 2.0 - 4.0, y: 18.0, w: tw + 8.0, h: 12.0, argb: if w.confirmed && blink { 0xC0B0_0000 } else { 0xA040_0000 } },
                    Shape::Text { x: sw / 2.0, y: 20.0, text, rgb: if w.confirmed { 0xFFFFFF } else { 0xFFD080 }, centered: true },
                ];
                io.set_hud(&banner);
                self.hud_shown = true;
            }
            None if self.hud_shown => {
                io.set_hud(&[]);
                self.hud_shown = false;
            }
            None => {}
        }
    }

    /// `/tempest ...`
    pub fn command(&mut self, io: &mut dyn Io, args: &str) -> Result<Option<String>, String> {
        let words: Vec<&str> = args.split_whitespace().collect();
        let here = [self.pos[0], self.pos[2]];
        let tf = self.t as f64;
        let reply = match words.first().copied() {
            Some("spawn") => {
                let Some(kind) = words.get(1).and_then(|k| StormKind::parse(k)) else {
                    return Err("Usage: /tempest spawn <cell|supercell|tornado|squall|hurricane|blizzard> [EF 0-5]".into());
                };
                let mut ef = words.get(2).and_then(|e| e.trim_start_matches(['e', 'E', 'f', 'F']).parse::<u8>().ok());
                if words[1] == "tornado" && ef.is_none() {
                    ef = Some(2);
                }
                let lead = match kind {
                    StormKind::Cell => 600,
                    StormKind::Supercell => 1200,
                    StormKind::Squall => 1600,
                    StormKind::Cyclone => 6000,
                    StormKind::Blizzard => 1200,
                };
                let s = self.spawner.manual(kind, here, self.t, ef, lead);
                self.manual.push(s);
                self.refreshed = i64::MIN;
                self.save_all(io);
                let extra = s.tornado.map(|t| format!(", tornado EF{}", t.ef)).unwrap_or_default();
                format!("{} spawned{extra}; it reaches you in ~{} s.", kind.name(), lead / 20)
            }
            Some("clear") => {
                self.suppressed.extend(self.natural.iter().map(|s| s.id));
                self.manual.clear();
                self.refreshed = i64::MIN;
                self.save_all(io);
                "Storms cleared.".into()
            }
            Some("list") => {
                let mut rows: Vec<(f64, String)> = self
                    .storms
                    .iter()
                    .map(|s| {
                        let c = s.position(tf);
                        let d = (c[0] - here[0]).hypot(c[1] - here[1]);
                        let tor = s.tornado_at(tf).map(|t| format!(" TORNADO EF{}", t.ef_now)).unwrap_or_default();
                        (d, format!("{} {:.1} km {} ({:.0}%){tor}", s.kind.name(), d / 1000.0, compass_to(here, c), 100.0 * s.intensity(tf)))
                    })
                    .collect();
                rows.sort_by(|a, b| a.0.total_cmp(&b.0));
                if rows.is_empty() {
                    "No storms nearby.".into()
                } else {
                    for (_, r) in rows.iter().skip(1).take(7) {
                        io.chat(r);
                    }
                    rows[0].1.clone()
                }
            }
            Some("info") => {
                let e = self.spawner.climate.sample(here[0], here[1], tf, self.day as f64);
                let mut s = format!(
                    "T {:.0}°C Td {:.0}°C CAPE {:.0} shear {:.0} kt SRH {:.0} STP {:.1} risk {} ({})",
                    e.temp_c, e.dew_c, e.cape, e.shear_kt, e.srh, e.stp, e.risk().label(), season_name(e.season)
                );
                for st in self.storms.iter().filter(|s| s.kind == StormKind::Cyclone) {
                    let c = st.position(tf);
                    let w = st.wind(c[0] + st.aux as f64, c[1], tf);
                    s.push_str(&format!("; hurricane cat {} {:.1} km {}", hurricane_category(w[0].hypot(w[1])), (c[0] - here[0]).hypot(c[1] - here[1]) / 1000.0, compass_to(here, c)));
                }
                s
            }
            Some("radar") => {
                self.cfg.radar = match words.get(1) {
                    Some(v) => v.parse::<bool>().unwrap_or(!self.cfg.radar) && *v != "off",
                    None => !self.cfg.radar,
                };
                self.save_all(io);
                format!("Radar {}", if self.cfg.radar { "on" } else { "off" })
            }
            Some("vel" | "velocity") => {
                self.cfg.velocity = true;
                self.radar_t = i64::MIN;
                self.save_all(io);
                "Radar: velocity (green toward you, red away)".into()
            }
            Some("ref" | "reflectivity") => {
                self.cfg.velocity = false;
                self.radar_t = i64::MIN;
                self.save_all(io);
                "Radar: reflectivity".into()
            }
            Some("range" | "quality") if words.len() >= 2 => {
                let key = if words[0] == "range" { "radar_range" } else { "quality" };
                if !self.cfg.set(key, words[1]) {
                    return Err(format!("Bad value: {}", words[1]));
                }
                self.radar_t = i64::MIN;
                self.save_all(io);
                format!("{key} = {}", words[1])
            }
            Some("set") if words.len() >= 3 => {
                let v = words[2..].join(" ");
                if !self.cfg.set(words[1], &v) {
                    return Err(format!("Unknown setting or value: {} {v}", words[1]));
                }
                self.apply_cfg();
                self.save_all(io);
                format!("{} = {v}", words[1])
            }
            Some("settings") => self.cfg.to_text().replace('\n', "; "),
            _ => "/tempest spawn <kind> [ef] | list | info | clear | radar | vel | ref | range <m> | quality <low|medium|high> | set <key> <value> | settings".into(),
        };
        Ok(Some(reply))
    }
}

/// A point `dist` blocks from `from` toward `to` (x, z), for sounds.
fn toward(from: [f64; 3], to: [f64; 2], dist: f64) -> [f64; 3] {
    let dx = to[0] - from[0];
    let dz = to[1] - from[1];
    let d = dx.hypot(dz).max(1e-6);
    [from[0] + dx / d * dist, from[1] + 4.0, from[2] + dz / d * dist]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[derive(Default)]
    struct Fake {
        t: u64,
        ran: Vec<String>,
        corner: Vec<Shape>,
        hud: Vec<Shape>,
        shakes: usize,
        store: HashMap<String, Vec<u8>>,
        chat: Vec<String>,
        max_batch: usize,
    }
    impl Io for Fake {
        fn player(&self) -> Option<Player> {
            Some(Player { pos: [0.0, 70.0, 0.0], dimension: OVERWORLD.into() })
        }
        fn clock(&self) -> Option<(u64, u64)> {
            Some((self.t, 6000))
        }
        fn run(&mut self, lines: &[String]) {
            self.max_batch = self.max_batch.max(lines.len());
            self.ran.extend(lines.iter().cloned());
        }
        fn set_corner(&mut self, w: f32, h: f32, shapes: &[Shape]) {
            assert!(w > 0.0 && h > 0.0 && shapes.len() <= 1024);
            self.corner = shapes.to_vec();
        }
        fn clear_corner(&mut self) {
            self.corner.clear();
        }
        fn set_hud(&mut self, shapes: &[Shape]) {
            self.hud = shapes.to_vec();
        }
        fn gui_size(&self) -> (f32, f32) {
            (427.0, 240.0)
        }
        fn shake(&mut self, s: f32, secs: f32) {
            assert!((0.0..=1.0).contains(&s) && secs <= 10.0);
            self.shakes += 1;
        }
        fn load(&self, key: &str) -> Option<Vec<u8>> {
            self.store.get(key).cloned()
        }
        fn save(&mut self, key: &str, value: &[u8]) {
            self.store.insert(key.into(), value.to_vec());
        }
        fn chat(&mut self, text: &str) {
            self.chat.push(text.into());
        }
    }

    #[test]
    fn a_spawned_tornado_does_everything_within_budget() {
        let mut io = Fake { t: 100_000, ..Fake::default() };
        let mut m = Tempest::new(&io);
        m.command(&mut io, "set storm_rate 0").unwrap();
        m.tick(&mut io);
        let r = m.command(&mut io, "spawn tornado 4").unwrap().unwrap();
        assert!(r.contains("EF4"), "{r}");
        for _ in 0..1300 {
            io.t += 1;
            m.tick(&mut io);
        }
        assert!(io.max_batch <= m.cfg.command_budget());
        let has = |s: &str| io.ran.iter().any(|c| c.contains(s));
        assert!(has("run fill "), "damage");
        assert!(has("campfire_cosy_smoke"), "funnel");
        assert!(has("raid.horn"), "siren");
        assert!(has("damage @s"), "hurt");
        assert!(has("tp @s ^"), "suction");
        assert!(has("weather "), "vanilla weather");
        assert!(io.shakes > 0);
        assert!(!io.corner.is_empty(), "radar and readout");
        assert!(io.store.contains_key("storms") && io.store.contains_key("config"));
        // Saved storms come back.
        let again = Tempest::new(&io);
        assert_eq!(again.manual.len(), 1);
    }

    #[test]
    fn commands_and_dimensions() {
        let mut io = Fake { t: 50_000, ..Fake::default() };
        let mut m = Tempest::new(&io);
        m.tick(&mut io);
        assert!(m.command(&mut io, "info").unwrap().unwrap().contains("CAPE"));
        assert!(m.command(&mut io, "spawn nothing").is_err());
        m.command(&mut io, "radar").unwrap();
        assert!(!m.cfg.radar);
        m.command(&mut io, "quality high").unwrap();
        assert_eq!(m.cfg.quality, crate::sim::config::Quality::High);
        m.command(&mut io, "spawn hurricane").unwrap();
        m.tick(&mut io);
        assert!(m.command(&mut io, "list").unwrap().unwrap().contains("hurricane"));
        m.command(&mut io, "clear").unwrap();
        m.tick(&mut io);
        assert_eq!(m.command(&mut io, "list").unwrap().unwrap(), "No storms nearby.");
    }
}
