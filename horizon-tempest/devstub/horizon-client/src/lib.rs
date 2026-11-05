//! Stub of `horizon_client::mod_api` (the subset Tempest uses). Signatures
//! follow Horizon's generator: a hook's event holds its arguments, numbers
//! behind `&mut` become plain fields, `&self` hooks get `&dyn Host`, `&mut
//! self` hooks `&mut dyn Host`; cancel hooks return `Flow`.
#![allow(dead_code, unused_variables)]

pub mod mod_api {
    use std::path::PathBuf;

    // ---- types -------------------------------------------------------------

    #[derive(Clone, Debug, Default, PartialEq)]
    pub struct Text(String);
    impl Text {
        pub fn literal(s: impl Into<String>) -> Self { Text(s.into()) }
        pub fn colored(s: impl Into<String>, color: u32) -> Self { Text(s.into()) }
        pub fn with_color(self, color: u32) -> Self { self }
        pub fn plain(&self) -> String { self.0.clone() }
    }

    pub struct HudBatch { _p: () }
    impl HudBatch {
        /// Stub only, for tests.
        #[doc(hidden)]
        pub fn for_tests() -> Self { HudBatch { _p: () } }
    }
    pub struct Corners { width: f32, height: f32, used: [f32; 4] }
    impl Corners {
        pub fn new(width: f32, height: f32, top_right: f32) -> Self { Corners { width, height, used: [0.0, top_right, 0.0, 0.0] } }
        pub fn place(&mut self, corner: Corner, w: f32, h: f32) -> (f32, f32) { (self.width - 2.0 - w, 2.0) }
    }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Corner { TopLeft, TopRight, BottomLeft, BottomRight }

    #[derive(Clone, Debug, Default, PartialEq)]
    pub struct WorldPrimitives { n: usize, boxes: Vec<WorldPrimitive> }
    #[derive(Clone, Debug, PartialEq)]
    pub struct WorldPrimitive { pub through_walls: bool }
    impl WorldPrimitives {
        pub fn filled_box(&mut self, min: [f64; 3], max: [f64; 3], color: u32) -> &mut WorldPrimitive {
            self.n += 1;
            self.boxes.push(WorldPrimitive { through_walls: false });
            self.boxes.last_mut().unwrap()
        }
        pub fn line(&mut self, from: [f64; 3], to: [f64; 3], color: u32, width: f32) -> &mut WorldPrimitive { unimplemented!() }
        pub fn box_outline(&mut self, min: [f64; 3], max: [f64; 3], color: u32, width: f32) -> &mut WorldPrimitive { unimplemented!() }
        pub fn text(&mut self, at: [f64; 3], text: impl Into<String>, color: u32, scale: f32) -> &mut WorldPrimitive { unimplemented!() }
        pub fn len(&self) -> usize { self.n }
    }
    impl WorldPrimitive {
        pub fn through_walls(&mut self) -> &mut Self { self }
        pub fn scale_with_distance(&mut self) -> &mut Self { self }
        pub fn background(&mut self) -> &mut Self { self }
    }

    #[derive(Clone, Copy, Debug, Default)]
    pub struct NativeShake { pub pitch: f32, pub yaw: f32, pub roll: f32, pub offset: [f32; 3] }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum FogType { Atmospheric, Water, Lava, PowderSnow }
    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct FogData {
        pub color: [f32; 4],
        pub environmental_start: f32,
        pub environmental_end: f32,
        pub render_distance_start: f32,
        pub render_distance_end: f32,
        pub sky_end: f32,
        pub cloud_end: f32,
    }

    #[derive(Clone, Debug)]
    pub struct NativeCamera {
        pub position: [f64; 3],
        pub eye_height: f32,
        pub partial_tick: f32,
        pub yaw: f32,
        pub pitch: f32,
        pub fov_degrees: f32,
        pub near: f32,
        pub far: f32,
        pub view: [f32; 16],
        pub projection: [f32; 16],
        pub dimension: String,
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct NativeClock { pub game_time: i64, pub day_time: i64 }

    #[derive(Clone, Debug, PartialEq)]
    #[non_exhaustive]
    pub struct NativeServerPlayer {
        pub uuid: u128,
        pub name: String,
        pub permission: u8,
        pub dimension: String,
        pub position: [f64; 3],
        pub game_mode: i32,
        pub health: f32,
        pub yaw: f32,
        pub pitch: f32,
        pub entity_id: i32,
        pub address: String,
    }

    #[derive(Clone, Debug, PartialEq, Eq)]
    #[non_exhaustive]
    pub struct NativeCommand { pub usage: String, pub level: u8 }
    impl NativeCommand {
        pub fn new(usage: &str) -> Self { Self { usage: usage.to_owned(), level: 0 } }
        pub fn op(usage: &str, level: u8) -> Self { Self { usage: usage.to_owned(), level: level.min(4) } }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum NativeHeightmap { WorldSurface, MotionBlocking, MotionBlockingNoLeaves, OceanFloor }
    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct NativeBlockRun {
        pub x: i32, pub z: i32, pub y: i32, pub height: u32,
        pub surface: Option<NativeHeightmap>, pub block: u32, pub only: Option<u32>,
    }
    #[derive(Clone, Debug, PartialEq, Default)]
    pub struct NativeBlockEdit { pub palette: Vec<String>, pub filters: Vec<String>, pub runs: Vec<NativeBlockRun> }

