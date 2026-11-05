//! Where and when storms are born.
//!
//! The world is cut into cells and time into slots. For each (cell, slot,
//! kind) a hash says whether a storm is born there, with odds set by the
//! environment. Any machine can list the storms near a point at any time
//! without history, which keeps the server and every client in agreement
//! even with no network traffic, and costs a few microseconds.

use super::climate::{Climate, Env};
use super::rng::{hash, unit, Rng};
use super::storm::{smoothstep, StormDef, StormKind, TornadoDef};

/// Number of storm kinds.
pub const KINDS: usize = 5;

/// Length of a spawn slot, ticks (2 minutes).
pub const SLOT: i64 = 2400;

/// How each kind is born.
struct Rule {
    kind: StormKind,
    /// Cell size, blocks.
    cell: f64,
    /// Chance of a birth per cell and slot in a perfect environment.
    base: f64,
    life: (f64, f64),
    speed: (f64, f64),
    size: (f64, f64),
    aux: (f64, f64),
}

const RULES: [Rule; 5] = [
    Rule { kind: StormKind::Cell, cell: 3072.0, base: 0.10, life: (5000.0, 11000.0), speed: (0.12, 0.30), size: (140.0, 280.0), aux: (0.0, 0.0) },
    Rule { kind: StormKind::Supercell, cell: 4096.0, base: 0.035, life: (14000.0, 30000.0), speed: (0.18, 0.40), size: (280.0, 560.0), aux: (0.0, 0.0) },
    Rule { kind: StormKind::Squall, cell: 8192.0, base: 0.02, life: (20000.0, 40000.0), speed: (0.30, 0.55), size: (300.0, 550.0), aux: (1600.0, 4000.0) },
    Rule { kind: StormKind::Cyclone, cell: 16384.0, base: 0.004, life: (60000.0, 110000.0), speed: (0.10, 0.25), size: (2500.0, 5000.0), aux: (160.0, 380.0) },
    Rule { kind: StormKind::Blizzard, cell: 8192.0, base: 0.02, life: (18000.0, 36000.0), speed: (0.25, 0.45), size: (1800.0, 3600.0), aux: (0.0, 0.0) },
];

/// How favourable the environment is for a kind, 0..1.
pub fn favour(kind: StormKind, e: &Env) -> f64 {
    match kind {
        StormKind::Cell => smoothstep(300.0, 1800.0, e.cape) * (1.0 - 0.5 * smoothstep(35.0, 55.0, e.shear_kt)),
        StormKind::Supercell => smoothstep(0.5, 6.0, e.scp),
        StormKind::Squall => smoothstep(800.0, 2500.0, e.cape) * smoothstep(25.0, 50.0, e.shear_kt),
        StormKind::Cyclone => {
            let window = smoothstep(0.18, 0.28, e.season) * (1.0 - smoothstep(0.6, 0.7, e.season));
            smoothstep(23.0, 29.0, e.temp_c) * window
        }
        StormKind::Blizzard => smoothstep(-1.0, -8.0, e.temp_c) * smoothstep(20.0, 45.0, e.shear_kt),
    }
}

/// The storm generator.
#[derive(Clone, Debug)]
pub struct Spawner {
    pub seed: u64,
    pub climate: Climate,
    /// Multiplies every birth chance.
    pub rate: f64,
    /// Multiplies tornado chances.
    pub tornado_rate: f64,
    /// Which kinds are born naturally, by `StormKind as usize`.
    pub enabled: [bool; 5],
}

impl Spawner {
    pub fn new(seed: u64, year_days: f64) -> Self {
        Spawner {
            seed,
            climate: Climate::new(seed, year_days),
            rate: 1.0,
            tornado_rate: 1.0,
            enabled: [true; 5],
        }
    }

    /// Appends to `out` every natural storm alive at tick `t` whose effects can
    /// reach within `radius` blocks of (x, z).
    pub fn storms_near(&self, x: f64, z: f64, t: i64, radius: f64, out: &mut Vec<StormDef>) {
        for k in 0..KINDS {
            self.storms_near_kind(k, x, z, t, radius, out);
        }
    }

    /// [`storms_near`](Self::storms_near) for one kind (`StormKind as usize`),
    /// so the work can be spread over several ticks.
    pub fn storms_near_kind(&self, kind: usize, x: f64, z: f64, t: i64, radius: f64, out: &mut Vec<StormDef>) {
        let tf = t as f64;
        if let Some(rule) = RULES.iter().find(|r| r.kind as usize == kind) {
            if !self.enabled[rule.kind as usize] || self.rate <= 0.0 {
                return;
            }
            let p_max = (rule.base * self.rate).min(1.0);
            let travel = rule.speed.1 * rule.life.1;
            let reach = radius + travel + rule.size.1 * 3.5 + rule.aux.1;
            let n = (reach / rule.cell).ceil() as i64;
            let (cx0, cz0) = ((x / rule.cell).floor() as i64, (z / rule.cell).floor() as i64);
            let s1 = t.div_euclid(SLOT);
            let s0 = (t - rule.life.1 as i64).div_euclid(SLOT);
            for cx in cx0 - n..=cx0 + n {
                for cz in cz0 - n..=cz0 + n {
                    for slot in s0..=s1 {
                        let h = hash(&[self.seed, rule.kind as u64, cx as u64, cz as u64, slot as u64]);
                        if unit(h) >= p_max {
                            continue; // cheap rejection before sampling the climate
                        }
                        if let Some(s) = self.candidate(rule, cx, cz, slot, h) {
                            if !s.alive(tf) {
                                continue;
                            }
                            let p = s.position(tf);
                            let d = (p[0] - x).hypot(p[1] - z);
                            if d <= radius + s.reach() {
                                out.push(s);
                            }
                        }
                    }
                }
            }
        }
    }

