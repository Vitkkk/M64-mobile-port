//! Wind damage to the world, as bulk block edits.
//!
//! Damage is planned per column from the wind speed alone: each step of the
//! scale strips more from the top of the column (leaves, glass, wood, then the
//! grass itself), filtered by block tags, so the server never has to read a
//! block first. Columns hit again and again as a tornado passes lose a little
//! more each time, which gives the progressive, path-shaped destruction.

use super::rng::Rng;

/// Blocks the edits can place, by index.
pub const PALETTE: [&str; 5] = [
    "minecraft:air",
    "minecraft:dirt",
    "minecraft:coarse_dirt",
    "minecraft:snow[layers=1]",
    "minecraft:ice",
];
pub const AIR: u8 = 0;
pub const DIRT: u8 = 1;
pub const COARSE_DIRT: u8 = 2;
pub const SNOW_LAYER: u8 = 3;
pub const ICE: u8 = 4;

/// Block predicates the edits filter on, by index (vanilla tags and blocks).
pub const FILTERS: [&str; 22] = [
    "#minecraft:leaves",
    "#minecraft:small_flowers",
    "minecraft:short_grass",
    "#minecraft:impermeable",
    "minecraft:glass_pane",
    "#minecraft:logs",
    "#minecraft:planks",
    "#minecraft:wooden_stairs",
    "#minecraft:wooden_slabs",
    "#minecraft:wooden_fences",
    "#minecraft:wool",
    "minecraft:grass_block",
    "minecraft:dirt",
    "#minecraft:wooden_doors",
    "#minecraft:wooden_trapdoors",
    "#minecraft:beds",
    "#minecraft:crops",
    "minecraft:cobblestone",
    "minecraft:stone_bricks",
    "minecraft:bricks",
    "minecraft:air",
    "minecraft:water",
];
pub const F_LEAVES: u8 = 0;
pub const F_FLOWERS: u8 = 1;
pub const F_SHORT_GRASS: u8 = 2;
pub const F_GLASS: u8 = 3;
pub const F_PANE: u8 = 4;
pub const F_LOGS: u8 = 5;
pub const F_PLANKS: u8 = 6;
pub const F_STAIRS: u8 = 7;
pub const F_SLABS: u8 = 8;
pub const F_FENCES: u8 = 9;
pub const F_WOOL: u8 = 10;
pub const F_GRASS_BLOCK: u8 = 11;
pub const F_DIRT: u8 = 12;
pub const F_DOORS: u8 = 13;
pub const F_TRAPDOORS: u8 = 14;
pub const F_BEDS: u8 = 15;
pub const F_CROPS: u8 = 16;
pub const F_COBBLE: u8 = 17;
pub const F_STONE_BRICKS: u8 = 18;
pub const F_BRICKS: u8 = 19;
pub const F_AIR: u8 = 20;
pub const F_WATER: u8 = 21;

/// Which surface a run counts from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Surface {
    /// Top block that blocks motion (leaves included).
    Motion,
    /// Top non-air block (plants included).
    World,
}

/// One planned edit: `height` blocks from `depth` below the surface (the top
/// block is depth -1), set to `block` where `filter` matches.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Run {
    pub dx: i32,
    pub dz: i32,
    pub depth: i32,
    pub height: u32,
    pub surface: Surface,
    pub block: u8,
    pub filter: u8,
}

fn strip(out: &mut Vec<Run>, filter: u8, depth: u32) {
    out.push(Run { dx: 0, dz: 0, depth: -(depth as i32), height: depth, surface: Surface::Motion, block: AIR, filter });
}

/// Plans damage to one column hit by a wind of `speed` m/s. Appends to `out`.
pub fn plan_column(speed: f64, rng: &mut Rng, out: &mut Vec<Run>) {
    if speed < 29.0 {
        return;
    }
    // Partial damage near the thresholds.
    let p = ((speed - 27.0) / 20.0).clamp(0.15, 1.0);
    if !rng.chance(p) {
        return;
    }
    // EF0: plants and the tops of trees.
    out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::World, block: AIR, filter: F_FLOWERS });
    out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::World, block: AIR, filter: F_SHORT_GRASS });
    out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::World, block: AIR, filter: F_CROPS });
    let leaves = match speed {
        s if s >= 74.0 => 24,
        s if s >= 61.0 => 14,
        s if s >= 50.0 => 8,
        s if s >= 38.0 => 4,
        _ => 2,
    };
    strip(out, F_LEAVES, leaves);
    if speed >= 38.0 {
        // EF1: windows, doors, wool, fences.
        strip(out, F_GLASS, 2);
        strip(out, F_PANE, 2);
        strip(out, F_WOOL, 1);
        strip(out, F_DOORS, 2);
        strip(out, F_TRAPDOORS, 2);
        strip(out, F_FENCES, 1);
    }
    if speed >= 50.0 {
        // EF2: roofs and trunks.
        let wood = if speed >= 89.0 { 16 } else if speed >= 74.0 { 8 } else if speed >= 61.0 { 4 } else { 2 };
        strip(out, F_PLANKS, wood);
        strip(out, F_STAIRS, wood);
        strip(out, F_SLABS, wood);
        strip(out, F_BEDS, 2);
        let logs = if speed >= 74.0 { 12 } else if speed >= 61.0 { 6 } else { 2 };
        strip(out, F_LOGS, logs);
    }
    if speed >= 61.0 {
        // EF3: the grass is scoured off.
        let to = if speed >= 74.0 { COARSE_DIRT } else { DIRT };
        out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::Motion, block: to, filter: F_GRASS_BLOCK });
    }
    if speed >= 89.0 {
        // EF5: ground scoured, masonry tops stripped.
        out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::Motion, block: COARSE_DIRT, filter: F_DIRT });
        strip(out, F_COBBLE, 1);
        strip(out, F_STONE_BRICKS, 1);
        strip(out, F_BRICKS, 1);
    }
}

/// A layer of snow on top of a column (only onto air above solid ground).
pub fn snow_column(out: &mut Vec<Run>) {
    out.push(Run { dx: 0, dz: 0, depth: 0, height: 1, surface: Surface::Motion, block: SNOW_LAYER, filter: F_AIR });
}

/// Freezing rain glazes still water at the surface.
pub fn freeze_column(out: &mut Vec<Run>) {
    out.push(Run { dx: 0, dz: 0, depth: -1, height: 1, surface: Surface::World, block: ICE, filter: F_WATER });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stronger_wind_does_more() {
        let mut rng = Rng::new(1);
        let mut weak = Vec::new();
        let mut strong = Vec::new();
        for _ in 0..50 {
            plan_column(32.0, &mut rng, &mut weak);
            plan_column(95.0, &mut rng, &mut strong);
        }
        assert!(strong.len() > weak.len());
        assert!(strong.iter().any(|r| r.filter == F_GRASS_BLOCK));
        assert!(!weak.iter().any(|r| r.filter == F_LOGS));
        let mut none = Vec::new();
        plan_column(20.0, &mut rng, &mut none);
        assert!(none.is_empty());
        for r in strong.iter().chain(&weak) {
            assert!((r.filter as usize) < FILTERS.len());
            assert!((r.block as usize) < PALETTE.len());
            assert!(r.depth < 0 && r.height as i32 == -r.depth || r.height == 1);
        }
    }
}
