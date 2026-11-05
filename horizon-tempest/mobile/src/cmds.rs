//! The vanilla commands Tempest runs as the player: pure functions, tested
//! on the host. Positions on the ground use `positioned over`, so nothing
//! has to read the world first.

use crate::sim::damage::{Run, Surface, FILTERS, PALETTE};

fn heightmap(s: Surface) -> &'static str {
    match s {
        Surface::Motion => "motion_blocking",
        Surface::World => "world_surface",
    }
}

/// A `fill` that applies one damage run over a square of side 2r+1 around
/// column (x, z), counted from that column's surface.
pub fn damage_fill(x: i32, z: i32, r: i32, run: &Run) -> String {
    let lo = run.depth;
    let hi = run.depth + run.height as i32 - 1;
    format!(
        "execute positioned {x} 0 {z} positioned over {} run fill ~-{r} ~{lo} ~-{r} ~{r} ~{hi} ~{r} {} replace {}",
        heightmap(run.surface),
        PALETTE[run.block as usize],
        FILTERS[run.filter as usize],
    )
}

/// Particles on the ground at (x, z), `dy` above the surface, spread
/// over a box of half-sizes (sx, sy, sz), seen from far away.
#[allow(clippy::too_many_arguments)]
pub fn particle(kind: &str, x: f64, z: f64, dy: f64, sx: f64, sy: f64, sz: f64, speed: f64, count: u32) -> String {
    format!(
        "execute positioned {x:.1} 0 {z:.1} positioned over motion_blocking run particle {kind} ~ ~{dy:.1} ~ {sx:.1} {sy:.1} {sz:.1} {speed:.2} {count} force"
    )
}

pub fn lightning(x: i32, z: i32) -> String {
    format!("execute positioned {x} 0 {z} positioned over motion_blocking run summon minecraft:lightning_bolt")
}

/// A sound only the player hears, from (x, y, z).
pub fn sound(name: &str, category: &str, at: [f64; 3], volume: f64, pitch: f64) -> String {
    format!(
        "playsound {name} {category} @s {:.1} {:.1} {:.1} {:.2} {:.2}",
        at[0], at[1], at[2], volume.clamp(0.0, 4.0), pitch.clamp(0.5, 2.0)
    )
}

/// Mobs and items near a vortex at (x, y, z) spiral in and up.
pub fn suck(x: f64, y: f64, z: f64, reach: f64, swirl: f64, lift: f64, pull: f64) -> String {
    format!(
        "execute positioned {x:.1} {y:.1} {z:.1} as @e[type=!minecraft:player,distance=..{reach:.0},limit=24] at @s facing {x:.1} ~ {z:.1} run tp @s ^{swirl:.2} ^{lift:.2} ^{pull:.2}"
    )
}

pub fn hurt(amount: f64) -> String {
    format!("damage @s {amount:.1} minecraft:generic")
}

/// Hail hurts only in the open: the player stands at the surface.
pub fn hurt_if_exposed(amount: f64) -> String {
    format!("execute at @s positioned over motion_blocking if entity @s[distance=..1.2] run damage @s {amount:.1} minecraft:generic")
}

pub fn lift(seconds: u32, level: u32) -> String {
    format!("effect give @s minecraft:levitation {seconds} {level} true")
}

pub fn weather(state: u8) -> &'static str {
    ["weather clear 600", "weather rain 600", "weather thunder 600"][state.min(2) as usize]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sim::damage::{self, Run};

    #[test]
    fn commands_read_right() {
        let run = Run { dx: 0, dz: 0, depth: -4, height: 4, surface: Surface::Motion, block: damage::AIR, filter: damage::F_LEAVES };
        assert_eq!(
            damage_fill(10, -3, 2, &run),
            "execute positioned 10 0 -3 positioned over motion_blocking run fill ~-2 ~-4 ~-2 ~2 ~-1 ~2 minecraft:air replace #minecraft:leaves"
        );
        let snow = Run { dx: 0, dz: 0, depth: 0, height: 1, surface: Surface::Motion, block: damage::SNOW_LAYER, filter: damage::F_AIR };
        assert!(damage_fill(0, 0, 1, &snow).ends_with("~-1 ~0 ~-1 ~1 ~0 ~1 minecraft:snow[layers=1] replace minecraft:air"));
        assert!(particle("minecraft:large_smoke", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 0.1, 8).ends_with("4.0 5.0 6.0 0.10 8 force"));
        assert_eq!(sound("minecraft:event.raid.horn", "master", [1.0, 2.0, 3.0], 9.0, 0.1), "playsound minecraft:event.raid.horn master @s 1.0 2.0 3.0 4.00 0.50");
        assert!(lightning(5, 6).ends_with("summon minecraft:lightning_bolt"));
        assert!(suck(0.0, 64.0, 0.0, 20.0, 0.4, 0.2, 0.5).contains("distance=..20,limit=24"));
        assert_eq!(weather(9), "weather thunder 600");
    }
}
