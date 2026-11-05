//! Storm clouds, rain shafts, funnels and debris, drawn as a budgeted number
//! of translucent boxes (Horizon's world primitives): no shaders, no
//! textures, a few hundred quads on Low quality.

use super::{night_factor, ClientState};
use crate::sim::rng::{hash, unit};
use crate::sim::storm::{StormDef, StormKind, TornadoState};
use horizon_client::mod_api::WorldPrimitives;

const CLOUD_BASE: f64 = 155.0;

/// Adds boxes within a budget; pulls far ones in, keeping their apparent size.
struct Painter<'a> {
    prims: &'a mut WorldPrimitives,
    cam: [f64; 3],
    near_limit: f64,
    left: usize,
    light: f64,
}

impl Painter<'_> {
    fn cube(&mut self, c: [f64; 3], h: [f64; 3], argb: u32) {
        if self.left == 0 || h[0] <= 0.0 || h[1] <= 0.0 || h[2] <= 0.0 {
            return;
        }
        // Inside it: the fog does that job, and a box around the camera
        // would cost a full-screen blend on a phone.
        if (c[0] - self.cam[0]).abs() < h[0] + 1.0 && (c[1] - self.cam[1]).abs() < h[1] + 1.0 && (c[2] - self.cam[2]).abs() < h[2] + 1.0 {
            return;
        }
        let d = ((c[0] - self.cam[0]).powi(2) + (c[1] - self.cam[1]).powi(2) + (c[2] - self.cam[2]).powi(2)).sqrt();
        let (c, h) = if d > self.near_limit {
            // An impostor: same direction and angular size, nearer.
            let s = self.near_limit / d;
            (
                [self.cam[0] + (c[0] - self.cam[0]) * s, self.cam[1] + (c[1] - self.cam[1]) * s, self.cam[2] + (c[2] - self.cam[2]) * s],
                [h[0] * s, h[1] * s, h[2] * s],
            )
        } else {
            (c, h)
        };
        let color = shade(argb, self.light);
        self.prims.filled_box([c[0] - h[0], c[1] - h[1], c[2] - h[2]], [c[0] + h[0], c[1] + h[1], c[2] + h[2]], color);
        self.left -= 1;
    }
}

/// Darkens a colour by `light` (0..1), keeping alpha.
fn shade(argb: u32, light: f64) -> u32 {
    let a = argb >> 24;
    let f = |c: u32| ((c as f64) * light).round().clamp(0.0, 255.0) as u32;
    (a << 24) | (f((argb >> 16) & 255) << 16) | (f((argb >> 8) & 255) << 8) | f(argb & 255)
}

fn rand01(seed: u64, i: u64) -> f64 {
    unit(hash(&[seed, i]))
}

pub fn draw(c: &ClientState, partial: f32, camera: [f64; 3], prims: &mut WorldPrimitives) {
    if !c.active || !c.cfg.clouds || c.storms.is_empty() {
        return;
    }
    let budget = c.budget();
    let t = c.t as f64 + partial as f64;
    let night = night_factor(c.day);
    let mut p = Painter {
        prims,
        cam: camera,
        near_limit: 640.0,
        left: budget.boxes,
        light: 1.0 - 0.72 * night,
    };
    let ground = (camera[1] - 4.0).clamp(50.0, 140.0);
    let detail = budget.cloud_detail;

    // Tornadoes first: they matter most.
    for s in &c.storms {
        if let Some(tor) = s.tornado_at(t) {
            let d = (tor.pos[0] - camera[0]).hypot(tor.pos[1] - camera[2]);
            if d < budget.draw_distance {
                let lod = if d < 1200.0 { detail } else { 0 };
                tornado(&mut p, &tor, ground, t, lod, if d < 600.0 { budget.debris } else { budget.debris / 3 });
            }
        }
    }
    // Then storms, nearest first.
    let mut order: Vec<(f64, &StormDef)> = c
        .storms
        .iter()
        .map(|s| {
            let q = s.position(t);
            ((q[0] - camera[0]).hypot(q[1] - camera[2]), s)
        })
        .filter(|(d, s)| *d < budget.draw_distance + s.reach())
        .collect();
    order.sort_by(|a, b| a.0.total_cmp(&b.0));
    for (d, s) in order {
        let lod = if d < 1500.0 { detail } else if d < 4000.0 { 0 } else { 3 };
        match s.kind {
            StormKind::Cell | StormKind::Supercell => convective(&mut p, s, t, ground, lod),
            StormKind::Squall => squall(&mut p, s, t, ground, lod),
            StormKind::Cyclone => cyclone(&mut p, s, t, d, lod),
            StormKind::Blizzard => blizzard(&mut p, s, t, d),
        }
        if p.left == 0 {
            break;
        }
    }
}

