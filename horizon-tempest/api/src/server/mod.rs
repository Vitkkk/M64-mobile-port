//! The server half: keeps the storm list, damages the world, strikes
//! lightning, steers vanilla rain, pushes entities and tells clients.

use crate::sim::climate::Env;
use crate::sim::config::ServerConfig;
use crate::sim::damage::{self, Surface, FILTERS, PALETTE};
use crate::sim::field;
use crate::sim::net::{self, Snapshot};
use crate::sim::precip::Precip;
use crate::sim::rng::Rng;
use crate::sim::spawn::Spawner;
use crate::sim::storm::{hurricane_category, StormDef, StormKind, TornadoState};
use horizon_client::mod_api::*;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

pub const OVERWORLD: &str = "minecraft:overworld";

/// Filters kept when the full list is refused by the game (an unknown tag).
const SAFE_FILTERS: [u8; 6] = [
    damage::F_LEAVES,
    damage::F_LOGS,
    damage::F_PLANKS,
    damage::F_GRASS_BLOCK,
    damage::F_DIRT,
    damage::F_AIR,
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DamageMode {
    Full,
    Safe,
    Off,
}

#[derive(Clone, Debug)]
struct PlayerInfo {
    name: String,
    pos: [f64; 3],
    dim: String,
    game_mode: i32,
    seen: i64,
    last_sync: i64,
    sync_gen: u64,
}

/// A tornado as entities feel it, copied out of the state.
#[derive(Clone, Copy, Debug)]
pub struct Vortex {
    tor: TornadoState,
}

pub struct ServerState {
    epoch: Option<NativeWorldEpoch>,
    cfg: ServerConfig,
    spawner: Spawner,
    dir: Option<PathBuf>,
    manual: Vec<StormDef>,
    suppressed: HashSet<u64>,
    active: Vec<StormDef>,
    tors: Vec<(usize, TornadoState)>,
    vortices: Vec<Vortex>,
    generation: u64,
    last_refresh: i64,
    anchors: Vec<[f64; 2]>,
    now: i64,
    day: i64,
    players: HashMap<u128, PlayerInfo>,
    rng: Rng,
    vanilla: u8,
    vanilla_at: i64,
    damage_mode: DamageMode,
    ticks: u64,
    /// Every wood type: a bulk edit can afford a run each.
    species: Vec<u8>,
    /// Damage step each tile of a path has had, and tiles still to edit:
    /// (tile, from step, to step, a wind speed of the step).
    tiles: HashMap<(i32, i32), u8>,
    pending: std::collections::VecDeque<((i32, i32), u8, u8, f64)>,
}

impl Default for ServerState {
    fn default() -> Self {
        let cfg = ServerConfig::default();
        ServerState {
            epoch: None,
            spawner: spawner_for(&cfg),
            cfg,
            dir: None,
            manual: Vec::new(),
            suppressed: HashSet::new(),
            active: Vec::new(),
            tors: Vec::new(),
            vortices: Vec::new(),
            generation: 0,
            last_refresh: i64::MIN,
            anchors: Vec::new(),
            now: 0,
            day: 0,
            players: HashMap::new(),
            rng: Rng::new(0x7E57),
            vanilla: 255,
            vanilla_at: i64::MIN,
            damage_mode: DamageMode::Full,
            ticks: 0,
            species: damage::all_species(),
            tiles: HashMap::new(),
            pending: std::collections::VecDeque::new(),
        }
    }
}

fn spawner_for(cfg: &ServerConfig) -> Spawner {
    let mut s = Spawner::new(cfg.seed, cfg.year_days);
    s.rate = cfg.storm_rate;
    s.tornado_rate = cfg.tornado_rate;
    s.enabled = cfg.natural;
    s
}

fn log(msg: &str) {
    eprintln!("[tempest] {msg}");
}

impl ServerState {
    /// Once per server tick: notices a world being opened.
    pub fn on_server_tick(&mut self, host: &mut dyn Host, epoch: NativeWorldEpoch) {
        if self.epoch == Some(epoch) {
            return;
        }
        *self = ServerState { epoch: Some(epoch), ..ServerState::default() };
        self.open_world(host.world_dir().map(|d| d.join("tempest")));
    }

    /// A world opened: its settings and saved storms live in `dir`.
    pub fn open_world(&mut self, dir: Option<PathBuf>) {
        self.dir = dir;
        self.load();
    }

    fn load(&mut self) {
        let Some(dir) = self.dir.clone() else { return };
        let cfg_path = dir.join("server.cfg");
        match std::fs::read_to_string(&cfg_path) {
            Ok(text) => self.cfg = ServerConfig::parse(&text),
            Err(_) => {
                let _ = std::fs::create_dir_all(&dir);
                let _ = std::fs::write(&cfg_path, self.cfg.to_text());
            }
        }
        self.spawner = spawner_for(&self.cfg);
        if let Ok(bytes) = std::fs::read(dir.join("storms.bin")) {
            if let Some(snap) = net::decode(&bytes) {
                self.manual = snap.storms;
            }
        }
        if let Ok(text) = std::fs::read_to_string(dir.join("suppressed.txt")) {
            self.suppressed = text.lines().filter_map(|l| l.trim().parse().ok()).collect();
        }
    }

    fn save(&self) {
        let Some(dir) = &self.dir else { return };
        let _ = std::fs::create_dir_all(dir);
        let _ = std::fs::write(dir.join("server.cfg"), self.cfg.to_text());
        let snap = Snapshot { seed: self.cfg.seed, year_days: self.cfg.year_days as f32, game_time: self.now, storms: self.manual.clone() };
        let _ = std::fs::write(dir.join("storms.bin"), net::encode(&snap));
        let ids: Vec<String> = self.suppressed.iter().map(|i| i.to_string()).collect();
        let _ = std::fs::write(dir.join("suppressed.txt"), ids.join("\n"));
    }

    /// Each player's tick: where they are.
    pub fn on_player_tick(&mut self, uuid: u128, name: &str, pos: [f64; 3], dim: &str, game_mode: i32) {
        let now = self.now;
        let e = self.players.entry(uuid).or_insert_with(|| PlayerInfo {
            name: name.to_owned(),
            pos,
            dim: dim.to_owned(),
            game_mode,
            seen: now,
            last_sync: i64::MIN,
            sync_gen: u64::MAX,
        });
        e.pos = pos;
        e.game_mode = game_mode;
        e.seen = now;
        if e.dim != dim {
            e.dim = dim.to_owned();
            e.sync_gen = u64::MAX;
        }
    }

    pub fn forget_player(&mut self, uuid: u128) {
        self.players.remove(&uuid);
    }

    fn overworld_players(&self) -> impl Iterator<Item = (&u128, &PlayerInfo)> {
        self.players.iter().filter(|(_, p)| p.dim == OVERWORLD)
    }

    fn env_at(&self, x: f64, z: f64) -> Env {
        self.spawner.climate.sample(x, z, self.now as f64, self.day as f64)
    }

    /// After the overworld ticked: everything that touches the world.
    pub fn on_overworld_tick(&mut self, host: &mut dyn Host) {
        let Some(level) = host.level(OVERWORLD) else { return };
        let Some(now) = host.level_game_time(level) else { return };
        self.now = now;
        self.day = host.level_day_time(level).unwrap_or(now);
        self.ticks += 1;
        // Players not heard of for two seconds have gone (or changed level).
        self.players.retain(|_, p| now.saturating_sub(p.seen) < 40 || p.seen > now);
        if self.players.is_empty() {
            return;
        }
        if self.needs_refresh() {
            self.refresh();
        }
        let tf = now as f64;
        self.tors = field::tornadoes(&self.active, tf);
        self.vortices = if self.cfg.entity_push {
            self.tors.iter().map(|(_, tor)| Vortex { tor: *tor }).collect()
        } else {
            Vec::new()
        };
        if self.ticks % 10 == 0 {
            self.damage(host);
        }
        if self.ticks % 20 == 5 {
            self.lightning(host);
            self.hurt_players(host);
        }
        if self.ticks % 40 == 15 {
            self.winter(host);
        }
        if self.ticks % 200 == 7 {
            self.vanilla_weather(host);
        }
        self.sync(host);
    }

    fn needs_refresh(&self) -> bool {
        if self.now.saturating_sub(self.last_refresh) >= 100 || self.now < self.last_refresh {
            return true;
        }
        // A player far from where the list was made.
        self.overworld_players().any(|(_, p)| {
            !self.anchors.iter().any(|a| (a[0] - p.pos[0]).hypot(a[1] - p.pos[2]) < 1500.0)
        })
    }

    fn refresh(&mut self) {
        let t = self.now;
        let radius = self.cfg.sync_radius;
        let mut list = Vec::new();
        let anchors: Vec<[f64; 2]> = self.overworld_players().map(|(_, p)| [p.pos[0], p.pos[2]]).collect();
        // Players close together share one search.
        let mut searched: Vec<[f64; 2]> = Vec::new();
        for a in &anchors {
            if searched.iter().any(|s| (s[0] - a[0]).hypot(s[1] - a[1]) < 1000.0) {
                continue;
            }
            searched.push(*a);
            self.spawner.storms_near(a[0], a[1], t, radius, &mut list);
        }
        list.sort_by_key(|s| s.id);
        list.dedup_by_key(|s| s.id);
        list.retain(|s| !self.suppressed.contains(&s.id));
        let before = self.manual.len();
        self.manual.retain(|s| s.alive(t as f64));
        if self.manual.len() != before {
            self.save();
        }
        list.extend(self.manual.iter().copied());
        let changed = list.len() != self.active.len() || list.iter().zip(&self.active).any(|(a, b)| a.id != b.id);
        if changed {
            self.generation += 1;
        }
        self.active = list;
        self.anchors = searched;
        self.last_refresh = t;
    }

    /// Sends each player the storms near them, now and then or on change.
    fn sync(&mut self, host: &mut dyn Host) {
        let now = self.now;
        let interval = self.cfg.sync_interval as i64;
        let generation = self.generation;
        let mut due: Vec<(u128, [f64; 3])> = Vec::new();
        for (uuid, p) in self.players.iter() {
            if p.dim == OVERWORLD && (p.sync_gen != generation || now.saturating_sub(p.last_sync) >= interval || now < p.last_sync) {
                due.push((*uuid, p.pos));
            }
        }
        for (uuid, pos) in due {
            let tf = now as f64;
            let mut near: Vec<(f64, StormDef)> = self
                .active
                .iter()
                .map(|s| {
                    let c = s.position(tf);
                    ((c[0] - pos[0]).hypot(c[1] - pos[2]) - s.reach(), *s)
                })
                .filter(|(d, _)| *d < self.cfg.sync_radius)
                .collect();
            near.sort_by(|a, b| a.0.total_cmp(&b.0));
            near.truncate(net::MAX_STORMS);
            let snap = Snapshot {
                seed: self.cfg.seed,
                year_days: self.cfg.year_days as f32,
                game_time: now,
                storms: near.into_iter().map(|(_, s)| s).collect(),
            };
            let ok = host.net_send_to_player(uuid, net::CHANNEL, &net::encode(&snap)).is_ok();
            if let Some(p) = self.players.get_mut(&uuid) {
                p.last_sync = now;
                if ok {
                    p.sync_gen = generation;
                }
            }
        }
    }

    /// Wind at a column, optionally without hurricanes.
    fn wind_speed(&self, x: f64, z: f64, env: &Env, with_cyclones: bool) -> f64 {
        let tf = self.now as f64;
        let (w, _) = if with_cyclones {
            field::wind_at(&self.active, &self.tors, env.wind, x, z, tf)
        } else {
            let storms: Vec<StormDef> = self.active.iter().filter(|s| s.kind != StormKind::Cyclone).copied().collect();
            field::wind_at(&storms, &self.tors, env.wind, x, z, tf)
        };
        w[0].hypot(w[1])
    }

    /// Wind damage near players. The path is cut into tiles; each tile gets
    /// the edits of each new damage step once, on every one of its columns
    /// (trees stripped and debarked, ground scoured, buildings torn down),
    /// plus per-column extras (snapped trunks, holes). Pending tiles are
    /// worked off within `damage_budget` columns a second.
    fn damage(&mut self, host: &mut dyn Host) {
        if !self.cfg.damage || self.damage_mode == DamageMode::Off || self.active.is_empty() {
            return;
        }
        if self.tiles.len() > 50_000 {
            self.tiles.clear();
        }
        let players: Vec<[f64; 3]> = self.overworld_players().map(|(_, p)| p.pos).collect();
        let mut areas: Vec<([f64; 2], f64)> = Vec::new();
        for (_, tor) in &self.tors {
            let near = players.iter().any(|p| (p[0] - tor.pos[0]).hypot(p[2] - tor.pos[1]) < 400.0 + tor.radius * 4.0);
            if near {
                areas.push((tor.pos, tor.radius * (tor.vmax / 29.0).powf(1.0 / 0.75).min(3.5)));
            }
        }
        for p in &players {
            let env = self.env_at(p[0], p[2]);
            if self.wind_speed(p[0], p[2], &env, self.cfg.hurricane_damage) >= 29.0 {
                areas.push(([p[0], p[2]], 96.0));
            }
        }
        let env = players.first().map(|p| self.env_at(p[0], p[2])).unwrap_or_default();
        for (c, reach) in areas {
            let (t0, t1) = (damage::tile_of(c[0] - reach, c[1] - reach), damage::tile_of(c[0] + reach, c[1] + reach));
            for tx in t0.0..=t1.0 {
                for tz in t0.1..=t1.1 {
                    let (cx, cz) = damage::tile_center((tx, tz));
                    let (x, z) = (cx as f64 + 0.5, cz as f64 + 0.5);
                    if (x - c[0]).hypot(z - c[1]) > reach + damage::TILE as f64 {
                        continue;
                    }
                    let tier = damage::tier_of(self.wind_speed(x, z, &env, self.cfg.hurricane_damage));
                    let done = self.tiles.get(&(tx, tz)).copied().unwrap_or(0);
                    if tier > done && self.pending.len() < 4000 {
                        self.pending.push_back(((tx, tz), done, tier, tier_speed(tier)));
                        self.tiles.insert((tx, tz), tier);
                    }
                }
            }
        }
        let mut columns_left = (self.cfg.damage_budget / 2).max(1) as i64;
        let mut runs = Vec::new();
        let mut ops = Vec::new();
        let mut extras = Vec::new();
        while columns_left > 0 {
            let Some(((tx, tz), from, to, speed)) = self.pending.pop_front() else { break };
            columns_left -= (damage::TILE * damage::TILE) as i64;
            ops.clear();
            for step in from + 1..=to {
                damage::tile_ops(step, &self.species, &mut ops);
            }
            for dx in 0..damage::TILE {
                for dz in 0..damage::TILE {
                    let (x, z) = (tx * damage::TILE + dx, tz * damage::TILE + dz);
                    for op in &ops {
                        let (lo, hi) = op.band.range();
                        let run = damage::Run { dx: 0, dz: 0, depth: lo, height: (hi - lo + 1) as u32, surface: Surface::World, block: op.block, filter: op.filter };
                        runs.push((x, z, run));
                    }
                    extras.clear();
                    damage::plan_column_extras(speed, &mut self.rng, &mut extras);
                    runs.extend(extras.iter().map(|r| (x, z, *r)));
                }
            }
        }
        if !runs.is_empty() {
            self.apply(host, &runs);
        }
    }

    /// Turns planned runs into one bulk edit.
    fn apply(&mut self, host: &mut dyn Host, runs: &[(i32, i32, damage::Run)]) {
        let safe = self.damage_mode == DamageMode::Safe;
        let filters: Vec<u8> = if safe { SAFE_FILTERS.to_vec() } else { (0..FILTERS.len() as u8).collect() };
        let mut edit = NativeBlockEdit {
            palette: PALETTE.iter().map(|s| s.to_string()).collect(),
            filters: filters.iter().map(|&f| FILTERS[f as usize].to_string()).collect(),
            runs: Vec::with_capacity(runs.len()),
        };
        for (x, z, r) in runs {
            let Some(only) = filters.iter().position(|&f| f == r.filter) else { continue };
            edit.runs.push(NativeBlockRun {
                x: *x,
                z: *z,
                y: r.depth,
                height: r.height,
                surface: Some(match r.surface {
                    Surface::Motion => NativeHeightmap::MotionBlocking,
                    Surface::World => NativeHeightmap::WorldSurface,
                }),
                block: r.block as u32,
                only: Some(only as u32),
            });
        }
        if edit.runs.is_empty() {
            return;
        }
        if let Err(e) = host.edit_blocks(OVERWORLD, &edit) {
            if e.contains("is not the level") || e.contains("client") {
                return; // not our moment; try again later
            }
            self.damage_mode = if safe { DamageMode::Off } else { DamageMode::Safe };
            log(&format!("block edit refused ({e}); damage now {:?}", self.damage_mode));
        }
    }

    /// Lightning near players under strong echoes.
    fn lightning(&mut self, host: &mut dyn Host) {
        if !self.cfg.lightning || self.active.is_empty() {
            return;
        }
        let tf = self.now as f64;
        let players: Vec<[f64; 3]> = self.overworld_players().map(|(_, p)| p.pos).collect();
        for p in players {
            let a = self.rng.f64() * std::f64::consts::TAU;
            let r = 24.0 + 150.0 * self.rng.f64().sqrt();
            let (x, z) = (p[0] + r * a.cos(), p[2] + r * a.sin());
            let Some((i, dbz)) = field::dominant(&self.active, x, z, tf) else { continue };
            if !self.active[i].kind.convective() || dbz < 45.0 {
                continue;
            }
            if !self.rng.chance(0.35 * ((dbz - 45.0) / 25.0).min(1.0)) {
                continue;
            }
            let (xi, zi) = (x.floor() as i32, z.floor() as i32);
            let y = match host.surface_heights(OVERWORLD, &[(xi, zi)], NativeHeightmap::MotionBlocking) {
                Ok(h) => match h.first() {
                    Some(Some(y)) => *y,
                    _ => continue,
                },
                Err(_) => continue,
            };
            host.run_command(&format!("summon minecraft:lightning_bolt {xi} {y} {zi}"));
        }
    }

    /// Tornado cores and big hail hurt players in the open.
    fn hurt_players(&mut self, host: &mut dyn Host) {
        if !self.cfg.player_damage || self.active.is_empty() {
            return;
        }
        let tf = self.now as f64;
        let players: Vec<(String, [f64; 3], i32)> = self
            .overworld_players()
            .map(|(_, p)| (p.name.clone(), p.pos, p.game_mode))
            .collect();
        for (name, pos, mode) in players {
            if mode != 0 && mode != 2 {
                continue;
            }
            let mut amount: f64 = 0.0;
            for (_, tor) in &self.tors {
                let d = (tor.pos[0] - pos[0]).hypot(tor.pos[1] - pos[2]);
                if d < tor.radius * 1.1 && tor.vmax >= 45.0 {
                    amount = amount.max(((tor.vmax - 40.0) / 12.0).clamp(1.0, 8.0));
                }
            }
            if amount == 0.0 {
                let env = self.env_at(pos[0], pos[2]);
                let s = field::sample(&self.active, &self.tors, &env, pos[0], pos[2], tf);
                if s.precip == Precip::Hail && s.dbz >= 62.0 && self.rng.chance(0.4) && self.exposed(host, pos) {
                    amount = 1.0;
                }
            }
            if amount > 0.0 {
                host.run_command(&format!("damage {name} {:.1} minecraft:generic", amount));
            }
        }
    }

    fn exposed(&self, host: &dyn Host, pos: [f64; 3]) -> bool {
        let col = (pos[0].floor() as i32, pos[2].floor() as i32);
        match host.surface_heights(OVERWORLD, &[col], NativeHeightmap::MotionBlocking) {
            Ok(h) => h.first().copied().flatten().is_none_or(|y| y as f64 <= pos[1] + 0.5),
            Err(_) => false,
        }
    }

    /// Snow piles up and freezing rain glazes water under winter storms.
    fn winter(&mut self, host: &mut dyn Host) {
        if !self.cfg.snow || self.active.is_empty() || self.damage_mode == DamageMode::Off {
            return;
        }
        let tf = self.now as f64;
        let players: Vec<[f64; 3]> = self.overworld_players().map(|(_, p)| p.pos).collect();
        let mut runs = Vec::new();
        let mut plan = Vec::new();
        for p in players {
            let env = self.env_at(p[0], p[2]);
            let s = field::sample(&self.active, &self.tors, &env, p[0], p[2], tf);
            let n = match s.precip {
                Precip::Snow => 2 + (s.dbz / 10.0) as usize,
                Precip::FreezingRain | Precip::Sleet => 3,
                _ => 0,
            };
            for _ in 0..n {
                let a = self.rng.f64() * std::f64::consts::TAU;
                let r = 64.0 * self.rng.f64().sqrt();
                let (x, z) = ((p[0] + r * a.cos()).floor() as i32, (p[2] + r * a.sin()).floor() as i32);
                plan.clear();
                if s.precip == Precip::Snow {
                    damage::snow_column(&mut plan);
                } else {
                    damage::freeze_column(&mut plan);
                }
                runs.extend(plan.iter().map(|r| (x, z, *r)));
            }
        }
        if !runs.is_empty() && self.damage_mode == DamageMode::Full {
            self.apply(host, &runs);
        }
    }

    /// Vanilla rain and thunder follow the storms over the players.
    fn vanilla_weather(&mut self, host: &mut dyn Host) {
        if !self.cfg.vanilla_weather {
            return;
        }
        let tf = self.now as f64;
        let mut want = 0u8;
        let players: Vec<[f64; 3]> = self.overworld_players().map(|(_, p)| p.pos).collect();
        for p in players {
            if let Some((i, dbz)) = field::dominant(&self.active, p[0], p[2], tf) {
                let w = if dbz >= 45.0 && self.active[i].kind.convective() { 2 } else if dbz >= 20.0 { 1 } else { 0 };
                want = want.max(w);
            }
        }
        if want != self.vanilla || self.now.saturating_sub(self.vanilla_at) > 9000 || self.now < self.vanilla_at {
            let cmd = ["weather clear 600", "weather rain 600", "weather thunder 600"][want as usize];
            if host.run_command(cmd) {
                self.vanilla = want;
                self.vanilla_at = self.now;
            }
        }
    }

    /// What `entity_tick_post` needs: `None` when nothing pushes entities.
    pub fn vortices_for_entities(&self) -> Option<Vec<Vortex>> {
        (!self.vortices.is_empty()).then(|| self.vortices.clone())
    }

    // ---- commands -----------------------------------------------------------

    pub fn perform_command(&mut self, host: &mut dyn Host, position: [f64; 3], command: &str) {
        let words: Vec<&str> = command.split_whitespace().collect();
        let pos = [position[0], position[2]];
        if let Some(level) = host.level(OVERWORLD) {
            if let Some(t) = host.level_game_time(level) {
                self.now = t;
            }
        }
        let reply = |host: &mut dyn Host, s: String| host.feedback(Text::literal(s));
        match words.get(1).copied() {
            Some("spawn") => {
                let Some(kind) = words.get(2).and_then(|k| StormKind::parse(k)) else {
                    reply(host, "Usage: /tempest spawn <cell|supercell|tornado|squall|hurricane|blizzard> [EF 0-5]".into());
                    return;
                };
                let mut ef = words.get(3).and_then(|e| e.trim_start_matches(['e', 'E', 'f', 'F']).parse::<u8>().ok());
                if words[2] == "tornado" && ef.is_none() {
                    ef = Some(2);
                }
                let lead = match kind {
                    StormKind::Cell => 600,
                    StormKind::Supercell => 1200,
                    StormKind::Squall => 1600,
                    StormKind::Cyclone => 6000,
                    StormKind::Blizzard => 1200,
                };
                let s = self.spawner.manual(kind, pos, self.now, ef, lead);
                self.manual.push(s);
                self.last_refresh = i64::MIN;
                self.save();
                let extra = s.tornado.map(|t| format!(", tornado EF{}", t.ef)).unwrap_or_default();
                reply(host, format!("Tempest: {} spawned{extra}; it reaches you in ~{} s.", kind.name(), lead / 20));
            }
            Some("clear") => {
                self.suppressed.extend(self.active.iter().filter(|s| !s.manual).map(|s| s.id));
                self.manual.clear();
                self.last_refresh = i64::MIN;
                self.save();
                reply(host, "Tempest: storms cleared.".into());
            }
            Some("list") => {
                let tf = self.now as f64;
                let mut rows: Vec<(f64, String)> = self
                    .active
                    .iter()
                    .filter(|s| s.alive(tf))
                    .map(|s| {
                        let c = s.position(tf);
                        let d = (c[0] - pos[0]).hypot(c[1] - pos[1]);
                        let tor = match s.tornado_at(tf) {
                            Some(t) => format!(" TORNADO EF{}", t.ef_now),
                            None => String::new(),
                        };
                        (d, format!("{} {:.1} km {} ({:.0}%){tor}", s.kind.name(), d / 1000.0, field::compass_to(pos, c), 100.0 * s.intensity(tf)))
                    })
                    .collect();
                rows.sort_by(|a, b| a.0.total_cmp(&b.0));
                if rows.is_empty() {
                    reply(host, "Tempest: no storms nearby.".into());
                }
                for (_, r) in rows.into_iter().take(8) {
                    reply(host, r);
                }
            }
            Some("info") => {
                let e = self.env_at(pos[0], pos[1]);
                reply(host, format!(
                    "T {:.0}°C Td {:.0}°C CAPE {:.0} shear {:.0} kt SRH {:.0} STP {:.1} risk {} ({})",
                    e.temp_c, e.dew_c, e.cape, e.shear_kt, e.srh, e.stp, e.risk().label(),
                    crate::sim::climate::season_name(e.season)
                ));
                let tf = self.now as f64;
                for s in self.active.iter().filter(|s| s.kind == StormKind::Cyclone && s.alive(tf)) {
                    let c = s.position(tf);
                    let w = s.wind(c[0] + s.aux as f64, c[1], tf);
                    reply(host, format!("Hurricane cat {} {:.1} km {}", hurricane_category(w[0].hypot(w[1])), (c[0] - pos[0]).hypot(c[1] - pos[1]) / 1000.0, field::compass_to(pos, c)));
                }
            }
            Some("set") if words.len() >= 4 => {
                let value = words[3..].join(" ");
                if self.cfg.set(words[2], &value) {
                    self.spawner = spawner_for(&self.cfg);
                    self.damage_mode = DamageMode::Full;
                    self.last_refresh = i64::MIN;
                    self.save();
                    reply(host, format!("Tempest: {} = {value}", words[2]));
                } else {
                    reply(host, format!("Tempest: unknown setting or value: {} {value}", words[2]));
                }
            }
            Some("reload") => {
                self.load();
                self.damage_mode = DamageMode::Full;
                self.last_refresh = i64::MIN;
                reply(host, "Tempest: settings reloaded.".into());
            }
            _ => reply(host, "Tempest: /tempest spawn <kind> [ef] | clear | list | info | set <key> <value> | reload".into()),
        }
    }
}

/// A wind speed inside damage step `tier` (for the per-column extras).
fn tier_speed(tier: u8) -> f64 {
    match tier {
        0 => 0.0,
        t => crate::sim::storm::EF_MIN[(t as usize - 1).min(5)] + 3.0,
    }
}

pub fn register_commands(commands: &mut Vec<NativeCommand>) {
    for usage in [
        "tempest spawn <kind:word> [ef:integer]",
        "tempest clear",
        "tempest set <key:word> <value:text>",
        "tempest reload",
    ] {
        commands.push(NativeCommand::op(usage, 2));
    }
    for usage in ["tempest list", "tempest info", "tempest"] {
        commands.push(NativeCommand::new(usage));
    }
}

/// Pulls a mob or item toward a tornado and lifts it.
pub fn push_entity(host: &mut dyn Host, vortices: &[Vortex], id: u32, kind: &str) {
    if kind == "minecraft:player" {
        return;
    }
    let Some(entity) = host.entity(id) else { return };
    let Some(pos) = host.entity_position(entity) else { return };
    let mut best: Option<([f64; 2], f64, f64)> = None;
    for v in vortices {
        let d = (pos[0] - v.tor.pos[0]).hypot(pos[2] - v.tor.pos[1]);
        if d < v.tor.radius * 4.0 {
            let (w, up) = v.tor.wind(pos[0], pos[2]);
            let s = w[0].hypot(w[1]);
            if best.is_none_or(|b| s > b.1) {
                best = Some((w, s, up));
            }
        }
    }
    let Some((w, speed, up)) = best else { return };
    if speed < 18.0 {
        return;
    }
    let vel = host.entity_velocity(entity).unwrap_or([0.0; 3]);
    // Drag toward the wind (blocks per tick) and lift in the core.
    let k = 0.06;
    let target = [w[0] / 20.0, w[1] / 20.0];
    let mut nv = [
        vel[0] + (target[0] - vel[0]) * k,
        vel[1] + up / 20.0 * 0.09 + if speed > 50.0 { 0.03 } else { 0.0 },
        vel[2] + (target[1] - vel[2]) * k,
    ];
    let mag = (nv[0] * nv[0] + nv[1] * nv[1] + nv[2] * nv[2]).sqrt();
    if mag > 2.5 {
        for c in &mut nv {
            *c *= 2.5 / mag;
        }
    }
    if !host.entity_set_velocity(entity, nv) {
        host.push_mob(OVERWORLD, id, [nv[0] - vel[0], nv[1] - vel[1], nv[2] - vel[2]]);
    }
}
