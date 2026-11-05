//! The weather at a point: storms combined with the background climate.

use super::climate::Env;
use super::precip::{classify, Precip};
use super::storm::{StormDef, TornadoState};

/// Everything the game wants to know about one spot.
#[derive(Clone, Copy, Debug, Default)]
pub struct Sample {
    /// Reflectivity, dBZ.
    pub dbz: f64,
    /// Horizontal wind, m/s (x, z).
    pub wind: [f64; 2],
    /// Updraft, m/s.
    pub up: f64,
    /// Sky covered by storm clouds, 0..1.
    pub cover: f64,
    pub precip: Precip,
    /// Distance to the nearest tornado, blocks (infinite when none).
    pub tornado_dist: f64,
}

impl Sample {
    pub fn speed(&self) -> f64 {
        self.wind[0].hypot(self.wind[1])
    }
}

/// Highest reflectivity of all storms at (x, z).
pub fn dbz_at(storms: &[StormDef], x: f64, z: f64, t: f64) -> f64 {
    storms.iter().fold(0.0, |m: f64, s| m.max(s.dbz(x, z, t)))
}

/// The storm most responsible for the echo at (x, z).
pub fn dominant(storms: &[StormDef], x: f64, z: f64, t: f64) -> Option<(usize, f64)> {
    let mut best = None;
    for (i, s) in storms.iter().enumerate() {
        let d = s.dbz(x, z, t);
        if d > 0.0 && best.is_none_or(|(_, b)| d > b) {
            best = Some((i, d));
        }
    }
    best
}

/// Tornadoes on the ground at `t`, with the index of their storm.
pub fn tornadoes(storms: &[StormDef], t: f64) -> Vec<(usize, TornadoState)> {
    storms
        .iter()
        .enumerate()
        .filter_map(|(i, s)| s.tornado_at(t).map(|tor| (i, tor)))
        .collect()
}

/// Wind at (x, z): background, storms and tornadoes. Returns (wind, updraft).
pub fn wind_at(storms: &[StormDef], tors: &[(usize, TornadoState)], background: [f64; 2], x: f64, z: f64, t: f64) -> ([f64; 2], f64) {
    let mut w = background;
    let mut up: f64 = 0.0;
    for s in storms {
        let sw = s.wind(x, z, t);
        w[0] += sw[0];
        w[1] += sw[1];
    }
    for (_, tor) in tors {
        let (tw, tu) = tor.wind(x, z);
        w[0] += tw[0];
        w[1] += tw[1];
        up = up.max(tu);
    }
    (w, up)
}

/// Full sample at (x, z).
pub fn sample(storms: &[StormDef], tors: &[(usize, TornadoState)], env: &Env, x: f64, z: f64, t: f64) -> Sample {
    let mut dbz: f64 = 0.0;
    let mut cover: f64 = 0.0;
    let mut convective = false;
    for s in storms {
        let d = s.dbz(x, z, t);
        if d > dbz {
            dbz = d;
            convective = s.kind.convective();
        }
        cover = cover.max(s.cover(x, z, t));
    }
    let (wind, up) = wind_at(storms, tors, env.wind, x, z, t);
    let tornado_dist = tors
        .iter()
        .map(|(_, tor)| (tor.pos[0] - x).hypot(tor.pos[1] - z))
        .fold(f64::INFINITY, f64::min);
    Sample {
        dbz,
        wind,
        up,
        cover: cover.max((dbz / 45.0).min(1.0)),
        precip: classify(dbz, env.temp_c, convective),
        tornado_dist,
    }
}

/// Compass point of a direction the wind blows *from*, given the wind vector.
pub fn compass_from(wind: [f64; 2]) -> &'static str {
    // Blowing toward (x, z); it comes from the opposite side. North is -z.
    let (fx, fz) = (-wind[0], -wind[1]);
    let ang = fx.atan2(-fz).to_degrees().rem_euclid(360.0); // 0 = north, 90 = east
    const P: [&str; 8] = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
    P[((ang + 22.5) / 45.0) as usize % 8]
}

/// Compass point of a bearing from one place to another.
pub fn compass_to(from: [f64; 2], to: [f64; 2]) -> &'static str {
    compass_from([from[0] - to[0], from[1] - to[1]])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compass() {
        // A wind blowing toward +x (east) comes from the west.
        assert_eq!(compass_from([5.0, 0.0]), "W");
        assert_eq!(compass_from([0.0, 5.0]), "N");
        assert_eq!(compass_to([0.0, 0.0], [0.0, -100.0]), "N");
        assert_eq!(compass_to([0.0, 0.0], [100.0, 100.0]), "SE");
    }
}
