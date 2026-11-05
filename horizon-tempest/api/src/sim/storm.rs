//! Storms as closed-form functions of time.
//!
//! A storm is a small set of numbers (`StormDef`) chosen when it is born. Its
//! position, intensity, radar echo, winds and tornado at any moment are pure
//! functions of the game time, so the game never has to step a simulation:
//! it only evaluates the storms near the player, when it needs them.

use super::noise::fbm3;
use std::f64::consts::{PI, TAU};

/// The kinds of storm.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum StormKind {
    /// An ordinary (pulse) thunderstorm.
    Cell = 0,
    /// A rotating thunderstorm that can produce large hail and tornadoes.
    Supercell = 1,
    /// A squall line / QLCS, with damaging straight-line winds.
    Squall = 2,
    /// A tropical cyclone (hurricane).
    Cyclone = 3,
    /// A winter storm with snow and strong wind.
    Blizzard = 4,
}

impl StormKind {
    pub const ALL: [StormKind; 5] = [
        StormKind::Cell,
        StormKind::Supercell,
        StormKind::Squall,
        StormKind::Cyclone,
        StormKind::Blizzard,
    ];
    pub fn from_u8(v: u8) -> Option<Self> {
        Self::ALL.get(v as usize).copied()
    }
    pub fn name(self) -> &'static str {
        match self {
            StormKind::Cell => "thunderstorm",
            StormKind::Supercell => "supercell",
            StormKind::Squall => "squall line",
            StormKind::Cyclone => "hurricane",
            StormKind::Blizzard => "blizzard",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "cell" | "thunderstorm" | "storm" => StormKind::Cell,
            "supercell" | "tornado" => StormKind::Supercell,
            "squall" | "squall_line" | "qlcs" | "derecho" => StormKind::Squall,
            "hurricane" | "cyclone" | "typhoon" => StormKind::Cyclone,
            "blizzard" | "snowstorm" | "winter" => StormKind::Blizzard,
            _ => return None,
        })
    }
    /// Whether the storm is convective (lightning, hail).
    pub fn convective(self) -> bool {
        !matches!(self, StormKind::Blizzard)
    }
}

/// A tornado a storm will produce.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TornadoDef {
    /// Ticks after the storm's birth when it touches down.
    pub start: i32,
    /// How long it stays on the ground, ticks.
    pub dur: i32,
    /// Peak rating, 0 to 5 (Enhanced Fujita).
    pub ef: u8,
    /// Peak core radius in blocks.
    pub width: f32,
    pub seed: u32,
    /// Squall lines: where along the line it forms, -1 to 1.
    pub along: f32,
}

/// Everything about a storm, fixed at birth.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StormDef {
    pub id: u64,
    pub kind: StormKind,
    /// Game tick of birth.
    pub t0: i64,
    /// Lifetime in ticks.
    pub life: i32,
    /// Position (x, z) at birth.
    pub origin: [f64; 2],
    /// Motion in blocks per tick (x, z).
    pub vel: [f32; 2],
    /// Turning rate of the motion, radians per tick (positive turns right).
    pub curve: f32,
    /// 0..1, how strong the storm gets.
    pub strength: f32,
    /// Main radius in blocks (squall lines: depth of the line).
    pub size: f32,
    /// Squall lines: half length. Cyclones: radius of maximum wind.
    pub aux: f32,
    pub seed: u32,
    pub tornado: Option<TornadoDef>,
    /// Spawned by a command rather than by the climate.
    pub manual: bool,
}

/// A tornado at one moment.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TornadoState {
    pub pos: [f64; 2],
    /// Core radius (radius of maximum wind), blocks.
    pub radius: f64,
    /// Peak tangential wind, m/s.
    pub vmax: f64,
    /// Storm motion, m/s.
    pub trans: [f64; 2],
    /// 0..1 through its life.
    pub life_frac: f64,
    /// Rating of the current peak wind.
    pub ef_now: u8,
    /// Rating it will reach.
    pub ef_peak: u8,
    pub seed: u32,
}