/// Thunderstorms and supercells: tower, anvil, dark base, rain shaft, wall cloud.
fn convective(p: &mut Painter, s: &StormDef, t: f64, ground: f64, lod: u8) {
    let fr = s.frame(t);
    let r = s.radius_now(t);
    let i = s.intensity(t);
    let sup = s.kind == StormKind::Supercell;
    let top = if sup { 240.0 + 90.0 * i } else { 200.0 + 60.0 * i };
    let tower = fr.to_world(-0.05 * r, -0.05 * r);
    if lod == 3 {
        // Far away: one tower and one anvil.
        p.cube([tower[0], (CLOUD_BASE + top) / 2.0, tower[1]], [0.45 * r, (top - CLOUD_BASE) / 2.0, 0.45 * r], 0xE0D8DCE2);
        let anvil = fr.to_world(0.8 * r, 0.1 * r);
        p.cube([anvil[0], top - 10.0, anvil[1]], [1.1 * r, 10.0, 1.1 * r], 0xC8C8CCD4);
        return;
    }
    // Dark base.
    let base = fr.to_world(0.0, 0.0);
    p.cube([base[0], CLOUD_BASE - 5.0, base[1]], [0.85 * r, 5.0, 0.85 * r], 0xF0383C44);
    // Tower, leaning downstream with height.
    let segs = 3 + lod as usize * 2;
    let hseg = (top - CLOUD_BASE) / segs as f64;
    for k in 0..segs {
        let f = (k as f64 + 0.5) / segs as f64;
        let c = fr.to_world((-0.1 + 0.3 * f) * r, -0.08 * r);
        let w = r * (0.5 - 0.15 * f) * (0.9 + 0.2 * rand01(s.seed as u64, k as u64));
        let bright = 0xD0 + (0x28 as f64 * f) as u32;
        let col = 0xE000_0000 | (bright << 16) | (bright << 8) | (bright + 6).min(255);
        p.cube([c[0], CLOUD_BASE + hseg * (k as f64 + 0.5), c[1]], [w, hseg * 0.55, w], col);
    }
    // Anvil spreading downstream.
    let anvils = if sup { 2 + lod as usize } else { 1 };
    for j in 0..anvils {
        let a = fr.to_world((0.4 + 0.9 * j as f64) * r, 0.15 * r);
        let w = r * (0.7 + 0.25 * j as f64);
        p.cube([a[0], top - 12.0, a[1]], [w, 12.0 - 2.0 * j as f64, w], 0xC0C8CCD4);
    }
    // Rain (and hail) shafts.
    if i > 0.25 {
        let core = fr.to_world(0.15 * r, 0.1 * r);
        let h = (CLOUD_BASE - 10.0 - ground) / 2.0;
        let col = if s.peak_dbz() >= 62.0 { 0x5A5E7468 } else { 0x58606A78 };
        p.cube([core[0], ground + h, core[1]], [0.35 * r, h, 0.35 * r], col);
        if sup && lod >= 1 {
            let ff = fr.to_world(0.9 * r, 0.35 * r);
            p.cube([ff[0], ground + h, ff[1]], [0.4 * r, h, 0.4 * r], 0x38646C78);
        }
    }
    // Wall cloud, slowly turning, under the mesocyclone.
    if sup && i > 0.5 {
        if let Some(m) = s.meso_pos(t) {
            p.cube([m[0], CLOUD_BASE - 18.0, m[1]], [0.26 * r, 10.0, 0.26 * r], 0xF0303338);
            let n = 4 + 2 * lod as usize;
            for k in 0..n {
                let a = k as f64 / n as f64 * std::f64::consts::TAU + t * 0.012;
                let q = [m[0] + 0.22 * r * a.cos(), m[1] + 0.22 * r * a.sin()];
                p.cube([q[0], CLOUD_BASE - 24.0, q[1]], [0.07 * r, 6.0, 0.07 * r], 0xE8383B40);
            }
        }
    }
}

