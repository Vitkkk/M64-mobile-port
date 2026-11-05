// A lente: profundidade de campo com foco automatico no centro da tela (bokeh redondo) e
// desfoque de movimento da camera. Os dois vem desligados por padrao.
override dof: bool = false;
override dof_strength: f32 = 1.0;
override motion_blur: bool = false;
override motion_blur_strength: f32 = 0.5;
override quality: u32 = 2u;

fn linear_distance(uv: vec2f) -> f32 {
    let d = depth_at(uv);
    if d <= 0.0 {
        return frame.far * 4.0;
    }
    return length(view_position(uv, d));
}

fn dof_taps() -> i32 {
    var n = 36;
    if quality == 0u {
        n = 8;
    } else if quality == 1u {
        n = 16;
    } else if quality == 2u {
        n = 24;
    }
    return n;
}

fn post(input: PostInput) -> vec4<f32> {
    var color = input.color.rgb;
    let uv = input.uv;

    if motion_blur {
        let depth = max(input.depth, 1e-6);
        let p = view_position(uv, depth);
        let prev = frame.previous_view_projection * vec4f(p + frame.camera_motion, 1.0);
        if prev.w > 0.0 && frame.delta_time > 0.0 {
            let prev_uv = vec2f(prev.x / prev.w * 0.5 + 0.5, 0.5 - prev.y / prev.w * 0.5);
            // Normaliza para 60 fps, assim o rastro e o mesmo em qualquer taxa de quadros.
            var vel = (uv - prev_uv) * motion_blur_strength * (1.0 / 60.0) / max(frame.delta_time, 1e-3);
            let max_len = 0.04;
            let len = length(vel);
            if len > max_len {
                vel *= max_len / len;
            }
            if len > 0.0005 {
                var sum = color;
                let jitter = ign(input.pixel) - 0.5;
                for (var i = 1; i <= 8; i++) {
                    let t = (f32(i) + jitter) / 8.0 - 0.5;
                    sum += textureSampleLevel(previous, linear_sampler, clamp(uv + vel * t, vec2f(0.0), vec2f(1.0)), 0.0).rgb;
                }
                color = sum / 9.0;
            }
        }
    }

    if dof {
        // O foco: a distancia no centro da tela (media de 5 pontos para nao pular).
        var focus = linear_distance(vec2f(0.5));
        focus = min(focus, linear_distance(vec2f(0.48, 0.5)));
        focus = min(focus, linear_distance(vec2f(0.52, 0.5)));
        focus = min(focus, linear_distance(vec2f(0.5, 0.47)));
        focus = min(focus, linear_distance(vec2f(0.5, 0.53)));
        let dist = linear_distance(uv);
        // Circulo de confusao em pixels: cresce com a diferenca relativa de distancia.
        let coc_of = clamp(abs(dist - focus) / max(dist, 0.5) * dof_strength * 9.0, 0.0, 14.0);
        let coc = coc_of * frame.resolution.y / 1080.0;
        if coc > 0.6 {
            let px = 1.0 / frame.resolution;
            let n = dof_taps();
            var sum = color;
            var weight = 1.0;
            let rot = ign(input.pixel) * TAU;
            for (var i = 0; i < n; i++) {
                let fi = f32(i) + 0.5;
                let r = sqrt(fi / f32(n)) * coc;
                let a = fi * 2.39996323 + rot;
                let suv = uv + vec2f(cos(a), sin(a)) * r * px;
                let c = textureSampleLevel(previous, linear_sampler, suv, 0.0).rgb;
                // Os brilhos pesam mais: bokeh com pontos de luz destacados.
                let w = 1.0 + luma(c) * luma(c) * 4.0;
                sum += c * w;
                weight += w;
            }
            color = sum / weight;
        }
    }
    return vec4f(color, input.color.a);
}