/// EF scale lower bounds, m/s (3-second gusts).
pub const EF_MIN: [f64; 6] = [29.0, 38.0, 50.0, 61.0, 74.0, 89.0];
/// Typical peak wind of each rating, m/s.
const EF_MID: [f64; 6] = [34.0, 44.0, 55.0, 67.0, 81.0, 98.0];

/// The EF rating of a wind speed, or `None` below EF0.
pub fn ef_of(speed: f64) -> Option<u8> {
    EF_MIN.iter().rposition(|&m| speed >= m).map(|i| i as u8)
}

/// Saffir-Simpson category of a sustained wind (m/s), 0 = tropical storm.
pub fn hurricane_category(speed: f64) -> u8 {
    match speed {
        s if s >= 70.0 => 5,
        s if s >= 58.0 => 4,
        s if s >= 50.0 => 3,
        s if s >= 43.0 => 2,
        s if s >= 33.0 => 1,
        _ => 0,
    }
}

#[inline]
pub fn smoothstep(e0: f64, e1: f64, x: f64) -> f64 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[inline]
fn gauss(dx: f64, dz: f64, sx: f64, sz: f64) -> f64 {
    (-(dx / sx).powi(2) - (dz / sz).powi(2)).exp()
}

/// The storm's local frame at one moment.
#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub center: [f64; 2],
    /// Unit vector of motion (x, z).
    pub fwd: [f64; 2],
    /// Unit vector to the left of the motion, seen from above.
    pub left: [f64; 2],
}

impl Frame {
    #[inline]
    pub fn to_local(&self, x: f64, z: f64) -> (f64, f64) {
        let dx = x - self.center[0];
        let dz = z - self.center[1];
        (
            dx * self.fwd[0] + dz * self.fwd[1],
            dx * self.left[0] + dz * self.left[1],
        )
    }
    #[inline]
    pub fn to_world(&self, u: f64, v: f64) -> [f64; 2] {
        [
            self.center[0] + u * self.fwd[0] + v * self.left[0],
            self.center[1] + u * self.fwd[1] + v * self.left[1],
        ]
    }
    /// A local vector (u, v) as a world vector.
    #[inline]
    pub fn vec_to_world(&self, u: f64, v: f64) -> [f64; 2] {
        [
            u * self.fwd[0] + v * self.left[0],
            u * self.fwd[1] + v * self.left[1],
        ]
    }
}

