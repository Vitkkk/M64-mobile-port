//! The radar panel, the weather readout and the tornado warning banner.

use super::ClientState;
use crate::sim::precip::rain_rate;
use crate::sim::radar::dbz_color;
use horizon_client::mod_api::{Corner, Corners, Host, HudBatch};

const CELL: f32 = 2.0;

pub fn draw(c: &ClientState, host: &dyn Host, batch: &mut HudBatch, corners: &mut Corners) {
    if !c.active {
        return;
    }
    let mut screen_w: Option<f32> = None;
    if c.cfg.radar && c.radar_n > 0 && c.radar.len() == c.radar_n * c.radar_n {
        let n = c.radar_n;
        let side = n as f32 * CELL;
        let (x, y) = corners.place(Corner::TopRight, side + 4.0, side + 14.0);
        screen_w = Some(x + side + 6.0);
        host.fill(batch, [x, y, side + 4.0, side + 14.0], 0xA000_0000);
        let label = format!("{} {:.1}km", if c.cfg.velocity { "VEL" } else { "REF" }, c.cfg.radar_range / 1000.0);
        host.text(batch, &label, [x + 2.0, y + 2.0], 0xFFFFFF, true);
        let (ox, oy) = (x + 2.0, y + 12.0);
        // Row by row, merging runs of one colour into one rectangle.
        for row in 0..n {
            let line = &c.radar[row * n..(row + 1) * n];
            let mut col = 0;
            while col < n {
                let color = line[col];
                let start = col;
                while col < n && line[col] == color {
                    col += 1;
                }
                if color != 0 {
                    host.fill(batch, [ox + start as f32 * CELL, oy + row as f32 * CELL, (col - start) as f32 * CELL, CELL], color);
                }
            }
        }
        // Range ring at half range and the player.
        let mid = side / 2.0;
        host.fill(batch, [ox + mid - 1.0, oy + mid - 1.0, 2.0, 2.0], 0xFFFF_FFFF);
        // Tornadoes (TVS) as marked squares.
        let range = c.cfg.radar_range;
        for (_, tor) in &c.tors {
            let dx = (tor.pos[0] - c.cam[0]) / range;
            let dz = (tor.pos[1] - c.cam[2]) / range;
            if dx.abs() < 1.0 && dz.abs() < 1.0 {
                let px = ox + mid + dx as f32 * mid;
                let pz = oy + mid + dz as f32 * mid;
                host.fill(batch, [px - 2.5, pz - 2.5, 5.0, 5.0], 0xFFFF_FFFF);
                host.fill(batch, [px - 1.5, pz - 1.5, 3.0, 3.0], 0xFFFF_0000);
            }
        }
    }
    if c.cfg.info {
        let e = &c.env;
        let h = &c.here;
        let risk = e.risk();
        let lines = [
            (format!("{:.0}°C  Td {:.0}°C  {}", e.temp_c, e.dew_c, risk.label()), risk.rgb()),
            (format!("Wind {:.0} m/s {}", h.speed(), crate::sim::field::compass_from(h.wind)), 0xFFFFFF),
            (format!("CAPE {:.0}  SRH {:.0}  {:.0}kt", e.cape, e.srh, e.shear_kt), 0xD0D0D0),
            (
                if h.dbz >= 5.0 {
                    format!("{} {:.0}dBZ {:.0}mm/h", h.precip.label(), h.dbz, rain_rate(h.dbz))
                } else {
                    "No precipitation".to_string()
                },
                dbz_color(h.dbz.max(5.0)) & 0xFF_FFFF,
            ),
        ];
        let w = 136.0;
        let hgt = lines.len() as f32 * 10.0 + 4.0;
        let (x, y) = corners.place(Corner::TopRight, w, hgt);
        screen_w.get_or_insert(x + w + 2.0);
        host.fill(batch, [x, y, w, hgt], 0x8000_0000);
        for (i, (text, rgb)) in lines.iter().enumerate() {
            host.text(batch, text, [x + 2.0, y + 2.0 + i as f32 * 10.0], *rgb, true);
        }
    }
    if let Some(w) = c.warning {
        let text = if w.confirmed {
            format!("TORNADO WARNING  EF{}  {:.1} km {}", w.ef, w.dist / 1000.0, w.dir)
        } else {
            format!("Tornado possible: rotating storm {:.1} km {}", w.dist / 1000.0, w.dir)
        };
        let sw = screen_w.unwrap_or_else(|| {
            let (x, _) = corners.place(Corner::TopRight, 0.0, 0.0);
            x + 2.0
        });
        let tw = text.chars().count() as f32 * 6.0;
        let x = ((sw - tw) / 2.0).max(2.0);
        let blink = (c.t / 10) % 2 == 0;
        host.fill(batch, [x - 3.0, 18.0, tw + 6.0, 12.0], if w.confirmed && blink { 0xC0B0_0000 } else { 0xA040_0000 });
        host.text(batch, &text, [x, 20.0], if w.confirmed { 0xFFFFFF } else { 0xFFD080 }, true);
    }
}