    #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
    pub struct NativeWorldEpoch(u64);
    impl NativeWorldEpoch {
        /// Stub only, for tests.
        #[doc(hidden)]
        pub fn for_tests(n: u64) -> Self { NativeWorldEpoch(n) }
    }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct NativeTickId(u64);
    #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
    pub struct EntityRef { epoch: NativeWorldEpoch, id: u32 }
    impl EntityRef {
        #[doc(hidden)]
        pub fn new(epoch: NativeWorldEpoch, id: u32) -> Self { Self { epoch, id } }
        pub fn id(self) -> u32 { self.id }
    }
    #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
    pub struct LevelRef { epoch: NativeWorldEpoch, dimension: &'static str }
    impl LevelRef {
        #[doc(hidden)]
        pub fn new(epoch: NativeWorldEpoch, dimension: &'static str) -> Self { Self { epoch, dimension } }
        pub fn dimension(self) -> &'static str { self.dimension }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    #[non_exhaustive]
    pub enum NativeAuthority { LocalAuthoritative, IntegratedServer, DedicatedServer, RemoteClient }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    #[non_exhaustive]
    pub struct NativeLifecycleContext { pub authority: NativeAuthority, pub epoch: NativeWorldEpoch, pub tick: NativeTickId }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    #[non_exhaustive]
    pub enum ServerTickPhase { ServerTickStart { world_running: bool }, ServerTickEnd { world_running: bool } }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Flow { Continue, Cancel }

    // ---- Host (helpers) ----------------------------------------------------

    pub trait Host {
        fn server_players(&self) -> Vec<NativeServerPlayer> { unimplemented!("Host::server_players") }
        fn acting_player(&self) -> Option<NativeServerPlayer> { unimplemented!("Host::acting_player") }
        fn tell(&mut self, uuid: u128, line: Text) -> bool { unimplemented!("Host::tell") }
        fn action_bar(&mut self, uuid: u128, line: Text) -> bool { unimplemented!("Host::action_bar") }
        fn broadcast(&self, line: Text) { unimplemented!("Host::broadcast") }
        fn feedback(&mut self, line: Text) { unimplemented!("Host::feedback") }
        fn run_command(&mut self, line: &str) -> bool { unimplemented!("Host::run_command") }
        fn world_dir(&self) -> Option<PathBuf> { unimplemented!("Host::world_dir") }
        fn config_dir(&self) -> Result<PathBuf, String> { unimplemented!("Host::config_dir") }
        fn edit_blocks(&mut self, dimension: &str, edit: &NativeBlockEdit) -> Result<u64, String> { unimplemented!("Host::edit_blocks") }
        fn surface_heights(&self, dimension: &str, columns: &[(i32, i32)], heightmap: NativeHeightmap) -> Result<Vec<Option<i32>>, String> { unimplemented!("Host::surface_heights") }
        fn net_send_to_player(&mut self, player: u128, channel: &str, data: &[u8]) -> Result<(), String> { unimplemented!("Host::net_send_to_player") }
        fn level(&self, dimension: &str) -> Option<LevelRef> { unimplemented!("Host::level") }
        fn level_game_time(&self, level: LevelRef) -> Option<i64> { unimplemented!("Host::level_game_time") }
        fn level_day_time(&self, level: LevelRef) -> Option<i64> { unimplemented!("Host::level_day_time") }
        fn entity(&self, id: u32) -> Option<EntityRef> { unimplemented!("Host::entity") }
        fn entity_position(&self, entity: EntityRef) -> Option<[f64; 3]> { unimplemented!("Host::entity_position") }
        fn entity_velocity(&self, entity: EntityRef) -> Option<[f64; 3]> { unimplemented!("Host::entity_velocity") }
        fn entity_set_velocity(&mut self, entity: EntityRef, velocity: [f64; 3]) -> bool { unimplemented!("Host::entity_set_velocity") }
        fn push_mob(&mut self, dimension: &str, id: u32, velocity: [f64; 3]) -> bool { unimplemented!("Host::push_mob") }
        fn camera(&self) -> Option<NativeCamera> { unimplemented!("Host::camera") }
        fn clock(&self) -> Option<NativeClock> { unimplemented!("Host::clock") }
        fn play_local_sound(&mut self, sound: &str, at: [f64; 3], volume: f32, pitch: f32, delay: i32) -> bool { unimplemented!("Host::play_local_sound") }
        fn push_player(&mut self, kick: [f32; 3]) { unimplemented!("Host::push_player") }
        fn fill(&self, batch: &mut HudBatch, rect: [f32; 4], argb: u32) { unimplemented!("Host::fill") }
        fn text(&self, batch: &mut HudBatch, text: &str, at: [f32; 2], rgb: u32, shadow: bool) { unimplemented!("Host::text") }
    }

    // ---- events --------------------------------------------------------------

    #[non_exhaustive] pub struct ServerTickEvent<'a> { pub context: &'a NativeLifecycleContext, pub phase: ServerTickPhase }
    #[non_exhaustive] pub struct LevelTickPostEvent<'a> { pub dimension: &'a str }
    #[non_exhaustive] pub struct TickEvent {}
    #[non_exhaustive] pub struct PlayerTickEvent {}
    #[non_exhaustive] pub struct PlayerLoggedOutEvent<'a> { pub player: u128, pub name: &'a str }
    #[non_exhaustive] pub struct EntityTickPostEvent<'a> { pub dimension: &'a str, pub entity: u32, pub kind: &'a str }
    #[non_exhaustive] pub struct RegisterCommandsEvent<'b> { pub commands: &'b mut Vec<NativeCommand> }
    #[non_exhaustive] pub struct PerformCommandEvent<'a> { pub player: &'a NativeServerPlayer, pub command: &'a str }
    #[non_exhaustive] pub struct NetFromServerEvent<'a> { pub channel: &'a str, pub data: &'a [u8] }
    #[non_exhaustive] pub struct HudEvent<'b> { pub batch: &'b mut HudBatch, pub corners: &'b mut Corners }
    #[non_exhaustive] pub struct WorldPrimitivesEvent<'b> { pub partial: f32, pub camera: [f64; 3], pub primitives: &'b mut WorldPrimitives }
    #[non_exhaustive] pub struct CameraShakeEvent<'b> { pub partial: f32, pub shake: &'b mut NativeShake }
    #[non_exhaustive] pub struct ComputeFogColorEvent<'b> { pub partial: f32, pub camera: [f64; 3], pub fog_type: FogType, pub rgb: &'b mut [f32; 3] }
    #[non_exhaustive] pub struct RenderFogEvent<'b> { pub partial: f32, pub camera: [f64; 3], pub fog_type: FogType, pub fog: &'b mut FogData }
    #[non_exhaustive] pub struct ShaderParamsEvent<'b> { pub partial: f32, pub params: &'b mut [[f32; 4]; 8] }
    #[non_exhaustive] pub struct ClientLevelLoadEvent<'a> { pub dimension: &'a str }
    #[non_exhaustive] pub struct ClientLevelUnloadEvent<'a> { pub dimension: &'a str }
    #[non_exhaustive] pub struct CommandsEvent<'b> { pub commands: &'b mut Vec<&'static str> }
    #[non_exhaustive] pub struct CommandEvent<'a> { pub command: &'a str }

    // ---- Hooks ---------------------------------------------------------------

    pub trait Hooks: Send + Sync {
        fn server_tick(&self, host: &mut dyn Host, event: &mut ServerTickEvent<'_>) {}
        fn level_tick_post(&self, host: &mut dyn Host, event: &mut LevelTickPostEvent<'_>) {}
        fn tick(&self, host: &mut dyn Host, event: &mut TickEvent) {}
        fn player_tick(&self, host: &mut dyn Host, event: &mut PlayerTickEvent) {}
        fn player_logged_out(&self, host: &mut dyn Host, event: &mut PlayerLoggedOutEvent<'_>) {}
        fn entity_tick_post(&self, host: &mut dyn Host, event: &mut EntityTickPostEvent<'_>) {}
        fn register_commands(&self, host: &mut dyn Host, event: &mut RegisterCommandsEvent<'_>) {}
        fn perform_command(&self, host: &mut dyn Host, event: &mut PerformCommandEvent<'_>) -> Flow { Flow::Continue }
        fn net_from_server(&self, host: &mut dyn Host, event: &mut NetFromServerEvent<'_>) {}
        fn hud(&self, host: &mut dyn Host, event: &mut HudEvent<'_>) {}
        fn world_primitives(&self, host: &dyn Host, event: &mut WorldPrimitivesEvent<'_>) {}
        fn camera_shake(&self, host: &dyn Host, event: &mut CameraShakeEvent<'_>) {}
        fn compute_fog_color(&self, host: &dyn Host, event: &mut ComputeFogColorEvent<'_>) {}
        fn render_fog(&self, host: &dyn Host, event: &mut RenderFogEvent<'_>) {}
        fn shader_params(&self, host: &dyn Host, event: &mut ShaderParamsEvent<'_>) {}
        fn client_level_load(&self, host: &mut dyn Host, event: &mut ClientLevelLoadEvent<'_>) {}
        fn client_level_unload(&self, host: &mut dyn Host, event: &mut ClientLevelUnloadEvent<'_>) {}
        fn commands(&self, host: &mut dyn Host, event: &mut CommandsEvent<'_>) {}
        fn command(&self, host: &mut dyn Host, event: &mut CommandEvent<'_>) -> Flow { Flow::Continue }
    }
}
