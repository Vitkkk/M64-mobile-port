//! The "thermodynamic" environment: temperature, dew point, instability (CAPE),
//! wind shear and helicity, as smooth closed-form fields of position and time.
//!
//! Air masses drift with a constant steering flow, so fronts and moist tongues
//! move across the world. Everything is computed on demand from noise: no grid
//! is stored and nothing has to be synchronised.

use super::noise::{fbm2, value2};

/// Ticks in a Minecraft day.
pub const DAY: i64 = 24_000;
/// Seconds per tick.
pub const TICK_S: f64 = 0.05;

/// Severe weather risk category (like a convective outlook).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Risk {
    None,
    Thunder,
    Marginal,
    Slight,
    Enhanced,
    Moderate,
    High,
}

impl Risk {
    pub fn label(self) -> &'static str {
        match self {
            Risk::None => "NONE",
            Risk::Thunder => "TSTM",
            Risk::Marginal => "MRGL",
            Risk::Slight => "SLGT",
            Risk::Enhanced => "ENH",
            Risk::Moderate => "MDT",
            Risk::High => "HIGH",
        }
    }
    /// RGB colour of the category, as outlook maps draw them.
    pub fn rgb(self) -> u32 {
        match self {
            Risk::None => 0xA0A0A0,
            Risk::Thunder => 0xC1E9C1,
            Risk::Marginal => 0x66A366,
            Risk::Slight => 0xF6F67B,
            Risk::Enhanced => 0xE6C27A,
            Risk::Moderate => 0xE67F7F,
            Risk::High => 0xFF66FF,
        }
    }
}

/// The environment at one place and time.
#[derive(Clone, Copy, Debug, Default)]
pub struct Env {
    /// 2 m temperature, °C.
    pub temp_c: f64,
    /// Dew point, °C.
    pub dew_c: f64,
    /// Surface-based CAPE, J/kg.
    pub cape: f64,
    /// 0-6 km bulk shear, knots.
    pub shear_kt: f64,
    /// 0-1 km storm relative helicity, m²/s².
    pub srh: f64,
    /// Lifted condensation level, m above ground.
    pub lcl_m: f64,
    /// Background (synoptic) wind at the surface, m/s, (x, z).
    pub wind: [f64; 2],
    /// Steering wind for storms, m/s, (x, z).
    pub steering: [f64; 2],
    /// Supercell composite parameter.
    pub scp: f64,
    /// Significant tornado parameter.
    pub stp: f64,
    /// Season phase in [0, 1): 0 spring, 0.25 summer, 0.5 autumn, 0.75 winter.
    pub season: f64,
}

impl Env {
    pub fn risk(&self) -> Risk {
        if self.stp > 6.0 {
            Risk::High
        } else if self.stp > 3.5 {
            Risk::Moderate
        } else if self.stp > 1.5 || self.scp > 8.0 {
            Risk::Enhanced
        } else if self.scp > 3.0 {
            Risk::Slight
        } else if self.scp > 1.0 || (self.cape > 1500.0 && self.shear_kt > 25.0) {
            Risk::Marginal
        } else if self.cape > 400.0 {
            Risk::Thunder
        } else {
            Risk::None
        }
    }
}

/// The climate generator.
#[derive(Clone, Debug)]
pub struct Climate {
    pub seed: u64,
    /// Length of a year in Minecraft days.
    pub year_days: f64,
}

/// Steering flow that carries air masses (blocks per second == m/s).
const DRIFT: [f64; 2] = [6.0, 1.5];
/// Size of air masses in blocks.
const AIRMASS: f64 = 9000.0;

impl Climate {
    pub fn new(seed: u64, year_days: f64) -> Self {
        Climate {
            seed,
            year_days: year_days.max(4.0),
        }
    }

    /// Season phase at game time `t` (ticks).
    pub fn season(&self, t: f64) -> f64 {
        let days = t / DAY as f64;
        (days / self.year_days).rem_euclid(1.0)
    }

