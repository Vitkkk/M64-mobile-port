//! Settings, kept as simple `key = value` text files.

/// Graphics quality. Low is made for phones.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quality {
    Low,
    Medium,
    High,
}

impl Quality {
    pub fn parse(s: &str) -> Option<Self> {
        Some(match s.trim().to_ascii_lowercase().as_str() {
            "low" | "baixa" | "mobile" | "celular" => Quality::Low,
            "medium" | "media" | "média" => Quality::Medium,
            "high" | "alta" => Quality::High,
            _ => return None,
        })
    }
    pub fn name(self) -> &'static str {
        match self {
            Quality::Low => "low",
            Quality::Medium => "medium",
            Quality::High => "high",
        }
    }
    pub fn budget(self) -> Budget {
        match self {
            Quality::Low => Budget { boxes: 180, radar_cells: 32, draw_distance: 3000.0, radar_refresh: 40, debris: 12, cloud_detail: 0 },
            Quality::Medium => Budget { boxes: 600, radar_cells: 48, draw_distance: 6000.0, radar_refresh: 20, debris: 40, cloud_detail: 1 },
            Quality::High => Budget { boxes: 1600, radar_cells: 64, draw_distance: 12000.0, radar_refresh: 10, debris: 120, cloud_detail: 2 },
        }
    }
}

/// How much work the client may do.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Budget {
    /// Boxes drawn for clouds, rain shafts, funnels and debris each frame.
    pub boxes: usize,
    /// Radar image side, in cells.
    pub radar_cells: usize,
    /// Storms farther than this are not drawn.
    pub draw_distance: f64,
    /// Ticks between radar sweeps.
    pub radar_refresh: u32,
    /// Debris pieces around a tornado.
    pub debris: usize,
    /// 0..2: how finely clouds are cut.
    pub cloud_detail: u8,
}

fn parse_bool(v: &str) -> Option<bool> {
    match v.trim().to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" | "sim" => Some(true),
        "false" | "off" | "no" | "0" | "nao" | "não" => Some(false),
        _ => None,
    }
}

fn lines(text: &str) -> impl Iterator<Item = (String, String)> + '_ {
    text.lines().filter_map(|l| {
        let l = l.split('#').next()?.trim();
        let (k, v) = l.split_once('=')?;
        Some((k.trim().to_ascii_lowercase(), v.trim().to_owned()))
    })
}

/// Client settings (`tempest-client.cfg` in the game's config folder).
#[derive(Clone, Debug, PartialEq)]
pub struct ClientConfig {
    pub quality: Quality,
    pub radar: bool,
    pub info: bool,
    pub velocity: bool,
    pub radar_range: f64,
    pub clouds: bool,
    pub fog: bool,
    pub sounds: bool,
    pub sirens: bool,
    pub shake: bool,
    pub push: bool,
    /// Make local storms (visual only) when the server sends none.
    pub fallback: bool,
    pub seed: u64,
    pub year_days: f64,
}

impl Default for ClientConfig {
    fn default() -> Self {
        ClientConfig {
            quality: Quality::Low,
            radar: true,
            info: true,
            velocity: false,
            radar_range: 3000.0,
            clouds: true,
            fog: true,
            sounds: true,
            sirens: true,
            shake: true,
            push: true,
            fallback: true,
            seed: DEFAULT_SEED,
            year_days: 48.0,
        }
    }
}

pub const DEFAULT_SEED: u64 = 0x7E57_57AA_2026;

impl ClientConfig {
    pub fn parse(text: &str) -> Self {
        let mut c = ClientConfig::default();
        for (k, v) in lines(text) {
            c.set(&k, &v);
        }
        c
    }
    /// Sets one value; false when the key or value is not understood.
    pub fn set(&mut self, k: &str, v: &str) -> bool {
        let b = parse_bool(v);
        match k {
            "quality" => Quality::parse(v).map(|q| self.quality = q).is_some(),
            "radar" => b.map(|b| self.radar = b).is_some(),
            "info" => b.map(|b| self.info = b).is_some(),
            "velocity" => b.map(|b| self.velocity = b).is_some(),
            "radar_range" => v.parse::<f64>().ok().filter(|r| (500.0..=20_000.0).contains(r)).map(|r| self.radar_range = r).is_some(),
            "clouds" => b.map(|b| self.clouds = b).is_some(),
            "fog" => b.map(|b| self.fog = b).is_some(),
            "sounds" => b.map(|b| self.sounds = b).is_some(),
            "sirens" => b.map(|b| self.sirens = b).is_some(),
            "shake" => b.map(|b| self.shake = b).is_some(),
            "push" => b.map(|b| self.push = b).is_some(),
            "fallback" => b.map(|b| self.fallback = b).is_some(),
            "seed" => v.parse().ok().map(|s| self.seed = s).is_some(),
            "year_days" => v.parse::<f64>().ok().filter(|d| *d >= 4.0).map(|d| self.year_days = d).is_some(),
            _ => false,
        }
    }
    pub fn to_text(&self) -> String {
        format!(
            "# Tempest (client). quality: low (phones) | medium | high\n\
             quality = {}\nradar = {}\ninfo = {}\nvelocity = {}\nradar_range = {}\nclouds = {}\nfog = {}\n\
             sounds = {}\nsirens = {}\nshake = {}\npush = {}\n\
             # Local, visual-only storms when the server has no Tempest\nfallback = {}\nseed = {}\nyear_days = {}\n",
            self.quality.name(), self.radar, self.info, self.velocity, self.radar_range, self.clouds, self.fog,
            self.sounds, self.sirens, self.shake, self.push, self.fallback, self.seed, self.year_days
        )
    }
}

