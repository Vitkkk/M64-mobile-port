//! Wind damage to the world, planned per column from the wind speed alone.
//!
//! Each step of the EF scale does more, top-down, filtered by block so
//! nothing has to be read first:
//!
//! * EF0: plants, snow cover and the tops of tree crowns go;
//! * EF1: crowns stripped further; glass, doors, fences, wool break;
//! * EF2: trees lose all leaves and are debarked (logs become stripped
//!   logs); grass is scoured to dirt; roofs (planks, stairs, slabs) go;
//! * EF3: the ground is scoured to coarse dirt; debarked trunks snap;
//!   masonry (bricks, stone bricks, smooth stone, cobblestone) breaks;
//! * EF4-EF5: trunks gone, walls torn down, holes one block deep.
//!
//! Columns hit again and again as a tornado passes lose a little more each
//! time, which gives the path-shaped, progressive destruction.

use super::rng::Rng;

/// Wood types with logs and stripped logs.
pub const SPECIES: [&str; 9] = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"];

/// Blocks the edits place, by index. Stripped logs follow the fixed ones.
pub const PALETTE: [&str; 14] = [
    "minecraft:air",
    "minecraft:dirt",
    "minecraft:coarse_dirt",
    "minecraft:snow[layers=1]",
    "minecraft:ice",
    "minecraft:stripped_oak_log[axis=y]",
    "minecraft:stripped_spruce_log[axis=y]",
    "minecraft:stripped_birch_log[axis=y]",
    "minecraft:stripped_jungle_log[axis=y]",
    "minecraft:stripped_acacia_log[axis=y]",
    "minecraft:stripped_dark_oak_log[axis=y]",
    "minecraft:stripped_mangrove_log[axis=y]",
    "minecraft:stripped_cherry_log[axis=y]",
    "minecraft:stripped_pale_oak_log[axis=y]",
];
pub const AIR: u8 = 0;
pub const DIRT: u8 = 1;
pub const COARSE_DIRT: u8 = 2;
pub const SNOW_LAYER: u8 = 3;
pub const ICE: u8 = 4;
pub const STRIPPED: u8 = 5;

/// Block predicates the edits filter on, by index. Species logs follow.
pub const FILTERS: [&str; 34] = [
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
    "minecraft:snow",
    "minecraft:smooth_stone",
    "#minecraft:dirt",
    "minecraft:oak_log",
    "minecraft:spruce_log",
    "minecraft:birch_log",
    "minecraft:jungle_log",
    "minecraft:acacia_log",
    "minecraft:dark_oak_log",
    "minecraft:mangrove_log",
    "minecraft:cherry_log",
    "minecraft:pale_oak_log",
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
pub const F_SNOW: u8 = 22;
pub const F_SMOOTH_STONE: u8 = 23;
pub const F_ANY_DIRT: u8 = 24;
pub const F_SPECIES_LOG: u8 = 25;

/// Masonry that breaks from EF3 up, and flies off as debris.
pub const MASONRY: [u8; 4] = [F_BRICKS, F_STONE_BRICKS, F_SMOOTH_STONE, F_COBBLE];

/// The single block a filter names, when it names one (for debris).
pub fn single_block(filter: u8) -> Option<&'static str> {
    let f = FILTERS[filter as usize];
    (!f.starts_with('#') && filter != F_AIR && filter != F_WATER).then_some(f)
}

/// Which surface a run counts from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Surface {
    /// Top block that blocks motion (leaves included).
    Motion,
    /// Top non-air block (plants and snow layers included).
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

fn run(out: &mut Vec<Run>, filter: u8, block: u8, depth: u32, surface: Surface) {
    out.push(Run { dx: 0, dz: 0, depth: -(depth as i32), height: depth, surface, block, filter });
}

fn strip(out: &mut Vec<Run>, filter: u8, depth: u32) {
    run(out, filter, AIR, depth, Surface::Motion);
}

