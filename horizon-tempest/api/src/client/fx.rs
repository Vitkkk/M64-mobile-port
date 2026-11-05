//! Fog, sky colour, lightning flashes, camera shake and shader-pack values.

use super::ClientState;
use crate::sim::precip::Precip;
use horizon_client::mod_api::{FogData, NativeShake};

/// Storm-darkened, rain-greyed, hail-greened fog colour.
pub fn fog_color(c: &ClientState, rgb: &mut [f32; 3]) {
    if !c.active || !c.cfg.fog {
        return;
    }
    let target = match c.here.precip {
        Precip::Hail => [0.20, 0.30, 0.25],
        Precip::Snow | Precip::Sleet => [0.70, 0.72, 0.76],
        _ => [0.20, 0.22, 0.26],
    };
    let k = c.dark.clamp(0.0, 0.92);
    for i in 0..3 {
        rgb[i] += (target[i] - rgb[i]) * k;
        rgb[i] = (rgb[i] + c.flash * 0.8).clamp(0.0, 1.0);
    }
}

/// Shorter sight in heavy rain, whiteouts in blizzards, murk near a tornado.
pub fn fog(c: &ClientState, fog: &mut FogData) {
    if !c.active || !c.cfg.fog || c.vis >= 0.999 {
        return;
    }
    let f = c.vis.clamp(0.08, 1.0);
    fog.environmental_start *= f;
    fog.environmental_end *= f;
    fog.render_distance_start *= f;
    fog.render_distance_end = (fog.render_distance_end * f).max(24.0);
}

pub fn shake(c: &ClientState, shake: &mut NativeShake) {
    if !c.active || !c.cfg.shake || c.shake_amp <= 0.001 {
        return;
    }
    let s = c.seconds();
    let a = c.shake_amp.min(2.5);
    shake.pitch += a * (s * 13.0).sin() * 0.8;
    shake.yaw += a * (s * 17.3).sin() * 0.6;
    shake.roll += a * (s * 7.1).sin() * 0.5;
}

/// Eight vec4s for a shader pack (`frame.mods`), documented in the README.
pub fn shader_params(c: &ClientState, params: &mut [[f32; 4]; 8]) {
    if !c.active {
        return;
    }
    if let Some((_, tor)) = c.nearest_tornado() {
        let d = (tor.pos[0] - c.cam[0]).hypot(tor.pos[1] - c.cam[2]);
        params[0] = [(tor.pos[0] - c.cam[0]) as f32, 0.0, (tor.pos[1] - c.cam[2]) as f32, tor.radius as f32];
        params[1] = [tor.vmax as f32, tor.ef_now as f32, tor.life_frac as f32, d as f32];
    }
    params[2] = [c.here.dbz as f32, c.dark, c.here.speed() as f32, c.flash];
    params[3] = [c.here.wind[0] as f32, c.here.wind[1] as f32, c.here.up as f32, c.here.precip as u8 as f32];
}
