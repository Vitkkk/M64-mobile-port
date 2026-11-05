//! Settings, kept in the mod's storage as `key = value` lines.

use crate::sim::config::Quality;

#[derive(Clone, Debug, PartialEq)]
pub struct Cfg {
    pub quality: Quality,
    pub radar: bool,
    pub velocity: bool,
    pub info: bool,
    pub radar_range: f64,
    pub particles: bool,
    pub sounds: bool,
    pub sirens: bool,
    pub shake: bool,
    pub damage: bool,
    pub lightning: bool,
    pub vanilla_weather: bool,
    pub snow: bool,
    pub hurt: bool,
    pub entity_push: bool,
    pub storm_rate: f64,
    pub tornado_rate: f64,
    pub seed: u64,
    pub year_days: f64,
}

impl Default for Cfg {
    fn default() -> Self {
        Cfg {
            quality: Quality::Low,
            radar: true,
            velocity: false,
            info: true,
            radar_range: 3000.0,
            particles: true,
            sounds: true,
            sirens: true,
            shake: true,
            damage: true,
            lightning: true,
            vanilla_weather: true,
            snow: true,
            hurt: true,
            entity_push: true,
            storm_rate: 1.0,
            tornado_rate: 1.0,
            seed: crate::sim::config::DEFAULT_SEED,
            year_days: 48.0,
        }
    }
}

fn boolean(v: &str) -> Option<bool> {
    match v.trim().to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" | "sim" | "ligado" => Some(true),
        "false" | "off" | "no" | "0" | "nao" | "não" | "desligado" => Some(false),
        _ => None,
    }
}

impl Cfg {
    pub fn parse(text: &str) -> Self {
        let mut c = Cfg::default();
        for line in text.lines() {
            if let Some((k, v)) = line.split('#').next().unwrap_or("").split_once('=') {
                c.set(k.trim(), v.trim());
            }
        }
        c
    }

    pub fn set(&mut self, k: &str, v: &str) -> bool {
        let b = boolean(v);
        let f = v.parse::<f64>().ok().filter(|x| x.is_finite());
        let flag = |slot: &mut bool| b.map(|b| *slot = b).is_some();
        match k {
            "quality" => Quality::parse(v).map(|q| self.quality = q).is_some(),
            "radar" => flag(&mut self.radar),
            "velocity" => flag(&mut self.velocity),
            "info" => flag(&mut self.info),
            "radar_range" => f.filter(|r| (500.0..=20_000.0).contains(r)).map(|r| self.radar_range = r).is_some(),
            "particles" => flag(&mut self.particles),
            "sounds" => flag(&mut self.sounds),
            "sirens" => flag(&mut self.sirens),
            "shake" => flag(&mut self.shake),
            "damage" => flag(&mut self.damage),
            "lightning" => flag(&mut self.lightning),
            "vanilla_weather" => flag(&mut self.vanilla_weather),
            "snow" => flag(&mut self.snow),
            "hurt" => flag(&mut self.hurt),
            "entity_push" => flag(&mut self.entity_push),
            "storm_rate" => f.filter(|r| (0.0..=20.0).contains(r)).map(|r| self.storm_rate = r).is_some(),
            "tornado_rate" => f.filter(|r| (0.0..=10.0).contains(r)).map(|r| self.tornado_rate = r).is_some(),
            "seed" => v.parse().ok().map(|s| self.seed = s).is_some(),
            "year_days" => f.filter(|d| *d >= 4.0).map(|d| self.year_days = d).is_some(),
            _ => false,
        }
    }

    pub fn to_text(&self) -> String {
        format!(
            "quality = {}\nradar = {}\nvelocity = {}\ninfo = {}\nradar_range = {}\nparticles = {}\nsounds = {}\n\
             sirens = {}\nshake = {}\ndamage = {}\nlightning = {}\nvanilla_weather = {}\nsnow = {}\nhurt = {}\n\
             entity_push = {}\nstorm_rate = {}\ntornado_rate = {}\nseed = {}\nyear_days = {}\n",
            self.quality.name(), self.radar, self.velocity, self.info, self.radar_range, self.particles, self.sounds,
            self.sirens, self.shake, self.damage, self.lightning, self.vanilla_weather, self.snow, self.hurt,
            self.entity_push, self.storm_rate, self.tornado_rate, self.seed, self.year_days
        )
    }

    /// Commands Tempest may run in one tick (the game runs at most 64).
    pub fn command_budget(&self) -> usize {
        match self.quality {
            Quality::Low => 26,
            Quality::Medium => 40,
            Quality::High => 60,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let mut c = Cfg::default();
        assert!(c.set("damage", "off"));
        assert!(c.set("quality", "alta"));
        assert!(!c.set("radar_range", "1"));
        assert_eq!(Cfg::parse(&c.to_text()), c);
        assert!(Cfg::default().command_budget() < 64);
    }
}