impl StormDef {
    #[inline]
    pub fn age(&self, t: f64) -> f64 {
        t - self.t0 as f64
    }
    #[inline]
    pub fn alive(&self, t: f64) -> bool {
        let a = self.age(t);
        a >= 0.0 && a <= self.life as f64
    }
    /// 0..1 through its life.
    #[inline]
    pub fn life_frac(&self, t: f64) -> f64 {
        (self.age(t) / self.life.max(1) as f64).clamp(0.0, 1.0)
    }
    /// 0..1: grows, matures, decays.
    pub fn intensity(&self, t: f64) -> f64 {
        if !self.alive(t) {
            return 0.0;
        }
        let f = self.life_frac(t);
        smoothstep(0.0, 0.2, f) * (1.0 - smoothstep(0.7, 1.0, f))
    }
    #[inline]
    fn speed(&self) -> f64 {
        (self.vel[0] as f64).hypot(self.vel[1] as f64)
    }
    #[inline]
    fn heading0(&self) -> f64 {
        (self.vel[1] as f64).atan2(self.vel[0] as f64)
    }
    pub fn heading(&self, t: f64) -> f64 {
        self.heading0() + self.curve as f64 * self.age(t)
    }
    /// Centre (x, z) at time `t`.
    pub fn position(&self, t: f64) -> [f64; 2] {
        let a = self.age(t);
        let c = self.curve as f64;
        if c.abs() < 1e-9 {
            return [
                self.origin[0] + self.vel[0] as f64 * a,
                self.origin[1] + self.vel[1] as f64 * a,
            ];
        }
        let s = self.speed();
        let h0 = self.heading0();
        let h = h0 + c * a;
        [
            self.origin[0] + s / c * (h.sin() - h0.sin()),
            self.origin[1] - s / c * (h.cos() - h0.cos()),
        ]
    }
    /// Motion in m/s (1 block = 1 m, 20 ticks a second).
    pub fn motion_ms(&self, t: f64) -> [f64; 2] {
        let h = self.heading(t);
        let s = self.speed() * 20.0;
        [s * h.cos(), s * h.sin()]
    }
    pub fn frame(&self, t: f64) -> Frame {
        let h = self.heading(t);
        let fwd = [h.cos(), h.sin()];
        Frame {
            center: self.position(t),
            fwd,
            left: [fwd[1], -fwd[0]],
        }
    }
    /// Radius that grows and shrinks with intensity.
    pub fn radius_now(&self, t: f64) -> f64 {
        self.size as f64 * (0.55 + 0.45 * self.intensity(t))
    }
    /// How far from the centre the storm has any effect: for culling.
    pub fn reach(&self) -> f64 {
        let s = self.size as f64;
        match self.kind {
            StormKind::Cell => s * 2.0,
            StormKind::Supercell => s * 3.5,
            StormKind::Squall => self.aux as f64 * 1.1 + s * 3.0,
            StormKind::Cyclone => s * 1.3,
            StormKind::Blizzard => s * 1.6,
        }
    }
    /// Peak reflectivity, dBZ.
    pub fn peak_dbz(&self) -> f64 {
        let s = self.strength as f64;
        match self.kind {
            StormKind::Cell => 44.0 + 13.0 * s,
            StormKind::Supercell => 58.0 + 14.0 * s,
            StormKind::Squall => 50.0 + 12.0 * s,
            StormKind::Cyclone => 42.0 + 12.0 * s,
            StormKind::Blizzard => 26.0 + 12.0 * s,
        }
    }
    /// Squall lines: how far the leading edge bows out at `v`.
    pub fn squall_edge(&self, v: f64, t: f64) -> f64 {
        let l = self.aux as f64;
        let bow = 0.2 + 1.0 * self.life_frac(t);
        self.size as f64 * bow * (1.0 - (v / l).powi(2)).max(0.0)
    }
    /// Cheap rejection before the detailed evaluations.
    #[inline]
    fn near(&self, x: f64, z: f64, t: f64) -> bool {
        let c = self.position(t);
        let r = self.reach();
        (x - c[0]).abs() < r && (z - c[1]).abs() < r
    }

    /// Radar reflectivity at ground level, dBZ (0 when nothing).
    pub fn dbz(&self, x: f64, z: f64, t: f64) -> f64 {
        let i = self.intensity(t);
        if i <= 0.01 || !self.near(x, z, t) {
            return 0.0;
        }
        let fr = self.frame(t);
        let (u, v) = fr.to_local(x, z);
        let r_now = self.radius_now(t);
        let age_n = self.age(t) / 1500.0;
        let seed = self.seed as u64;
        let shape = match self.kind {
            StormKind::Cell => {
                let (a, b) = (u / r_now, v / r_now);
                let n = fbm3(seed, a * 1.6, b * 1.6, age_n, 2);
                gauss(a, b, 0.65, 0.65) * (0.75 + 0.5 * n)
            }
            StormKind::Supercell => {
                let (a, b) = (u / r_now, v / r_now);
                let n = fbm3(seed, a * 1.8, b * 1.8, age_n, 2);
                let core = gauss(a - 0.15, b - 0.10, 0.45, 0.38);
                let ff = gauss(a - 1.0, b - 0.35, 1.05, 0.5) * 0.70;
                let hook = hook_echo(a, b) * 0.82;
                core.max(ff).max(hook) * (0.8 + 0.4 * n)
            }
            StormKind::Squall => {
                let l = self.aux as f64;
                let w = self.size as f64;
                let du = u - self.squall_edge(v, t);
                let along = 1.0 - smoothstep(0.75 * l, 1.05 * l, v.abs());
                let n = fbm3(seed, u / 260.0, v / 260.0, age_n, 2);
                let conv = (-(du / (0.17 * w)).powi(2)).exp() * (0.55 + 0.75 * n);
                let strat = smoothstep(-2.8 * w, -2.2 * w, du)
                    * (1.0 - smoothstep(-0.45 * w, -0.25 * w, du))
                    * (0.55 + 0.2 * n);
                conv.max(strat).min(1.15) * along
            }
            StormKind::Cyclone => {
                let rm = self.aux as f64;
                let big = self.size as f64;
                let r = u.hypot(v);
                if r < 0.5 * rm {
                    0.0
                } else {
                    let theta = v.atan2(u);
                    let wall = (-((r - rm) / (0.35 * rm)).powi(2)).exp();
                    let spiral = (r / rm).ln() / 0.3f64.tan();
                    let rot = self.age(t) * 0.0004;
                    let band = (0.5 + 0.5 * (2.0 * (theta + spiral) - rot).cos()).powi(3);
                    let env = (1.0 - smoothstep(0.4 * big, big, r)) * smoothstep(0.5 * rm, 1.3 * rm, r);
                    let n = fbm3(seed, u / 700.0, v / 700.0, age_n, 2);
                    wall.max(band * 0.75 * env) * (0.8 + 0.4 * n)
                }
            }
            StormKind::Blizzard => {
                let big = self.size as f64;
                let r = (u / 1.5).hypot(v);
                let n = fbm3(seed, u / 900.0, v / 500.0, age_n, 3);
                (1.0 - smoothstep(0.3 * big, big, r)) * (0.45 + 0.6 * n)
            }
        };
        let dbz = self.peak_dbz() * (0.5 + 0.5 * i) * shape;
        if dbz < 5.0 { 0.0 } else { dbz }
    }

