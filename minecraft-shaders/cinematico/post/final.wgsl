// Acabamento (resolucao da janela): FXAA, nitidez, aberracao cromatica, vinheta, grao de
// filme animado, barras de cinema e um dither que evita faixas no ceu.
override fxaa: bool = true;
override sharpen: f32 = 0.25;
override chromatic: f32 = 0.15;
override vignette: f32 = 0.35;
override film_grain: f32 = 0.2;
override letterbox: bool = false;

fn tap(uv: vec2f) -> vec3f {
    return textureSampleLevel(previous, linear_sampler, uv, 0.0).rgb;
}

// FXAA 3.11 (versao enxuta): suaviza os serrilhados seguindo a direcao da borda.
fn fxaa_color(uv: vec2f, px: vec2f) -> vec3f {
    let rgb_m = tap(uv);
    let nw = luma(tap(uv + vec2f(-1.0, -1.0) * px));
    let ne = luma(tap(uv + vec2f(1.0, -1.0) * px));
    let sw = luma(tap(uv + vec2f(-1.0, 1.0) * px));
    let se = luma(tap(uv + vec2f(1.0, 1.0) * px));
    let m = luma(rgb_m);
    let lmin = min(m, min(min(nw, ne), min(sw, se)));
    let lmax = max(m, max(max(nw, ne), max(sw, se)));
    if lmax - lmin < max(0.0312, lmax * 0.125) {
        return rgb_m;
    }
    var dir = vec2f(-((nw + ne) - (sw + se)), (nw + sw) - (ne + se));
    let reduce = max((nw + ne + sw + se) * 0.25 * 0.125, 1.0 / 128.0);
    let rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcp, vec2f(-8.0), vec2f(8.0)) * px;
    let a = 0.5 * (tap(uv + dir * (1.0 / 3.0 - 0.5)) + tap(uv + dir * (2.0 / 3.0 - 0.5)));
    let b = a * 0.5 + 0.25 * (tap(uv - dir * 0.5) + tap(uv + dir * 0.5));
    let lb = luma(b);
    if lb < lmin || lb > lmax {
        return a;
    }
    return b;
}

fn post(input: PostInput) -> vec4<f32> {
    let uv = input.uv;
    let size = vec2f(textureDimensions(previous));
    let px = 1.0 / size;

    var c = input.color.rgb;
    if fxaa {
        c = fxaa_color(uv, px);
    }

    // Nitidez adaptativa leve (compensa a suavidade do FXAA).
    if sharpen > 0.0 {
        let blur = (tap(uv + vec2f(px.x, 0.0)) + tap(uv - vec2f(px.x, 0.0))
            + tap(uv + vec2f(0.0, px.y)) + tap(uv - vec2f(0.0, px.y))) * 0.25;
        c = clamp(c + (c - blur) * sharpen * 0.8, vec3f(0.0), vec3f(1.0));
    }

    // Aberracao cromatica: vermelho e azul se separam nas bordas da tela.
    let centered = uv - 0.5;
    if chromatic > 0.0 {
        let o = centered * dot(centered, centered) * chromatic * 0.025;
        c.r = mix(c.r, tap(uv - o).r, 0.85);
        c.b = mix(c.b, tap(uv + o).b, 0.85);
    }

    // Vinheta oval suave.
    let aspect = size.x / size.y;
    let d = length(centered * vec2f(aspect, 1.0)) / length(vec2f(aspect, 1.0) * 0.5);
    c *= 1.0 - smoothstep(0.35, 1.15, d) * vignette * 0.75;

    // Grao de filme: mais visivel nos meios-tons, animado a 24 quadros por segundo.
    if film_grain > 0.0 {
        let frame24 = floor(frame.time * 24.0);
        let g = hash12(input.pixel + vec2f(frame24 * 17.13, frame24 * 7.31)) - 0.5;
        let l = luma(c);
        c += g * film_grain * 0.09 * (1.0 - abs(l - 0.5) * 1.4);
    }

    // Dither de 1/255 para o ceu nao formar faixas.
    c += (ign(input.pixel) - 0.5) / 255.0;

    // Barras pretas 2.39:1.
    if letterbox {
        let target_h = size.x / 2.39;
        let bar = (size.y - target_h) * 0.5 / size.y;
        if uv.y < bar || uv.y > 1.0 - bar {
            c = vec3f(0.0);
        }
    }
    return vec4f(clamp(c, vec3f(0.0), vec3f(1.0)), input.color.a);
}
