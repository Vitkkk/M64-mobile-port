//! Cheap seeded value noise (2D and 3D) with fractal sums. Used for the
//! climate fields, the texture of radar echoes and the shape of clouds.

use super::rng::{hash, unit};

#[inline]
fn fade(t: f64) -> f64 {
    t * t * (3.0 - 2.0 * t)
}

#[inline]
fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + (b - a) * t
}

#[inline]
fn corner2(seed: u64, x: i64, z: i64) -> f64 {
    unit(hash(&[seed, x as u64, z as u64]))
}

/// Value noise in [0, 1).
pub fn value2(seed: u64, x: f64, z: f64) -> f64 {
    let (xf, zf) = (x.floor(), z.floor());
    let (xi, zi) = (xf as i64, zf as i64);
    let (tx, tz) = (fade(x - xf), fade(z - zf));
    let a = corner2(seed, xi, zi);
    let b = corner2(seed, xi + 1, zi);
    let c = corner2(seed, xi, zi + 1);
    let d = corner2(seed, xi + 1, zi + 1);
    lerp(lerp(a, b, tx), lerp(c, d, tx), tz)
}

/// Value noise in [0, 1) over (x, z, t).
pub fn value3(seed: u64, x: f64, z: f64, t: f64) -> f64 {
    let tf = t.floor();
    let ti = tf as i64;
    let tt = fade(t - tf);
    let s0 = seed ^ (ti as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    let s1 = seed ^ ((ti + 1) as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    lerp(value2(s0, x, z), value2(s1, x, z), tt)
}

/// Fractal sum of `octaves` layers of 2D noise, in [0, 1).
pub fn fbm2(seed: u64, x: f64, z: f64, octaves: u32) -> f64 {
    let (mut sum, mut amp, mut norm, mut f) = (0.0, 1.0, 0.0, 1.0);
    for o in 0..octaves {
        sum += amp * value2(seed.wrapping_add(o as u64 * 1013), x * f, z * f);
        norm += amp;
        amp *= 0.5;
        f *= 2.03;
    }
    sum / norm
}

/// Fractal sum of 3D noise, in [0, 1).
pub fn fbm3(seed: u64, x: f64, z: f64, t: f64, octaves: u32) -> f64 {
    let (mut sum, mut amp, mut norm, mut f) = (0.0, 1.0, 0.0, 1.0);
    for o in 0..octaves {
        sum += amp * value3(seed.wrapping_add(o as u64 * 7919), x * f, z * f, t * f);
        norm += amp;
        amp *= 0.5;
        f *= 2.03;
    }
    sum / norm
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn range_and_continuity() {
        for i in 0..1000 {
            let x = i as f64 * 0.137;
            let v = fbm2(5, x, -x * 0.7, 3);
            assert!((0.0..1.0).contains(&v));
            let w = fbm2(5, x + 1e-4, -x * 0.7, 3);
            assert!((v - w).abs() < 0.01, "noise is continuous");
        }
        assert_eq!(value3(1, 0.3, 0.4, 2.5), value3(1, 0.3, 0.4, 2.5));
    }
}