    /// Environment at (x, z) at game time `t` (ticks). `day_time` is the time of
    /// day in ticks (0 = sunrise); pass `t` when unknown.
    pub fn sample(&self, x: f64, z: f64, t: f64, day_time: f64) -> Env {
        let secs = t * TICK_S;
        // Advected coordinates: air masses move with the drift.
        let ax = (x - DRIFT[0] * secs) / AIRMASS;
        let az = (z - DRIFT[1] * secs) / AIRMASS;
        let s = self.season(t);
        let tau = std::f64::consts::TAU;

        let seasonal = 11.0 * (tau * s).sin(); // warmest at s = 0.25
        let tod = day_time.rem_euclid(DAY as f64);
        let diurnal = 5.0 * (tau * (tod - 9000.0) / DAY as f64).cos(); // warmest ~15h
        let mass = fbm2(self.seed ^ 0x11, ax, az, 3) * 2.0 - 1.0;
        let temp_c = 15.0 + seasonal + diurnal + 11.0 * mass;

        let moist = fbm2(self.seed ^ 0x22, ax * 1.3 + 17.0, az * 1.3, 3);
        let spread = 1.5 + 15.0 * (1.0 - moist).powf(1.3);
        let dew_c = (temp_c - spread).min(27.0);

        let instab = 0.4 + 1.1 * fbm2(self.seed ^ 0x33, ax * 1.7, az * 1.7 - 9.0, 2);
        let heating = 0.45 + 0.55 * ((diurnal / 5.0) * 0.5 + 0.5);
        let cape = ((dew_c - 9.0).max(0.0).powf(1.6) * 85.0 * instab * heating).min(6500.0);

        let transition = (tau * s).cos().abs(); // spring and autumn
        let shear_n = fbm2(self.seed ^ 0x44, ax * 0.9 - 31.0, az * 0.9, 2);
        let shear_kt = (12.0 + 60.0 * shear_n) * (0.65 + 0.55 * transition);
        let srh_n = value2(self.seed ^ 0x55, ax * 2.1, az * 2.1 + 5.0);
        let srh = (shear_kt * (2.5 + 5.0 * srh_n) - 45.0).clamp(0.0, 650.0);
        let lcl_m = 125.0 * (temp_c - dew_c).max(0.0);

        // Synoptic wind: westerlies that vary from place to place.
        let wn = fbm2(self.seed ^ 0x66, ax * 2.0, az * 2.0 + 40.0, 2) - 0.5;
        let wm = fbm2(self.seed ^ 0x77, ax * 2.0 + 40.0, az * 2.0, 2) - 0.5;
        let wind = [3.0 + 10.0 * wn + 0.06 * shear_kt, 8.0 * wm];
        let steering = [
            DRIFT[0] + 0.12 * shear_kt * (0.6 + wn),
            DRIFT[1] + 6.0 * wm,
        ];

        let shear_ms = shear_kt * 0.5144;
        let scp = if shear_ms < 10.0 {
            0.0
        } else {
            (cape / 1000.0) * (srh / 50.0) * (shear_ms / 20.0).min(1.5)
        };
        let lcl_term = ((2000.0 - lcl_m) / 1000.0).clamp(0.0, 1.0);
        let stp = if shear_ms < 12.5 {
            0.0
        } else {
            (cape / 1500.0) * (srh / 150.0) * (shear_ms / 20.0).min(1.5) * lcl_term
        };
        Env {
            temp_c,
            dew_c,
            cape,
            shear_kt,
            srh,
            lcl_m,
            wind,
            steering,
            scp,
            stp,
            season: s,
        }
    }
}

/// Season name for the HUD.
pub fn season_name(s: f64) -> &'static str {
    match (s * 4.0) as u32 {
        0 => "Spring",
        1 => "Summer",
        2 => "Autumn",
        _ => "Winter",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_are_physical() {
        let c = Climate::new(42, 48.0);
        let mut max_cape: f64 = 0.0;
        let mut max_stp: f64 = 0.0;
        for i in 0..2000 {
            let x = (i as f64 * 977.0) % 60_000.0 - 30_000.0;
            let z = (i as f64 * 613.0) % 60_000.0 - 30_000.0;
            let t = i as f64 * 5000.0;
            let e = c.sample(x, z, t, t);
            assert!(e.temp_c > -40.0 && e.temp_c < 50.0, "{e:?}");
            assert!(e.dew_c <= e.temp_c);
            assert!(e.cape >= 0.0 && e.cape <= 6500.0);
            assert!(e.srh >= 0.0 && e.lcl_m >= 0.0);
            max_cape = max_cape.max(e.cape);
            max_stp = max_stp.max(e.stp);
        }
        assert!(max_cape > 2000.0, "some days are unstable: {max_cape}");
        assert!(max_stp > 1.0, "some days are tornadic: {max_stp}");
    }

    #[test]
    fn seasons_cycle() {
        let c = Climate::new(1, 48.0);
        assert!(c.season(0.0) < 0.01);
        let summer = 48.0 * 0.25 * DAY as f64;
        assert!((c.season(summer) - 0.25).abs() < 1e-9);
        assert_eq!(season_name(0.3), "Summer");
    }
}
