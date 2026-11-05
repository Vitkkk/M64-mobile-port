//! The weather itself, free of any game API: pure, deterministic and cheap.
//! Everything here is unit tested on its own (`cargo test`).

pub mod climate;
pub mod config;
pub mod damage;
pub mod field;
pub mod net;
pub mod noise;
pub mod precip;
pub mod radar;
pub mod rng;
pub mod spawn;
pub mod storm;
