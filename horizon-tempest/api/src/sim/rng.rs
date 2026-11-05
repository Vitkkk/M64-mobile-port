//! Deterministic hashing and random numbers.
//!
//! Every storm is derived from hashes of (seed, cell, time slot, kind), so the
//! server and every client compute exactly the same weather without having to
//! send it around. No global state, no allocation.

/// SplitMix64 finaliser: a cheap, well-mixed 64-bit hash.
#[inline]
pub fn mix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    x ^ (x >> 31)
}

/// Hash of several words.
#[inline]
pub fn hash(parts: &[u64]) -> u64 {
    let mut h = 0x243F_6A88_85A3_08D3u64;
    for &p in parts {
        h = mix64(h ^ p);
    }
    h
}

/// A hash mapped to [0, 1).
#[inline]
pub fn unit(h: u64) -> f64 {
    (h >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
}

/// A tiny deterministic generator (SplitMix64 stream).
#[derive(Clone, Debug)]
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Self {
        Rng(mix64(seed))
    }
    #[inline]
    pub fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        mix64(self.0)
    }
    /// Uniform in [0, 1).
    #[inline]
    pub fn f64(&mut self) -> f64 {
        unit(self.next_u64())
    }
    /// Uniform in [a, b).
    #[inline]
    pub fn range(&mut self, a: f64, b: f64) -> f64 {
        a + (b - a) * self.f64()
    }
    #[inline]
    pub fn chance(&mut self, p: f64) -> bool {
        self.f64() < p
    }
    #[inline]
    pub fn u32(&mut self) -> u32 {
        (self.next_u64() >> 32) as u32
    }
    /// Index picked by weights (weights need not sum to 1).
    pub fn weighted(&mut self, weights: &[f64]) -> usize {
        let total: f64 = weights.iter().sum();
        let mut r = self.f64() * total;
        for (i, w) in weights.iter().enumerate() {
            if r < *w {
                return i;
            }
            r -= *w;
        }
        weights.len().saturating_sub(1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministic_and_spread() {
        let mut a = Rng::new(7);
        let mut b = Rng::new(7);
        for _ in 0..100 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        let mut r = Rng::new(1);
        let mean: f64 = (0..10_000).map(|_| r.f64()).sum::<f64>() / 10_000.0;
        assert!((mean - 0.5).abs() < 0.02, "mean {mean}");
        assert_ne!(hash(&[1, 2]), hash(&[2, 1]));
    }

    #[test]
    fn weighted_respects_weights() {
        let mut r = Rng::new(3);
        let mut counts = [0usize; 3];
        for _ in 0..30_000 {
            counts[r.weighted(&[0.7, 0.2, 0.1])] += 1;
        }
        assert!(counts[0] > counts[1] && counts[1] > counts[2]);
    }
}