/// The funnel, its sway, and the debris orbiting at its foot.
fn tornado(p: &mut Painter, tor: &TornadoState, ground: f64, t: f64, lod: u8, debris: usize) {
    let segs = [8usize, 14, 22][lod.min(2) as usize];
    let h = CLOUD_BASE - 18.0 - ground;
    let seed = tor.seed as f64 * 1e-4;
    let rope = if tor.life_frac > 0.75 { 0.45 } else { 1.0 };
    for k in 0..segs {
        let f = (k as f64 + 0.5) / segs as f64;
        let y = ground + f * h;
        let rf = tor.radius * (0.18 + 0.95 * f.powf(2.2)) * (1.0 + 0.12 * (t * 0.3 + k as f64).sin()) * rope;
        let sway = tor.radius * 0.7 * f * if rope < 1.0 { 2.5 } else { 1.0 };
        let ox = (f * 3.1 + t * 0.021 + seed).sin() * sway;
        let oz = (f * 2.3 + t * 0.017 + 2.0 * seed).cos() * sway;
        p.cube([tor.pos[0] + ox, y, tor.pos[1] + oz], [rf.max(1.0), h / segs as f64 * 0.6, rf.max(1.0)], 0xD0706A64);
    }
    // Dust skirt at the ground.
    p.cube([tor.pos[0], ground + 4.0, tor.pos[1]], [tor.radius * 1.6, 4.0, tor.radius * 1.6], 0x60806A50);
    // Debris.
    const COLORS: [u32; 4] = [0xFF6B4F2A, 0xFF3A3A3A, 0xFF4F7F2F, 0xFF8B6A3E];
    let height = 8.0 + 7.0 * tor.ef_now as f64;
    for i in 0..debris {
        let u = rand01(tor.seed as u64, i as u64);
        let rho = tor.radius * (0.5 + 1.6 * u);
        let omega = tor.vmax / rho.max(1.0) / 20.0 * 0.5;
        let a = i as f64 * 2.399_963 + t * omega;
        let y = ground + 1.0 + height * rand01(tor.seed as u64, i as u64 + 1000) * (1.2 - u);
        let size = 0.3 + 0.7 * rand01(tor.seed as u64, i as u64 + 2000);
        p.cube([tor.pos[0] + rho * a.cos(), y, tor.pos[1] + rho * a.sin()], [size, size, size], COLORS[i % 4]);
    }
}

/// Squall lines: a long shelf cloud, rain curtain and stratiform deck behind.
fn squall(p: &mut Painter, s: &StormDef, t: f64, ground: f64, lod: u8) {
    let fr = s.frame(t);
    let l = s.aux as f64;
    let w = s.size as f64;
    let segs = [6usize, 10, 16, 4][lod.min(3) as usize];
    let step = 2.0 * l / segs as f64;
    let half = (step * 0.5).max(0.3 * w);
    for k in 0..segs {
        let v = -l + (k as f64 + 0.5) * step;
        let edge = s.squall_edge(v, t);
        let shelf = fr.to_world(edge - 0.1 * w, v);
        p.cube([shelf[0], CLOUD_BASE - 8.0, shelf[1]], [half, 14.0, half], 0xF0353A42);
        if lod < 3 {
            let deck = fr.to_world(edge - 1.3 * w, v);
            p.cube([deck[0], CLOUD_BASE + 40.0, deck[1]], [half.max(0.9 * w), 30.0, half.max(0.9 * w)], 0x90A0A6AE);
            let rain = fr.to_world(edge - 0.5 * w, v);
            let h = (CLOUD_BASE - 22.0 - ground) / 2.0;
            p.cube([rain[0], ground + h, rain[1]], [half.min(0.4 * w), h, half.min(0.4 * w)], 0x4860687A);
        }
    }
}

/// Hurricanes: the eyewall and spiral bands, when close enough to matter.
fn cyclone(p: &mut Painter, s: &StormDef, t: f64, d: f64, lod: u8) {
    let c = s.position(t);
    let rm = s.aux as f64;
    let big = s.size as f64;
    if d > big * 2.5 {
        return;
    }
    let n = 12 + 6 * lod.min(2) as usize;
    let spin = t * 0.0006;
    for k in 0..n {
        let a = k as f64 / n as f64 * std::f64::consts::TAU + spin;
        p.cube([c[0] + rm * a.cos(), 200.0, c[1] + rm * a.sin()], [0.28 * rm, 80.0, 0.28 * rm], 0xE0C8CCD2);
    }
    let bands = 16 + 8 * lod.min(2) as usize;
    for k in 0..bands {
        let theta = k as f64 * 0.45;
        for arm in 0..2 {
            let r = rm * (theta * 0.3f64.tan()).exp() * 1.4;
            if r > big {
                break;
            }
            let a = theta + spin + arm as f64 * std::f64::consts::PI;
            p.cube([c[0] + r * a.cos(), 185.0, c[1] + r * a.sin()], [0.1 * r + 30.0, 14.0, 0.1 * r + 30.0], 0xB0B4BAC2);
        }
    }
}

/// Blizzards are mostly whiteout fog; from outside, a low grey deck.
fn blizzard(p: &mut Painter, s: &StormDef, t: f64, d: f64) {
    if d < s.size as f64 * 0.8 {
        return;
    }
    let fr = s.frame(t);
    let big = s.size as f64;
    for k in 0..5 {
        let u = (k as f64 - 2.0) * 0.45 * big;
        let q = fr.to_world(u, 0.0);
        p.cube([q[0], CLOUD_BASE + 10.0, q[1]], [0.35 * big, 25.0, 0.35 * big], 0xB0C8CCD0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shading_keeps_alpha() {
        assert_eq!(shade(0x80FFFFFF, 0.5), 0x80808080);
        assert_eq!(shade(0xFF102030, 1.0), 0xFF102030);
    }
}