    /// How much sky the storm covers above (x, z): 0..1, for darkness and fog.
    pub fn cover(&self, x: f64, z: f64, t: f64) -> f64 {
        let i = self.intensity(t);
        if i <= 0.01 || !self.near(x, z, t) {
            return 0.0;
        }
        let fr = self.frame(t);
        let (u, v) = fr.to_local(x, z);
        let r = self.radius_now(t);
        let c = match self.kind {
            StormKind::Cell => gauss(u / r, v / r, 1.1, 1.1),
            StormKind::Supercell => {
                let (a, b) = (u / r, v / r);
                gauss(a, b, 1.2, 1.0).max(gauss(a - 1.9, b - 0.2, 2.0, 1.1) * 0.85)
            }
            StormKind::Squall => {
                let w = self.size as f64;
                let du = u - self.squall_edge(v, t);
                let along = 1.0 - smoothstep(0.8 * self.aux as f64, 1.1 * self.aux as f64, v.abs());
                smoothstep(-3.4 * w, -2.8 * w, du) * (1.0 - smoothstep(0.3 * w, 0.8 * w, du)) * along
            }
            StormKind::Cyclone => 1.0 - smoothstep(0.6 * self.size as f64, self.size as f64, u.hypot(v)),
            StormKind::Blizzard => {
                1.0 - smoothstep(0.5 * self.size as f64, 1.2 * self.size as f64, (u / 1.5).hypot(v))
            }
        };
        (c * (0.4 + 0.6 * i)).clamp(0.0, 1.0)
    }

    /// Where the rotating updraft (mesocyclone) is: supercells only.
    pub fn meso_pos(&self, t: f64) -> Option<[f64; 2]> {
        if self.kind != StormKind::Supercell {
            return None;
        }
        let r = self.radius_now(t);
        Some(self.frame(t).to_world(-0.45 * r, -0.45 * r))
    }

