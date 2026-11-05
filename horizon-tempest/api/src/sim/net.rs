//! The server's storm list, sent to each client (`tempest:sync`).
//!
//! A storm is ~70 bytes and fully describes itself for its whole life, so the
//! server only needs to send the list now and then; clients evaluate it every
//! frame on their own.

use super::storm::{StormDef, StormKind, TornadoDef};

pub const CHANNEL: &str = "tempest:sync";
const MAGIC: u8 = 0x54; // 'T'
const VERSION: u8 = 1;
/// Mod messages are capped at 30 000 bytes.
pub const MAX_STORMS: usize = 256;

#[derive(Clone, Debug, PartialEq)]
pub struct Snapshot {
    pub seed: u64,
    pub year_days: f32,
    pub game_time: i64,
    pub storms: Vec<StormDef>,
}

struct W(Vec<u8>);
impl W {
    fn u8(&mut self, v: u8) { self.0.push(v); }
    fn u32(&mut self, v: u32) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn i32(&mut self, v: i32) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn u64(&mut self, v: u64) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn i64(&mut self, v: i64) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn f32(&mut self, v: f32) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn f64(&mut self, v: f64) { self.0.extend_from_slice(&v.to_le_bytes()); }
}

struct R<'a>(&'a [u8]);
impl R<'_> {
    fn take<const N: usize>(&mut self) -> Option<[u8; N]> {
        if self.0.len() < N {
            return None;
        }
        let (a, b) = self.0.split_at(N);
        self.0 = b;
        a.try_into().ok()
    }
    fn u8(&mut self) -> Option<u8> { self.take::<1>().map(|b| b[0]) }
    fn u32(&mut self) -> Option<u32> { self.take().map(u32::from_le_bytes) }
    fn i32(&mut self) -> Option<i32> { self.take().map(i32::from_le_bytes) }
    fn u64(&mut self) -> Option<u64> { self.take().map(u64::from_le_bytes) }
    fn i64(&mut self) -> Option<i64> { self.take().map(i64::from_le_bytes) }
    fn f32(&mut self) -> Option<f32> { self.take().map(f32::from_le_bytes).filter(|v| v.is_finite()) }
    fn f64(&mut self) -> Option<f64> { self.take().map(f64::from_le_bytes).filter(|v| v.is_finite()) }
}

pub fn encode(s: &Snapshot) -> Vec<u8> {
    let mut w = W(Vec::with_capacity(24 + s.storms.len() * 80));
    w.u8(MAGIC);
    w.u8(VERSION);
    w.u64(s.seed);
    w.f32(s.year_days);
    w.i64(s.game_time);
    let n = s.storms.len().min(MAX_STORMS);
    w.u32(n as u32);
    for st in &s.storms[..n] {
        w.u64(st.id);
        w.u8(st.kind as u8);
        w.i64(st.t0);
        w.i32(st.life);
        w.f64(st.origin[0]);
        w.f64(st.origin[1]);
        w.f32(st.vel[0]);
        w.f32(st.vel[1]);
        w.f32(st.curve);
        w.f32(st.strength);
        w.f32(st.size);
        w.f32(st.aux);
        w.u32(st.seed);
        w.u8(st.manual as u8);
        match st.tornado {
            Some(t) => {
                w.u8(1);
                w.i32(t.start);
                w.i32(t.dur);
                w.u8(t.ef);
                w.f32(t.width);
                w.u32(t.seed);
                w.f32(t.along);
            }
            None => w.u8(0),
        }
    }
    w.0
}

pub fn decode(data: &[u8]) -> Option<Snapshot> {
    let mut r = R(data);
    if r.u8()? != MAGIC || r.u8()? != VERSION {
        return None;
    }
    let seed = r.u64()?;
    let year_days = r.f32()?;
    let game_time = r.i64()?;
    let n = r.u32()? as usize;
    if n > MAX_STORMS {
        return None;
    }
    let mut storms = Vec::with_capacity(n);
    for _ in 0..n {
        let id = r.u64()?;
        let kind = StormKind::from_u8(r.u8()?)?;
        let t0 = r.i64()?;
        let life = r.i32()?;
        let origin = [r.f64()?, r.f64()?];
        let vel = [r.f32()?, r.f32()?];
        let curve = r.f32()?;
        let strength = r.f32()?;
        let size = r.f32()?;
        let aux = r.f32()?;
        let seed = r.u32()?;
        let manual = r.u8()? != 0;
        let tornado = match r.u8()? {
            0 => None,
            _ => Some(TornadoDef {
                start: r.i32()?,
                dur: r.i32()?,
                ef: r.u8()?.min(5),
                width: r.f32()?,
                seed: r.u32()?,
                along: r.f32()?,
            }),
        };
        if life <= 0 || size <= 0.0 {
            return None;
        }
        storms.push(StormDef { id, kind, t0, life, origin, vel, curve, strength, size, aux, seed, tornado, manual });
    }
    Some(Snapshot { seed, year_days, game_time, storms })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sim::spawn::Spawner;

    #[test]
    fn round_trip_and_size() {
        let sp = Spawner::new(11, 48.0);
        let mut storms = Vec::new();
        for (i, k) in StormKind::ALL.iter().enumerate() {
            storms.push(sp.manual(*k, [i as f64 * 100.0, 5.0], 10_000, Some(2), 600));
        }
        let snap = Snapshot { seed: 11, year_days: 48.0, game_time: 10_000, storms };
        let bytes = encode(&snap);
        assert_eq!(decode(&bytes), Some(snap));
        assert!(bytes.len() < 30_000);
        let full = Snapshot {
            seed: 1,
            year_days: 48.0,
            game_time: 0,
            storms: vec![sp.manual(StormKind::Supercell, [0.0, 0.0], 0, Some(5), 0); MAX_STORMS],
        };
        assert!(encode(&full).len() < 30_000, "fits in one mod message");
        assert_eq!(decode(&bytes[..bytes.len() - 1]), None);
        assert_eq!(decode(&[1, 2, 3]), None);
    }
}