/// Plans damage to one column hit by a wind of `speed` m/s. `species` are
/// indexes in [`SPECIES`] of the wood types to debark (all of them when the
/// caller can afford it). Appends to `out`.
pub fn plan_column(speed: f64, rng: &mut Rng, species: &[u8], out: &mut Vec<Run>) {
    if speed < 29.0 {
        return;
    }
    // Partial damage near the thresholds.
    let p = ((speed - 27.0) / 20.0).clamp(0.2, 1.0);
    if !rng.chance(p) {
        return;
    }
    // EF0: plants and snow cover blown away, crowns thinned.
    for f in [F_SNOW, F_FLOWERS, F_SHORT_GRASS, F_CROPS] {
        run(out, f, AIR, 1, Surface::World);
    }
    let leaves = match speed {
        s if s >= 61.0 => 32,
        s if s >= 50.0 => 16,
        s if s >= 38.0 => 8,
        _ => 3,
    };
    strip(out, F_LEAVES, leaves);
    if speed >= 38.0 {
        // EF1: windows, doors, fences, wool.
        for f in [F_GLASS, F_PANE, F_DOORS, F_TRAPDOORS, F_FENCES] {
            strip(out, f, 3);
        }
        strip(out, F_WOOL, 2);
    }
    if speed >= 50.0 {
        // EF2: debarked trees, grass scoured to dirt, roofs off.
        for &s in species {
            if (s as usize) < SPECIES.len() {
                run(out, F_SPECIES_LOG + s, STRIPPED + s, 20, Surface::Motion);
            }
        }
        if speed < 61.0 {
            run(out, F_GRASS_BLOCK, DIRT, 1, Surface::Motion);
        }
        let wood = match speed {
            s if s >= 74.0 => 10,
            s if s >= 61.0 => 5,
            _ => 2,
        };
        for f in [F_PLANKS, F_STAIRS, F_SLABS] {
            strip(out, f, wood);
        }
        strip(out, F_BEDS, 2);
    }
    if speed >= 61.0 {
        // EF3: ground scoured to coarse dirt, trunks snap, masonry breaks.
        run(out, F_GRASS_BLOCK, COARSE_DIRT, 1, Surface::Motion);
        run(out, F_DIRT, COARSE_DIRT, 1, Surface::Motion);
        strip(out, F_LOGS, if speed >= 74.0 { 14 } else { 4 });
        let wall = match speed {
            s if s >= 89.0 => 6,
            s if s >= 74.0 => 3,
            _ => 1,
        };
        for f in MASONRY {
            strip(out, f, wall);
        }
    }
    if speed >= 74.0 {
        // EF4+: holes one block deep in the scoured ground.
        let p = if speed >= 89.0 { 0.6 } else { 0.3 };
        if rng.chance(p) {
            strip(out, F_ANY_DIRT, 1);
        }
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

/// Every species, for callers that can afford one run each.
pub fn all_species() -> Vec<u8> {
    (0..SPECIES.len() as u8).collect()
}

/// The wood type of a block name (`minecraft:spruce_leaves` → spruce).
pub fn species_of(block: &str) -> Option<u8> {
    let name = block.strip_prefix("minecraft:").unwrap_or(block);
    let name = name.strip_prefix("stripped_").unwrap_or(name);
    // Longest names first: `dark_oak` and `pale_oak` before `oak`.
    let mut order: Vec<usize> = (0..SPECIES.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(SPECIES[i].len()));
    order.into_iter().find(|&i| {
        let s = SPECIES[i];
        name.strip_prefix(s).is_some_and(|rest| ["_log", "_wood", "_leaves", "_planks", "_sapling"].iter().any(|k| rest.starts_with(k)))
    }).map(|i| i as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan(speed: f64) -> Vec<Run> {
        let mut rng = Rng::new(1);
        let mut out = Vec::new();
        for _ in 0..40 {
            plan_column(speed, &mut rng, &all_species(), &mut out);
        }
        out
    }

    #[test]
    fn the_scale_does_what_it_says() {
        assert!(plan(20.0).is_empty());
        let ef0 = plan(32.0);
        assert!(ef0.iter().any(|r| r.filter == F_LEAVES) && ef0.iter().any(|r| r.filter == F_SNOW));
        assert!(!ef0.iter().any(|r| r.filter >= F_SPECIES_LOG || r.filter == F_GLASS));
        let ef1 = plan(42.0);
        assert!(ef1.iter().any(|r| r.filter == F_GLASS));
        let ef2 = plan(55.0);
        let spruce = F_SPECIES_LOG + 1;
        assert!(ef2.iter().any(|r| r.filter == spruce && r.block == STRIPPED + 1), "debarked");
        assert!(ef2.iter().any(|r| r.filter == F_GRASS_BLOCK && r.block == DIRT));
        assert!(!ef2.iter().any(|r| MASONRY.contains(&r.filter)));
        let ef3 = plan(65.0);
        assert!(ef3.iter().any(|r| r.filter == F_GRASS_BLOCK && r.block == COARSE_DIRT), "coarse dirt");
        assert!(ef3.iter().any(|r| r.filter == F_BRICKS));
        assert!(!ef3.iter().any(|r| r.filter == F_ANY_DIRT));
        let ef5 = plan(95.0);
        let holes: Vec<_> = ef5.iter().filter(|r| r.filter == F_ANY_DIRT).collect();
        assert!(!holes.is_empty() && holes.iter().all(|r| r.height == 1 && r.depth == -1), "holes one deep");
        for r in ef5.iter().chain(&ef0) {
            assert!((r.filter as usize) < FILTERS.len() && (r.block as usize) < PALETTE.len());
            assert_eq!(r.depth + r.height as i32, 0, "runs end at the surface");
        }
        for (i, s) in SPECIES.iter().enumerate() {
            assert!(FILTERS[F_SPECIES_LOG as usize + i].contains(&format!(":{s}_log")));
            assert!(PALETTE[STRIPPED as usize + i].contains(&format!("stripped_{s}_log")));
        }
    }

    #[test]
    fn species_from_names() {
        assert_eq!(species_of("minecraft:spruce_leaves"), Some(1));
        assert_eq!(species_of("minecraft:dark_oak_log"), Some(5));
        assert_eq!(species_of("minecraft:stripped_pale_oak_log"), Some(8));
        assert_eq!(species_of("minecraft:oak_planks"), Some(0));
        assert_eq!(species_of("minecraft:stone"), None);
        assert_eq!(single_block(F_BRICKS), Some("minecraft:bricks"));
        assert_eq!(single_block(F_LEAVES), None);
    }
}
