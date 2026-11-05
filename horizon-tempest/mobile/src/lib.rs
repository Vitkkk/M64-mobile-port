//! Tempest for Horizon's Android ports: the same weather as the native mod
//! (`../api/src/sim`), as a sandboxed WebAssembly client mod. Without the
//! native hooks it draws the radar on the HUD and shows storms with
//! particles, and acts on the world with vanilla commands run as the
//! player (so world effects need cheats).

#[path = "../../api/src/sim/mod.rs"]
pub mod sim;

pub mod cfg;
pub mod cmds;
pub mod game;

wit_bindgen::generate!({
    path: "wit",
    world: "tempest",
});

use game::{Io, Player, Shape, Tempest};
use horizon::mods::{chat, command, game as hgame, render, storage};
use std::cell::RefCell;

struct Real;

fn shape(s: &Shape) -> render::Shape {
    match s {
        Shape::Fill { x, y, w, h, argb } => render::Shape::Fill(render::FillShape {
            rect: render::Rect { x: *x, y: *y, w: *w, h: *h },
            color: *argb,
        }),
        Shape::Text { x, y, text, rgb, centered } => render::Shape::Text(render::TextShape {
            text: text.clone(),
            at: render::Point { x: *x, y: *y },
            color: *rgb,
            centered: *centered,
            shadow: true,
        }),
    }
}

impl Io for Real {
    fn player(&self) -> Option<Player> {
        hgame::get_player().map(|p| Player { pos: [p.x, p.y, p.z], dimension: p.dimension })
    }
    fn clock(&self) -> Option<(u64, u64)> {
        hgame::get_clock().map(|c| (c.game_time, c.day_time))
    }
    fn run(&mut self, lines: &[String]) {
        command::run_quiet(lines);
    }
    fn run_loud(&mut self, lines: &[String]) {
        for line in lines.iter().take(8) {
            command::run(line);
        }
    }
    fn set_corner(&mut self, w: f32, h: f32, shapes: &[Shape]) {
        let shapes: Vec<render::Shape> = shapes.iter().map(shape).collect();
        render::set_corner(render::Corner::TopRight, w, h, &shapes);
    }
    fn clear_corner(&mut self) {
        render::clear_corner();
    }
    fn set_hud(&mut self, shapes: &[Shape]) {
        let shapes: Vec<render::Shape> = shapes.iter().map(shape).collect();
        render::set_hud(&shapes);
    }
    fn gui_size(&self) -> (f32, f32) {
        render::gui_size()
    }
    fn shake(&mut self, strength: f32, seconds: f32) {
        render::shake(strength, seconds);
    }
    fn load(&self, key: &str) -> Option<Vec<u8>> {
        storage::get(key)
    }
    fn save(&mut self, key: &str, value: &[u8]) {
        let _ = storage::set(key, value);
    }
    fn chat(&mut self, text: &str) {
        chat::show(text);
    }
    fn look(&self) -> Option<String> {
        hgame::raycast(48.0).map(|h| h.block)
    }
}

thread_local! {
    static STATE: RefCell<Option<Tempest>> = const { RefCell::new(None) };
}

struct Export;

impl exports::horizon::mods::events::Guest for Export {
    fn init() -> Result<exports::horizon::mods::events::Registration, String> {
        STATE.with(|s| *s.borrow_mut() = Some(Tempest::new(&Real)));
        Ok(exports::horizon::mods::events::Registration {
            commands: vec!["tempest".into()],
            tick: true,
            frame: false,
            chat: false,
        })
    }
    fn tick(_tick: u64) {
        STATE.with(|s| {
            if let Some(m) = s.borrow_mut().as_mut() {
                m.tick(&mut Real);
            }
        });
    }
    fn frame(_seconds: f32) {}
    fn command(_name: String, args: String) -> Result<Option<String>, String> {
        STATE.with(|s| match s.borrow_mut().as_mut() {
            Some(m) => m.command(&mut Real, &args),
            None => Err("Tempest is not running".into()),
        })
    }
    fn chat(_text: String) {}
}

#[cfg(target_arch = "wasm32")]
export!(Export);