    fn candidate(&self, rule: &Rule, cx: i64, cz: i64, slot: i64, h: u64) -> Option<StormDef> {
        let center = [(cx as f64 + 0.5) * rule.cell, (cz as f64 + 0.5) * rule.cell];
        let ts = (slot * SLOT) as f64;
        let env = self.climate.sample(center[0], center[1], ts, ts);
        let f = favour(rule.kind, &env);
        if unit(h) >= (rule.base * self.rate * f).min(1.0) {
            return None;
        }
        let mut r = Rng::new(h ^ 0x5EED_0F57);
        let t0 = slot * SLOT + (r.f64() * SLOT as f64) as i64;
        let origin = [
            (cx as f64 + r.f64()) * rule.cell,
            (cz as f64 + r.f64()) * rule.cell,
        ];
        Some(self.build(rule, &mut r, h, t0, origin, &env, f, false))
    }

    #[allow(clippy::too_many_arguments)]
    fn build(&self, rule: &Rule, r: &mut Rng, id: u64, t0: i64, origin: [f64; 2], env: &Env, f: f64, manual: bool) -> StormDef {
        let kind = rule.kind;
        let life = r.range(rule.life.0, rule.life.1);
        let mut heading = env.steering[1].atan2(env.steering[0]);
        if kind == StormKind::Supercell {
            heading += 0.45; // right-movers
        }
        heading += r.range(-0.25, 0.25);
        let steer = env.steering[0].hypot(env.steering[1]) / 20.0;
        let speed = steer.clamp(rule.speed.0, rule.speed.1) * r.range(0.85, 1.15);
        let curve = match kind {
            StormKind::Supercell => r.range(0.0, 3e-6),
            StormKind::Cyclone => r.range(-4e-6, 1e-6),
            _ => r.range(-1.5e-6, 1.5e-6),
        };
        let strength = ((0.35 + 0.65 * f) * r.range(0.6, 1.0)).clamp(0.1, 1.0);
        let size = r.range(rule.size.0, rule.size.1);
        let aux = r.range(rule.aux.0, rule.aux.1);
        let tornado = self.tornado(kind, r, env, life);
        StormDef {
            id,
            kind,
            t0,
            life: life as i32,
            origin,
            vel: [(speed * heading.cos()) as f32, (speed * heading.sin()) as f32],
            curve: curve as f32,
            strength: strength as f32,
            size: size as f32,
            aux: aux as f32,
            seed: r.u32(),
            tornado,
            manual,
        }
    }

    fn tornado(&self, kind: StormKind, r: &mut Rng, env: &Env, life: f64) -> Option<TornadoDef> {
        let stp = env.stp.max(0.0);
        match kind {
            StormKind::Supercell => {
                let p = ((0.06 + 0.11 * stp).min(0.8) * self.tornado_rate).min(1.0);
                if !r.chance(p) {
                    return None;
                }
                let mut ef = r.weighted(&[0.45, 0.30, 0.14, 0.07, 0.03, 0.01]) as u8;
                for _ in 0..3 {
                    if r.chance((stp / 10.0).min(0.5)) {
                        ef += 1;
                    }
                }
                Some(tornado_def(r, ef.min(5), life, 0.0))
            }
            StormKind::Squall => {
                let p = ((0.04 + 0.06 * stp).min(0.4) * self.tornado_rate).min(1.0);
                if !r.chance(p) {
                    return None;
                }
                let ef = r.weighted(&[0.6, 0.3, 0.1]) as u8;
                let along = r.range(-0.5, 0.5) as f32;
                let mut t = tornado_def(r, ef, life, 0.0);
                t.dur = (t.dur as f64 * 0.5) as i32;
                t.width *= 0.6;
                t.along = along;
                Some(t)
            }
            _ => None,
        }
    }