/// Server settings (`tempest/server.cfg` in the world folder).
#[derive(Clone, Debug, PartialEq)]
pub struct ServerConfig {
    pub seed: u64,
    pub year_days: f64,
    /// Multiplies how often storms are born.
    pub storm_rate: f64,
    pub tornado_rate: f64,
    pub natural: [bool; 5],
    /// Wind damage to blocks.
    pub damage: bool,
    /// Columns damaged per second at most, for all storms together.
    pub damage_budget: u32,
    pub hurricane_damage: bool,
    pub lightning: bool,
    pub vanilla_weather: bool,
    pub snow: bool,
    pub player_damage: bool,
    pub entity_push: bool,
    /// Storm list sent to each player this often, ticks.
    pub sync_interval: u32,
    /// Storms farther than this from every player are not sent.
    pub sync_radius: f64,
}

impl Default for ServerConfig {
    fn default() -> Self {
        ServerConfig {
            seed: DEFAULT_SEED,
            year_days: 48.0,
            storm_rate: 1.0,
            tornado_rate: 1.0,
            natural: [true; 5],
            damage: true,
            damage_budget: 120,
            hurricane_damage: true,
            lightning: true,
            vanilla_weather: true,
            snow: true,
            player_damage: true,
            entity_push: true,
            sync_interval: 100,
            sync_radius: 12_000.0,
        }
    }
}

const KIND_KEYS: [&str; 5] = ["natural_cells", "natural_supercells", "natural_squalls", "natural_hurricanes", "natural_blizzards"];

impl ServerConfig {
    pub fn parse(text: &str) -> Self {
        let mut c = ServerConfig::default();
        for (k, v) in lines(text) {
            c.set(&k, &v);
        }
        c
    }
    pub fn set(&mut self, k: &str, v: &str) -> bool {
        let b = parse_bool(v);
        let f = v.parse::<f64>().ok().filter(|x| x.is_finite());
        if let Some(i) = KIND_KEYS.iter().position(|kk| *kk == k) {
            return b.map(|b| self.natural[i] = b).is_some();
        }
        match k {
            "seed" => v.parse().ok().map(|s| self.seed = s).is_some(),
            "year_days" => f.filter(|d| *d >= 4.0).map(|d| self.year_days = d).is_some(),
            "storm_rate" => f.filter(|r| (0.0..=20.0).contains(r)).map(|r| self.storm_rate = r).is_some(),
            "tornado_rate" => f.filter(|r| (0.0..=10.0).contains(r)).map(|r| self.tornado_rate = r).is_some(),
            "damage" => b.map(|b| self.damage = b).is_some(),
            "damage_budget" => v.parse::<u32>().ok().map(|n| self.damage_budget = n.min(5000)).is_some(),
            "hurricane_damage" => b.map(|b| self.hurricane_damage = b).is_some(),
            "lightning" => b.map(|b| self.lightning = b).is_some(),
            "vanilla_weather" => b.map(|b| self.vanilla_weather = b).is_some(),
            "snow" => b.map(|b| self.snow = b).is_some(),
            "player_damage" => b.map(|b| self.player_damage = b).is_some(),
            "entity_push" => b.map(|b| self.entity_push = b).is_some(),
            "sync_interval" => v.parse::<u32>().ok().map(|n| self.sync_interval = n.clamp(20, 1200)).is_some(),
            "sync_radius" => f.filter(|r| *r >= 1000.0).map(|r| self.sync_radius = r).is_some(),
            _ => false,
        }
    }
    pub fn to_text(&self) -> String {
        let mut s = format!(
            "# Tempest (server)\nseed = {}\nyear_days = {}\nstorm_rate = {}\ntornado_rate = {}\n\
             damage = {}\n# columns per second; lower it on weak devices\ndamage_budget = {}\nhurricane_damage = {}\n\
             lightning = {}\n# switches vanilla rain/thunder to match the storms\nvanilla_weather = {}\nsnow = {}\n\
             player_damage = {}\nentity_push = {}\nsync_interval = {}\nsync_radius = {}\n",
            self.seed, self.year_days, self.storm_rate, self.tornado_rate, self.damage, self.damage_budget,
            self.hurricane_damage, self.lightning, self.vanilla_weather, self.snow, self.player_damage,
            self.entity_push, self.sync_interval, self.sync_radius
        );
        for (i, k) in KIND_KEYS.iter().enumerate() {
            s.push_str(&format!("{k} = {}\n", self.natural[i]));
        }
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let mut c = ClientConfig::default();
        assert!(c.set("quality", "high"));
        assert!(c.set("radar", "off"));
        assert!(!c.set("radar_range", "5"));
        assert_eq!(ClientConfig::parse(&c.to_text()), c);
        let mut s = ServerConfig::default();
        assert!(s.set("storm_rate", "2.5"));
        assert!(s.set("natural_hurricanes", "false"));
        assert!(!s.set("nope", "1"));
        assert_eq!(ServerConfig::parse(&s.to_text()), s);
        assert_eq!(ClientConfig::parse("garbage\n= =\n").quality, Quality::Low);
    }
}
