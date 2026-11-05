//! `tempest-fuelcheck <mod.wasm> [quality] [command]`: worst fuel of a tick.
use std::collections::HashMap;
use wasmtime::component::{Component, HasSelf, Linker};
use wasmtime::{Config, Engine, Store};

wasmtime::component::bindgen!({ path: "../../wit", world: "tempest" });

use horizon::mods::{chat, command, game, hud, log, render, storage};

const FUEL_TICK: u64 = 50_000_000;

#[derive(Default)]
struct Host {
    t: u64,
    store: HashMap<String, Vec<u8>>,
    commands: usize,
}

impl log::Host for Host {
    fn write(&mut self, _level: log::Level, message: String) {
        println!("[mod log] {message}");
    }
}
impl game::Host for Host {
    fn get_player(&mut self) -> Option<game::Player> {
        Some(game::Player { x: 0.0, y: 70.0, z: 0.0, yaw: 0.0, pitch: 0.0, health: 20.0, food: 20, dimension: "minecraft:overworld".into() })
    }
    fn get_clock(&mut self) -> Option<game::Clock> {
        Some(game::Clock { game_time: self.t, day_time: 6000 })
    }
    fn raycast(&mut self, _distance: f32) -> Option<game::Hit> {
        Some(game::Hit { block_x: 0, block_y: 0, block_z: 0, x: 0.0, y: 0.0, z: 0.0, block: "minecraft:spruce_log".into() })
    }
}
impl chat::Host for Host {
    fn show(&mut self, text: String) {
        println!("[chat] {text}");
    }
}
impl hud::Host for Host {
    fn set_panel(&mut self, _corner: hud::Corner, _lines: Vec<hud::Line>) {}
    fn clear(&mut self) {}
}
impl storage::Host for Host {
    fn get(&mut self, key: String) -> Option<Vec<u8>> {
        self.store.get(&key).cloned()
    }
    fn set(&mut self, key: String, value: Vec<u8>) -> Result<(), String> {
        self.store.insert(key, value);
        Ok(())
    }
    fn delete(&mut self, key: String) {
        self.store.remove(&key);
    }
}
impl command::Host for Host {
    fn run(&mut self, _line: String) {
        self.commands += 1;
    }
    fn run_quiet(&mut self, lines: Vec<String>) {
        self.commands += lines.len();
    }
}
impl render::Host for Host {
    fn set_hud(&mut self, _shapes: Vec<render::Shape>) {}
    fn gui_size(&mut self) -> (f32, f32) {
        (427.0, 240.0)
    }
    fn shake(&mut self, _strength: f32, _seconds: f32) {}
    fn set_corner(&mut self, _corner: render::Corner, _width: f32, _height: f32, shapes: Vec<render::Shape>) {
        assert!(shapes.len() <= 1024, "too many shapes");
    }
    fn clear_corner(&mut self) {}
    fn text_width(&mut self, text: String) -> f32 {
        text.len() as f32 * 6.0
    }
    fn frame_stats(&mut self) -> render::FrameRate {
        render::FrameRate { fps: 60, min: 60, low: 60 }
    }
    fn set_zoom(&mut self, _zoom: render::Zoom) {}
    fn set_minimap(&mut self, _minimap: Option<render::Minimap>) {}
    fn set_far_terrain(&mut self, _far: Option<render::FarTerrain>) {}
}

fn main() -> wasmtime::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let path = args.get(1).expect("usage: tempest-fuelcheck <mod.wasm> [quality] [command]");
    let quality = args.get(2).map(String::as_str).unwrap_or("low");
    let spawn = args.get(3).map(String::as_str).unwrap_or("here 5");
    let mut config = Config::new();
    config.wasm_component_model(true).consume_fuel(true);
    let engine = Engine::new(&config)?;
    let component = Component::from_file(&engine, path)?;
    let mut linker = Linker::<Host>::new(&engine);
    Tempest::add_to_linker::<_, HasSelf<_>>(&mut linker, |h| h)?;
    let mut store = Store::new(&engine, Host { t: 400_000, ..Host::default() });
    store.set_fuel(2_000_000_000)?;
    let m = Tempest::instantiate(&mut store, &component, &linker)?;
    let ev = m.horizon_mods_events();
    ev.call_init(&mut store)?.map_err(wasmtime::Error::msg)?;
    let call = |store: &mut Store<Host>, f: &dyn Fn(&mut Store<Host>) -> wasmtime::Result<()>| -> wasmtime::Result<u64> {
        store.set_fuel(FUEL_TICK)?;
        f(store)?;
        Ok(FUEL_TICK - store.get_fuel()?)
    };
    for i in 0..5 {
        store.data_mut().t += 1;
        call(&mut store, &|s| ev.call_tick(&mut *s, i))?;
    }
    let rate = args.get(4).map(String::as_str).unwrap_or("0");
    for cmd in [format!("set storm_rate {rate}"), format!("quality {quality}"), "spawn supercell".to_string(), "spawn squall".to_string(), spawn.to_string()] {
        let used = call(&mut store, &|s| {
            let r = ev.call_command(&mut *s, "tempest", &cmd)?;
            println!("/tempest {cmd} -> {r:?}");
            Ok(())
        })?;
        println!("  fuel {used}");
    }
    let (mut worst, mut worst_tick, mut total) = (0u64, 0u64, 0u64);
    let ticks = 2400u64;
    for i in 0..ticks {
        store.data_mut().t += 1;
        let used = call(&mut store, &|s| ev.call_tick(&mut *s, i))?;
        total += used;
        if used > worst {
            worst = used;
            worst_tick = i;
        }
    }
    let velocity = call(&mut store, &|s| ev.call_command(&mut *s, "tempest", "vel").map(|_| ()))?;
    let mut worst_vel = 0;
    for i in 0..200 {
        store.data_mut().t += 1;
        worst_vel = worst_vel.max(call(&mut store, &|s| ev.call_tick(&mut *s, ticks + i))?);
    }
    println!(
        "quality {quality}: worst tick {worst} fuel ({:.0}% of {FUEL_TICK}) at tick {worst_tick}; average {}; velocity radar worst {worst_vel} ({velocity}); commands {}",
        100.0 * worst as f64 / FUEL_TICK as f64,
        total / ticks,
        store.data().commands
    );
    Ok(())
}
