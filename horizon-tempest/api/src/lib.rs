//! Tempest: storms, tornadoes, squall lines, hurricanes, blizzards and a
//! Doppler radar for Horizon (the Rust rewrite of Minecraft: Java Edition).
//!
//! A clean-room reimplementation, written from scratch, of the kind of
//! gameplay ProtoManly's Weather offers on NeoForge, made to be light enough
//! for weak devices:
//!
//! * the weather is a set of closed-form functions (`sim`), evaluated only
//!   where and when needed, never a grid stepped every tick;
//! * storms are born from hashes, so clients agree with the server with
//!   almost no network traffic (a small list every few seconds);
//! * clouds and tornadoes are a budgeted number of boxes, fog and colour,
//!   with Low/Medium/High presets (Low is the default, for phones);
//! * wind damage is a few bulk block edits per second, with a budget.
//!
//! `hooks()` is what the Horizon launcher calls (docs/native-mods.md, "Trait mods").

// Written for older stable compilers too: no let-chains, no `is_multiple_of`.
#![allow(clippy::collapsible_if, clippy::manual_is_multiple_of)]

pub mod client;
pub mod server;
pub mod sim;

#[cfg(test)]
mod tests;

use horizon_client::mod_api::*;
use std::sync::{Mutex, TryLockError};

/// The mod object the launcher links into the game.
pub fn hooks() -> Box<dyn Hooks> {
    Box::new(Tempest::default())
}

/// One instance serves the window's game (client) and the server thread's
/// game, so each half keeps its own state behind its own lock.
#[derive(Default)]
pub struct Tempest {
    server: Mutex<server::ServerState>,
    client: Mutex<client::ClientState>,
}

/// Runs `f` on the state unless it is busy (a hook re-entered from inside
/// another of ours): never blocks, never deadlocks, survives a poisoned lock.
fn with<T, R>(m: &Mutex<T>, f: impl FnOnce(&mut T) -> R) -> Option<R> {
    match m.try_lock() {
        Ok(mut g) => Some(f(&mut g)),
        Err(TryLockError::Poisoned(p)) => Some(f(&mut p.into_inner())),
        Err(TryLockError::WouldBlock) => None,
    }
}

impl Hooks for Tempest {
    // ---- server half -------------------------------------------------------

    fn server_tick(&self, host: &mut dyn Host, event: &mut ServerTickEvent) {
        let epoch = event.context.epoch;
        with(&self.server, |s| s.on_server_tick(host, epoch));
    }

    fn level_tick_post(&self, host: &mut dyn Host, event: &mut LevelTickPostEvent) {
        if event.dimension == server::OVERWORLD {
            with(&self.server, |s| s.on_overworld_tick(host));
        }
    }

    fn player_tick(&self, host: &mut dyn Host, _event: &mut PlayerTickEvent) {
        if let Some(p) = host.acting_player() {
            with(&self.server, |s| s.on_player_tick(p.uuid, &p.name, p.position, &p.dimension, p.game_mode));
        }
    }

    fn player_logged_out(&self, _host: &mut dyn Host, event: &mut PlayerLoggedOutEvent) {
        let uuid = event.player;
        with(&self.server, |s| s.forget_player(uuid));
    }

    fn entity_tick_post(&self, host: &mut dyn Host, event: &mut EntityTickPostEvent) {
        if event.dimension != server::OVERWORLD {
            return;
        }
        // Copy what is needed and let go of the lock before touching the world.
        let Some(Some(vortices)) = with(&self.server, |s| s.vortices_for_entities()) else {
            return;
        };
        server::push_entity(host, &vortices, event.entity, event.kind);
    }

    fn register_commands(&self, _host: &mut dyn Host, event: &mut RegisterCommandsEvent) {
        server::register_commands(event.commands);
    }

    fn perform_command(&self, host: &mut dyn Host, event: &mut PerformCommandEvent) -> Flow {
        let (player, command) = (event.player, event.command);
        if command != "tempest" && !command.starts_with("tempest ") {
            return Flow::Continue;
        }
        let position = player.position;
        with(&self.server, |s| s.perform_command(host, position, command));
        Flow::Cancel
    }

    // ---- client half -------------------------------------------------------

    fn tick(&self, host: &mut dyn Host, _event: &mut TickEvent) {
        // Only the window's game has a camera; the server thread's has none.
        if let Some(cam) = host.camera() {
            with(&self.client, |c| c.on_tick(host, &cam));
        }
    }

    fn net_from_server(&self, _host: &mut dyn Host, event: &mut NetFromServerEvent) {
        if event.channel == sim::net::CHANNEL {
            let data = event.data;
            with(&self.client, |c| c.on_snapshot(data));
        }
    }

    fn hud(&self, host: &mut dyn Host, event: &mut HudEvent) {
        let host: &dyn Host = host;
        with(&self.client, |c| client::hud::draw(c, host, event.batch, event.corners));
    }

    fn world_primitives(&self, _host: &dyn Host, event: &mut WorldPrimitivesEvent) {
        let (partial, camera) = (event.partial, event.camera);
        with(&self.client, |c| client::render::draw(c, partial, camera, event.primitives));
    }

    fn camera_shake(&self, _host: &dyn Host, event: &mut CameraShakeEvent) {
        with(&self.client, |c| client::fx::shake(c, event.shake));
    }

    fn compute_fog_color(&self, _host: &dyn Host, event: &mut ComputeFogColorEvent) {
        if event.fog_type == FogType::Atmospheric {
            with(&self.client, |c| client::fx::fog_color(c, event.rgb));
        }
    }

    fn render_fog(&self, _host: &dyn Host, event: &mut RenderFogEvent) {
        if event.fog_type == FogType::Atmospheric {
            with(&self.client, |c| client::fx::fog(c, event.fog));
        }
    }

    fn shader_params(&self, _host: &dyn Host, event: &mut ShaderParamsEvent) {
        with(&self.client, |c| client::fx::shader_params(c, event.params));
    }

    fn client_level_load(&self, _host: &mut dyn Host, _event: &mut ClientLevelLoadEvent) {
        with(&self.client, |c| c.reset_world());
    }

    fn client_level_unload(&self, _host: &mut dyn Host, _event: &mut ClientLevelUnloadEvent) {
        with(&self.client, |c| c.reset_world());
    }

    fn commands(&self, _host: &mut dyn Host, event: &mut CommandsEvent) {
        if !event.commands.contains(&"radar") {
            event.commands.push("radar");
        }
    }

    fn command(&self, host: &mut dyn Host, event: &mut CommandEvent) -> Flow {
        let command = event.command;
        if command != "radar" && !command.starts_with("radar ") {
            return Flow::Continue;
        }
        with(&self.client, |c| c.perform_command(host, command));
        Flow::Cancel
    }
}