    /// The storm's own winds at (x, z), m/s, without any tornado.
    pub fn wind(&self, x: f64, z: f64, t: f64) -> [f64; 2] {
        let i = self.intensity(t);
        if i <= 0.01 || !self.near(x, z, t) {
            return [0.0, 0.0];
        }
        let s = self.strength as f64;
        let fr = self.frame(t);
        let (u, v) = fr.to_local(x, z);
        let r_now = self.radius_now(t);
        let (lu, lv) = match self.kind {
            StormKind::Cell => {
                // Downdraft outflow spreading from the core.
                let r = u.hypot(v).max(1.0);
                let mag = (8.0 + 10.0 * s) * i * (r / (0.8 * r_now)) * (-(r / r_now).powi(2)).exp() * 1.65;
                (u / r * mag, v / r * mag)
            }
            StormKind::Supercell => {
                // Broad mesocyclone circulation and rear-flank outflow.
                let (mu, mv) = (u + 0.45 * r_now, v + 0.45 * r_now);
                let rm = mu.hypot(mv).max(1.0);
                let rc = 0.25 * r_now;
                let vmax = (10.0 + 14.0 * s) * i;
                let vt = if rm < rc { vmax * rm / rc } else { vmax * (rc / rm).powf(0.7) };
                let fade = (-(rm / (1.6 * r_now)).powi(2)).exp();
                let (tu, tv) = (-mv / rm * vt * fade, mu / rm * vt * fade);
                let rfd = gauss(u / r_now + 0.6, v / r_now + 0.8, 0.55, 0.45) * (12.0 + 16.0 * s) * i;
                let ffd = gauss(u / r_now - 0.6, v / r_now - 0.3, 0.8, 0.5) * 7.0 * i;
                (tu + rfd * 0.6 + ffd, tv - rfd * 0.8 + ffd * 0.2)
            }
            StormKind::Squall => {
                let l = self.aux as f64;
                let w = self.size as f64;
                let du = u - self.squall_edge(v, t);
                let along = 1.0 - smoothstep(0.75 * l, 1.05 * l, v.abs());
                let apex = 0.6 + 0.4 * (1.0 - (v / l).powi(2)).max(0.0);
                let mag = (18.0 + 24.0 * s) * i * along * apex * (-((du + 0.25 * w) / (0.5 * w)).powi(2)).exp();
                (mag, 0.0)
            }
            StormKind::Cyclone => {
                let rm = self.aux as f64;
                let big = self.size as f64;
                let r = u.hypot(v).max(1.0);
                let vmax = (33.0 + 47.0 * s) * (0.35 + 0.65 * i);
                let vt = if r < rm { vmax * r / rm } else { vmax * (rm / r).powf(0.55) }
                    * (1.0 - smoothstep(0.7 * big, 1.3 * big, r));
                let inflow = 0.15 * vt;
                (-v / r * vt - u / r * inflow, u / r * vt - v / r * inflow)
            }
            StormKind::Blizzard => {
                let big = self.size as f64;
                let env = 1.0 - smoothstep(0.4 * big, 1.2 * big, (u / 1.5).hypot(v));
                let mag = (14.0 + 16.0 * s) * i * env;
                (mag * 0.6, -mag * 0.8)
            }
        };
        fr.vec_to_world(lu, lv)
    }

    /// The tornado on the ground at time `t`, if any.
    pub fn tornado_at(&self, t: f64) -> Option<TornadoState> {
        let td = self.tornado?;
        let ta = self.age(t) - td.start as f64;
        if ta < 0.0 || ta > td.dur as f64 || !self.alive(t) {
            return None;
        }
        let lf = ta / td.dur.max(1) as f64;
        let env = smoothstep(0.0, 0.12, lf) * (1.0 - smoothstep(0.75, 1.0, lf));
        let ef = td.ef.min(5) as usize;
        let vmax = EF_MID[ef] * (0.5 + 0.5 * env);
        let rope = 1.0 - 0.6 * smoothstep(0.7, 1.0, lf);
        let radius = (td.width as f64 * (0.35 + 0.65 * env) * rope).max(3.0);
        let fr = self.frame(t);
        let r_now = self.radius_now(t);
        let base = match self.kind {
            StormKind::Squall => {
                let v = td.along as f64 * self.aux as f64;
                fr.to_world(self.squall_edge(v, t) - 0.1 * self.size as f64, v)
            }
            _ => {
                // Occlusion: late in life the tornado drifts left of the motion.
                let occl = 0.3 * r_now * smoothstep(0.55, 1.0, lf);
                fr.to_world(-0.45 * r_now, -0.45 * r_now + occl)
            }
        };
        let ph = td.seed as f64 * 1e-3;
        let a1 = 6.0 + 0.15 * td.width as f64;
        let wu = a1 * (ta * 0.011 + ph).sin() + 0.5 * a1 * (ta * 0.029 + 2.0 * ph).sin();
        let wv = a1 * (ta * 0.013 + 3.0 * ph).cos() + 0.5 * a1 * (ta * 0.023 + ph).sin();
        let off = fr.vec_to_world(wu, wv);
        Some(TornadoState {
            pos: [base[0] + off[0], base[1] + off[1]],
            radius,
            vmax,
            trans: self.motion_ms(t),
            life_frac: lf,
            ef_now: ef_of(vmax).unwrap_or(0),
            ef_peak: td.ef,
            seed: td.seed,
        })
    }
}

