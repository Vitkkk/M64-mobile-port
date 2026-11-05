//! Doppler radar products: reflectivity and radial velocity images.

use super::field::wind_at;
use super::storm::{StormDef, TornadoState};

/// Which product the radar shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Product {
    Reflectivity,
    Velocity,
}

/// Colour (ARGB) of a reflectivity, transparent below 5 dBZ.
pub fn dbz_color(dbz: f64) -> u32 {
    const T: [(f64, u32); 15] = [
        (75.0, 0xFDFDFD),
        (70.0, 0x9854C6),
        (65.0, 0xF800FD),
        (60.0, 0xBC0000),
        (55.0, 0xD40000),
        (50.0, 0xFD0000),
        (45.0, 0xFD9500),
        (40.0, 0xE5BC00),
        (35.0, 0xFDF802),
        (30.0, 0x008E00),
        (25.0, 0x01C501),
        (20.0, 0x02FD02),
        (15.0, 0x0300F4),
        (10.0, 0x019FF4),
        (5.0, 0x04E9E7),
    ];
    for (min, rgb) in T {
        if dbz >= min {
            return 0xFF00_0000 | rgb;
        }
    }
    0
}

/// Colour (ARGB) of a radial velocity, m/s: green toward the radar, red away.
pub fn velocity_color(v: f64) -> u32 {
    let a = v.abs();
    let rgb = if v < 0.0 {
        match a {
            a if a >= 50.0 => 0x00FFFF,
            a if a >= 35.0 => 0x02FC02,
            a if a >= 25.0 => 0x01C501,
            a if a >= 15.0 => 0x008E00,
            a if a >= 5.0 => 0x356C35,
            _ => 0x4A5A4A,
        }
    } else {
        match a {
            a if a >= 50.0 => 0xFFFF00,
            a if a >= 35.0 => 0xFD0000,
            a if a >= 25.0 => 0xD40000,
            a if a >= 15.0 => 0x9C0000,
            a if a >= 5.0 => 0x6C3535,
            _ => 0x5A4A4A,
        }
    };
    0xFF00_0000 | rgb
}

/// Radial velocity at (x, z) seen from the radar at `site`, m/s (positive away).
pub fn radial_velocity(wind: [f64; 2], site: [f64; 2], x: f64, z: f64) -> f64 {
    let dx = x - site[0];
    let dz = z - site[1];
    let r = dx.hypot(dz);
    if r < 1.0 {
        return 0.0;
    }
    (wind[0] * dx + wind[1] * dz) / r
}

/// Builds an `n` x `n` image (row-major, north up, ARGB, 0 = no echo) of the
/// area `range` blocks around `site`.
#[allow(clippy::too_many_arguments)]
pub fn image(
    storms: &[StormDef],
    tors: &[(usize, TornadoState)],
    background: [f64; 2],
    site: [f64; 2],
    range: f64,
    n: usize,
    product: Product,
    t: f64,
    out: &mut Vec<u32>,
) {
    out.clear();
    out.resize(n * n, 0);
    let cell = 2.0 * range / n as f64;
    // Only storms that can reach the image.
    let relevant: Vec<StormDef> = storms
        .iter()
        .filter(|s| {
            let p = s.position(t);
            (p[0] - site[0]).abs() < range + s.reach() && (p[1] - site[1]).abs() < range + s.reach()
        })
        .copied()
        .collect();
    if relevant.is_empty() {
        return;
    }
    for row in 0..n {
        let z = site[1] - range + (row as f64 + 0.5) * cell;
        for col in 0..n {
            let x = site[0] - range + (col as f64 + 0.5) * cell;
            let mut dbz: f64 = 0.0;
            for s in &relevant {
                dbz = dbz.max(s.dbz(x, z, t));
            }
            let color = match product {
                Product::Reflectivity => dbz_color(dbz),
                Product::Velocity if dbz >= 5.0 => {
                    // A tornado smaller than a bin still shows as a couplet
                    // (TVS): the bins next to it read its peak winds.
                    let (mut sx, mut sz) = (x, z);
                    for (_, tor) in tors {
                        let (dx, dz) = (x - tor.pos[0], z - tor.pos[1]);
                        let d = dx.hypot(dz);
                        if d > 1e-6 && d < cell * 1.2 && d > tor.radius {
                            sx = tor.pos[0] + dx / d * tor.radius;
                            sz = tor.pos[1] + dz / d * tor.radius;
                        }
                    }
                    let (w, _) = wind_at(&relevant, tors, background, sx, sz, t);
                    velocity_color(radial_velocity(w, site, sx, sz))
                }
                Product::Velocity => 0,
            };
            out[row * n + col] = color;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sim::spawn::Spawner;
    use crate::sim::storm::StormKind;

    #[test]
    fn colours() {
        assert_eq!(dbz_color(0.0), 0);
        assert_eq!(dbz_color(66.0), 0xFFF8_00FD);
        assert_ne!(velocity_color(-30.0), velocity_color(30.0));
        assert!(radial_velocity([10.0, 0.0], [0.0, 0.0], 100.0, 0.0) > 9.9);
        assert!(radial_velocity([10.0, 0.0], [0.0, 0.0], -100.0, 0.0) < -9.9);
    }

    #[test]
    fn image_shows_storm_and_couplet() {
        let sp = Spawner::new(3, 48.0);
        let t = 50_000;
        let s = sp.manual(StormKind::Supercell, [0.0, 0.0], t, Some(4), 0);
        let storms = [s];
        let tf = t as f64 + 100.0;
        let tors: Vec<_> = crate::sim::field::tornadoes(&storms, tf);
        assert!(!tors.is_empty());
        let mut img = Vec::new();
        image(&storms, &tors, [0.0, 0.0], [-1500.0, 0.0], 3000.0, 48, Product::Reflectivity, tf, &mut img);
        assert!(img.iter().filter(|&&c| c != 0).count() > 20);
        image(&storms, &tors, [0.0, 0.0], [-1500.0, 0.0], 3000.0, 48, Product::Velocity, tf, &mut img);
        let reds = img.iter().filter(|&&c| c == 0xFFFF_FF00 || c == 0xFFFD_0000).count();
        let greens = img.iter().filter(|&&c| c == 0xFF00_FFFF || c == 0xFF02_FC02).count();
        assert!(reds + greens > 0, "a velocity couplet shows");
    }
}