    /// A storm made on demand (a command), placed so that it reaches `at`
    /// about `lead` ticks after `t`, already mature. `ef` forces a tornado.
    pub fn manual(&self, kind: StormKind, at: [f64; 2], t: i64, ef: Option<u8>, lead: i64) -> StormDef {
        let rule = RULES.iter().find(|r| r.kind == kind).expect("every kind has a rule");
        let tf = t as f64;
        let mut env = self.climate.sample(at[0], at[1], tf, tf);
        // Commands get a good environment for their kind.
        env.cape = env.cape.max(3000.0);
        env.shear_kt = env.shear_kt.max(45.0);
        env.srh = env.srh.max(250.0);
        env.stp = env.stp.max(4.0);
        let id = hash(&[self.seed, 0x4D41_4E55, t as u64, at[0] as i64 as u64, at[1] as i64 as u64, kind as u64]);
        let mut r = Rng::new(id);
        let mut s = self.build(rule, &mut r, id, 0, [0.0, 0.0], &env, 1.0, true);
        s.curve = 0.0;
        s.strength = s.strength.max(0.8);
        let mature = (0.25 * s.life as f64) as i64;
        s.t0 = t - mature;
        if let Some(ef) = ef {
            if matches!(kind, StormKind::Supercell | StormKind::Squall) {
                let mut td = tornado_def(&mut r, ef.min(5), s.life as f64, 0.0);
                td.start = (mature + lead - td.dur as i64 / 3).max(mature + 100) as i32;
                td.dur = td.dur.min(s.life - td.start - 200).max(400);
                s.tornado = Some(td);
            }
        } else if let Some(td) = s.tornado.as_mut() {
            // Natural tornado: let it touch down soon after the storm arrives.
            td.start = (mature + lead / 2) as i32;
            td.dur = td.dur.min(s.life - td.start - 200).max(400);
        }
        // Place the origin so that the point of interest crosses `at` at t + lead.
        let probe = StormDef { origin: [0.0, 0.0], ..s };
        let target_t = (t + lead) as f64;
        let rel = match kind {
            StormKind::Supercell => probe.meso_pos(target_t).unwrap_or(probe.position(target_t)),
            StormKind::Squall => {
                let fr = probe.frame(target_t);
                let along = s.tornado.map_or(0.0, |td| td.along as f64) * s.aux as f64;
                fr.to_world(probe.squall_edge(along, target_t), along)
            }
            _ => probe.position(target_t),
        };
        s.origin = [at[0] - rel[0], at[1] - rel[1]];
        s
    }
}

fn tornado_def(r: &mut Rng, ef: u8, life: f64, along: f64) -> TornadoDef {
    // Radius of maximum wind, blocks: Minecraft-sized, narrow enough to see.
    let widths = [(4.0, 9.0), (6.0, 13.0), (8.0, 18.0), (11.0, 25.0), (14.0, 34.0), (18.0, 45.0)];
    let w = widths[ef as usize];
    let start = r.range(0.25, 0.55) * life;
    let dur = ((900.0 + 800.0 * ef as f64) * r.range(0.7, 1.4)).min(life * 0.92 - start).max(300.0);
    TornadoDef {
        start: start as i32,
        dur: dur as i32,
        ef,
        width: r.range(w.0, w.1) as f32,
        seed: r.u32(),
        along: along as f32,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_answer_everywhere() {
        let s = Spawner::new(1234, 48.0);
        let mut a = Vec::new();
        let mut b = Vec::new();
        s.storms_near(1000.0, -2000.0, 500_000, 6000.0, &mut a);
        s.storms_near(1000.0, -2000.0, 500_000, 6000.0, &mut b);
        assert_eq!(a, b);
    }

    #[test]
    fn storms_happen_over_time() {
        let mut s = Spawner::new(99, 48.0);
        s.rate = 1.0;
        let mut kinds = [0usize; 5];
        let mut tornadic = 0;
        let mut out = Vec::new();
        for day in 0..96 {
            out.clear();
            let t = day * 24_000 + 8000;
            s.storms_near(0.0, 0.0, t, 8000.0, &mut out);
            for st in &out {
                kinds[st.kind as usize] += 1;
                tornadic += st.tornado.is_some() as usize;
                assert!(st.alive(t as f64));
            }
        }
        assert!(kinds[0] > 0, "thunderstorms: {kinds:?}");
        assert!(kinds[1] > 0, "supercells: {kinds:?}");
        assert!(tornadic > 0, "some tornadoes");
    }

    #[test]
    fn rate_zero_means_calm() {
        let mut s = Spawner::new(5, 48.0);
        s.rate = 0.0;
        let mut out = Vec::new();
        s.storms_near(0.0, 0.0, 1_000_000, 10_000.0, &mut out);
        assert!(out.is_empty());
    }

    #[test]
    fn manual_tornado_hits_target() {
        let s = Spawner::new(7, 48.0);
        let at = [500.0, 800.0];
        let t = 100_000;
        let st = s.manual(StormKind::Supercell, at, t, Some(4), 1200);
        assert!(st.alive(t as f64));
        let td = st.tornado.unwrap();
        assert_eq!(td.ef, 4);
        let tor = st.tornado_at((t + 1200) as f64).expect("tornado on the ground on arrival");
        let d = (tor.pos[0] - at[0]).hypot(tor.pos[1] - at[1]);
        assert!(d < 120.0, "passes near the player: {d}");
    }

    #[test]
    fn manual_hurricane_arrives() {
        let s = Spawner::new(7, 48.0);
        let at = [0.0, 0.0];
        let st = s.manual(StormKind::Cyclone, at, 1000, None, 6000);
        let p = st.position(7000.0);
        assert!(p[0].hypot(p[1]) < 1.0);
    }
}