/// A band of precipitation that wraps cyclonically around the mesocyclone:
/// the hook echo. Coordinates in storm radii, in the storm's frame.
fn hook_echo(a: f64, b: f64) -> f64 {
    let (dx, db) = (a + 0.45, b + 0.45);
    let rho = dx.hypot(db);
    if rho > 0.7 {
        return 0.0;
    }
    let toward_core = (0.55f64).atan2(0.60);
    let phi = (db.atan2(dx) - toward_core).rem_euclid(TAU);
    let span = 1.5 * PI;
    if phi > span {
        return 0.0;
    }
    let k = phi / span;
    let target = 0.33 * (1.0 - 0.4 * k);
    (-((rho - target) / 0.075).powi(2)).exp() * (1.0 - 0.55 * k)
}

impl TornadoState {
    /// Wind of the vortex at (x, z): horizontal (x, z) and updraft, m/s.
    pub fn wind(&self, x: f64, z: f64) -> ([f64; 2], f64) {
        let dx = x - self.pos[0];
        let dz = z - self.pos[1];
        let r = dx.hypot(dz);
        let rc = self.radius;
        if r > rc * 10.0 {
            return ([0.0, 0.0], 0.0);
        }
        let r1 = r.max(0.5);
        let vt = if r < rc { self.vmax * r / rc } else { self.vmax * (rc / r).powf(0.75) }
            * (1.0 - smoothstep(6.0 * rc, 10.0 * rc, r));
        let inflow = if r > rc { 0.3 * vt } else { 0.1 * vt };
        // Counter-clockwise seen from above (x east, z south).
        let tx = dz / r1;
        let tz = -dx / r1;
        let carry = (vt / (0.5 * self.vmax)).min(1.0);
        let wx = vt * tx - inflow * dx / r1 + self.trans[0] * carry;
        let wz = vt * tz - inflow * dz / r1 + self.trans[1] * carry;
        let up = if r < 1.2 * rc { 0.6 * self.vmax * (1.0 - (r / (1.2 * rc)).powi(2)) } else { 0.0 };
        ([wx, wz], up)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn supercell() -> StormDef {
        StormDef {
            id: 1,
            kind: StormKind::Supercell,
            t0: 0,
            life: 20_000,
            origin: [0.0, 0.0],
            vel: [0.3, 0.0],
            curve: 0.0,
            strength: 0.8,
            size: 400.0,
            aux: 0.0,
            seed: 9,
            tornado: Some(TornadoDef { start: 6000, dur: 3000, ef: 3, width: 50.0, seed: 4, along: 0.0 }),
            manual: false,
        }
    }

    #[test]
    fn motion_is_continuous_and_curves() {
        let mut s = supercell();
        s.curve = 1e-5;
        let mut prev = s.position(0.0);
        for k in 1..200 {
            let p = s.position(k as f64 * 100.0);
            let step = (p[0] - prev[0]).hypot(p[1] - prev[1]);
            assert!((step - 30.0).abs() < 0.1, "constant speed: {step}");
            prev = p;
        }
        assert!(s.position(20_000.0)[1] > 0.0, "positive curve turns right (south when heading east)");
    }

    #[test]
    fn intensity_lifecycle() {
        let s = supercell();
        assert_eq!(s.intensity(-1.0), 0.0);
        assert!(s.intensity(1000.0) < s.intensity(8000.0));
        assert!((s.intensity(10_000.0) - 1.0).abs() < 1e-9);
        assert_eq!(s.intensity(20_001.0), 0.0);
    }

    #[test]
    fn supercell_has_core_and_hook() {
        let s = supercell();
        let t = 10_000.0;
        let fr = s.frame(t);
        let r = s.radius_now(t);
        let core = fr.to_world(0.15 * r, 0.1 * r);
        assert!(s.dbz(core[0], core[1], t) > 50.0);
        let far = fr.to_world(-4.0 * r, 0.0);
        assert_eq!(s.dbz(far[0], far[1], t), 0.0);
        // The meso itself sits in the weak echo notch of the hook.
        let m = s.meso_pos(t).unwrap();
        assert!(s.dbz(m[0], m[1], t) < s.dbz(core[0], core[1], t));
    }

    #[test]
    fn tornado_lifecycle_and_wind() {
        let s = supercell();
        assert!(s.tornado_at(5999.0).is_none());
        assert!(s.tornado_at(9001.0).is_none());
        let tor = s.tornado_at(7500.0).expect("on the ground");
        assert_eq!(tor.ef_now, 3);
        let (w, up) = tor.wind(tor.pos[0] + tor.radius, tor.pos[1]);
        let speed = w[0].hypot(w[1]);
        assert!(speed > EF_MIN[3], "core wind {speed}");
        assert!(up >= 0.0);
        // East of the vortex the rotation blows north (-z).
        let (w_east, _) = (TornadoState { trans: [0.0, 0.0], ..tor }).wind(tor.pos[0] + tor.radius, tor.pos[1]);
        assert!(w_east[1] < 0.0);
        let (far, _) = tor.wind(tor.pos[0] + 2000.0, tor.pos[1]);
        assert_eq!(far, [0.0, 0.0]);
    }

    #[test]
    fn ratings() {
        assert_eq!(ef_of(20.0), None);
        assert_eq!(ef_of(30.0), Some(0));
        assert_eq!(ef_of(100.0), Some(5));
        assert_eq!(hurricane_category(60.0), 4);
    }

    #[test]
    fn cyclone_has_eye() {
        let c = StormDef {
            id: 2,
            kind: StormKind::Cyclone,
            t0: 0,
            life: 80_000,
            origin: [0.0, 0.0],
            vel: [0.2, 0.0],
            curve: 0.0,
            strength: 1.0,
            size: 4000.0,
            aux: 250.0,
            seed: 3,
            tornado: None,
            manual: false,
        };
        let t = 40_000.0;
        let p = c.position(t);
        assert_eq!(c.dbz(p[0], p[1], t), 0.0, "calm clear eye");
        assert!(c.dbz(p[0] + 250.0, p[1], t) > 35.0, "eyewall");
        let w = c.wind(p[0] + 250.0, p[1], t);
        assert!(w[0].hypot(w[1]) > 70.0, "cat 5 eyewall winds");
        let calm = c.wind(p[0] + 5.0, p[1], t);
        assert!(calm[0].hypot(calm[1]) < 5.0);
    }

    #[test]
    fn squall_line_winds_behind_edge() {
        let q = StormDef {
            id: 3,
            kind: StormKind::Squall,
            t0: 0,
            life: 30_000,
            origin: [0.0, 0.0],
            vel: [0.5, 0.0],
            curve: 0.0,
            strength: 1.0,
            size: 400.0,
            aux: 3000.0,
            seed: 5,
            tornado: None,
            manual: false,
        };
        let t = 15_000.0;
        let fr = q.frame(t);
        let edge = q.squall_edge(0.0, t);
        let behind = fr.to_world(edge - 100.0, 0.0);
        let w = q.wind(behind[0], behind[1], t);
        assert!(w[0] > 25.0, "derecho wind toward the motion: {w:?}");
        let ahead = fr.to_world(edge + 2000.0, 0.0);
        assert!(q.wind(ahead[0], ahead[1], t)[0].abs() < 1.0);
    }
}
