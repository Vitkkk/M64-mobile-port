//! What falls from the sky.

/// Precipitation type.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Precip {
    #[default]
    None,
    Rain,
    Snow,
    Sleet,
    FreezingRain,
    Hail,
}

impl Precip {
    pub fn label(self) -> &'static str {
        match self {
            Precip::None => "-",
            Precip::Rain => "Rain",
            Precip::Snow => "Snow",
            Precip::Sleet => "Sleet",
            Precip::FreezingRain => "Fz Rain",
            Precip::Hail => "Hail",
        }
    }
}

/// Classifies precipitation from reflectivity and surface temperature.
pub fn classify(dbz: f64, temp_c: f64, convective: bool) -> Precip {
    if dbz < 15.0 {
        return Precip::None;
    }
    if convective && dbz >= 60.0 && temp_c > -5.0 {
        return Precip::Hail;
    }
    match temp_c {
        t if t <= -3.0 => Precip::Snow,
        t if t <= -1.0 => Precip::Sleet,
        t if t <= 0.5 => Precip::FreezingRain,
        _ => Precip::Rain,
    }
}

/// Rain rate in mm/h from reflectivity (Marshall-Palmer, Z = 200 R^1.6).
pub fn rain_rate(dbz: f64) -> f64 {
    if dbz <= 0.0 {
        return 0.0;
    }
    let z = 10f64.powf(dbz / 10.0);
    (z / 200.0).powf(1.0 / 1.6)
}

/// Rough largest hail diameter, cm.
pub fn hail_size_cm(dbz: f64) -> f64 {
    ((dbz - 55.0).max(0.0) * 0.45).min(10.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn types() {
        assert_eq!(classify(10.0, 20.0, true), Precip::None);
        assert_eq!(classify(40.0, 20.0, true), Precip::Rain);
        assert_eq!(classify(65.0, 20.0, true), Precip::Hail);
        assert_eq!(classify(30.0, -10.0, false), Precip::Snow);
        assert_eq!(classify(30.0, 0.0, false), Precip::FreezingRain);
        assert!((rain_rate(40.0) - 11.5).abs() < 1.0);
    }
}
