// A lente: profundidade de campo com foco automatico no centro da tela (bokeh redondo),
// desfoque de movimento da camera, o bloom dos dois passos anteriores e os raios de sol
// volumetricos. Foco e movimento vem desligados por padrao.
//
// `previous` aqui e o bloom (passos 1 e 2); a imagem do mundo vem de `scene_color`.
override dof: bool = false;
override dof_strength: f32 = 1.0;
override motion_blur: bool = false;
override motion_blur_strength: f32 = 0.5;
override bloom: f32 = 0.5;
override light_shafts: f32 = 0.7;
override quality: u32 = 2u;

fn scene(uv: vec2f) -> vec3f {
    return textureSampleLevel(scene_color, linear_sampler, uv, 0.0).rgb;
}

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

fn shaft_samples() -> i32 {
    var n = 40;
    if quality == 0u {
        n = 12;
    } else if quality == 1u {
        n = 20;
    } else if quality == 2u {
        n = 28;
    }
    return n;
}

// Raios de sol: de cada pixel caminha em direcao ao sol na tela e soma o ceu visivel no
// caminho. Arvores, montanhas e predios recortam os raios. Devolve luz linear.
fn sun_shafts(uv0: vec2f, pixel: vec2f) -> vec3f {
    if light_shafts <= 0.0 || !overworld() || frame.eye_in != 0u {
        return vec3f(0.0);
    }
    let l = frame.light_direction;
    let is_sun = frame.sun_direction.y > 0.0;
    let clip = frame.projection * frame.view * vec4f(l, 0.0);
    if clip.w <= 0.0 {
        return vec3f(0.0);
    }
    let ndc = clip.xy / clip.w;
    let sun_uv = vec2f(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    // Some quando o sol sai muito da tela, de noite fica fraco (luar), some na chuva.
    let off = max(abs(sun_uv.x - 0.5), abs(sun_uv.y - 0.5));
    let on_screen = 1.0 - smoothstep(0.6, 1.1, off);
    let strength = light_shafts * on_screen * select(0.12, 1.0, is_sun) * (1.0 - frame.rain * 0.9)
        * smoothstep(-0.02, 0.08, l.y);
    if strength <= 0.001 {
        return vec3f(0.0);
    }
    let n = shaft_samples();
    let delta = (sun_uv - uv0) / f32(n);
    var uv = uv0 + delta * ign(pixel);
    var sum = 0.0;
    var decay = 1.0;
    let aspect = frame.resolution.x / frame.resolution.y;
    for (var i = 0; i < n; i++) {
        let inside = all(uv > vec2f(0.0)) && all(uv < vec2f(1.0));
        if inside && depth_at(uv) == 0.0 {
            // Perto do disco o ceu brilha mais: os raios saem do sol, nao do ceu inteiro.
            let d = length((uv - sun_uv) * vec2f(aspect, 1.0));
            sum += decay * (0.25 + 0.75 * exp(-d * 6.0));
        }
        decay *= 0.965;
        uv += delta;
    }
    sum /= f32(n);
    let color = sun_tint(frame.sun_direction.y);
    let tint = select(vec3f(0.5, 0.6, 1.0), color, is_sun) * (1.0 + golden_factor() * 1.5);
    return tint * sum * strength * 0.6;
}

fn post(input: PostInput) -> vec4<f32> {
    let uv = input.uv;
    var color = scene(uv);

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
                    sum += scene(clamp(uv + vel * t, vec2f(0.0), vec2f(1.0)));
                }
                color = sum / 9.0;
            }
        }
    }

    if dof {
        // O foco: a distancia no centro da tela (o mais perto de 5 pontos, para nao pular).
        var focus = linear_distance(vec2f(0.5));
        focus = min(focus, linear_distance(vec2f(0.48, 0.5)));
        focus = min(focus, linear_distance(vec2f(0.52, 0.5)));
        focus = min(focus, linear_distance(vec2f(0.5, 0.47)));
        focus = min(focus, linear_distance(vec2f(0.5, 0.53)));
        let dist = linear_distance(uv);
        // Circulo de confusao em pixels: cresce com a diferenca relativa de distancia.
        let coc_1080 = clamp(abs(dist - focus) / max(dist, 0.5) * dof_strength * 9.0, 0.0, 14.0);
        let coc = coc_1080 * frame.resolution.y / 1080.0;
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
                let c = scene(uv + vec2f(cos(a), sin(a)) * r * px);
                // Os brilhos pesam mais: bokeh com pontos de luz destacados.
                let w = 1.0 + luma(c) * luma(c) * 4.0;
                sum += c * w;
                weight += w;
            }
            color = sum / weight;
        }
    }

    // Soma o bloom e os raios de sol em luz linear.
    var lin = to_linear(color);
    if bloom > 0.0 {
        lin += input.color.rgb * bloom * 1.1;
    }
    lin += sun_shafts(uv, input.pixel);
    // A alpha do mundo (a saida HDR usa para achar as fontes de luz).
    let alpha = textureSampleLevel(scene_color, linear_sampler, uv, 0.0).a;
    return vec4f(to_gamma(lin), alpha);
}
