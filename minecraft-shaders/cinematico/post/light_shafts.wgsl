// Raios de sol volumetricos (meia resolucao): de cada pixel caminha em direcao ao sol na tela
// e soma o ceu visivel no caminho. Arvores, montanhas e predios recortam os raios.
override light_shafts: f32 = 0.7;
override quality: u32 = 2u;

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

fn post(input: PostInput) -> vec4<f32> {
    if light_shafts <= 0.0 || !overworld() || frame.eye_in != 0u {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }
    let l = frame.light_direction;
    let is_sun = frame.sun_direction.y > 0.0;
    // Onde o sol (ou a lua) esta na tela.
    let clip = frame.projection * frame.view * vec4f(l, 0.0);
    if clip.w <= 0.0 {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }
    let ndc = clip.xy / clip.w;
    let sun_uv = vec2f(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    // Some quando o sol sai muito da tela, de noite fica fraco (luar), some na chuva.
    let off = max(abs(sun_uv.x - 0.5), abs(sun_uv.y - 0.5));
    let on_screen = 1.0 - smoothstep(0.6, 1.1, off);
    let strength = light_shafts * on_screen * select(0.12, 1.0, is_sun) * (1.0 - frame.rain * 0.9)
        * smoothstep(-0.02, 0.08, l.y);
    if strength <= 0.001 {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }

    let n = shaft_samples();
    let delta = (sun_uv - input.uv) / f32(n);
    let jitter = ign(input.pixel);
    var uv = input.uv + delta * jitter;
    var sum = 0.0;
    var decay = 1.0;
    let aspect = frame.resolution.x / frame.resolution.y;
    for (var i = 0; i < n; i++) {
        let inside = all(uv > vec2f(0.0)) && all(uv < vec2f(1.0));
        if inside && depth_at(uv) == 0.0 {
            // Perto do disco o ceu brilha mais: raios saem do sol, nao do ceu inteiro.
            let d = length((uv - sun_uv) * vec2f(aspect, 1.0));
            sum += decay * (0.25 + 0.75 * exp(-d * 6.0));
        }
        decay *= 0.965;
        uv += delta;
    }
    sum /= f32(n);
    // Cor: a luz do sol depois da atmosfera, quente no fim de tarde.
    let color = normalize(sun_transmittance(frame.sun_direction.y) + vec3f(1e-3)) * 1.2;
    let moon = vec3f(0.5, 0.6, 1.0);
    let tint = select(moon, color, is_sun) * (1.0 + golden_factor() * 1.5);
    return vec4f(tint * sum * strength, 1.0);
}
