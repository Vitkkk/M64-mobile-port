// Bloom, passo 1 (meia resolucao): pega so o que e mais claro que o resto da cena e reduz a
// imagem com um filtro de 13 amostras (sem cintilar). O ceu claro do dia brilha bem menos que o
// sol, a lava ou as tochas.
override bloom: f32 = 0.5;

fn bright_tap(uv: vec2f) -> vec3f {
    let c = to_linear(textureSampleLevel(previous, linear_sampler, uv, 0.0).rgb);
    let l = luma(c);
    let threshold = 0.42;
    let knee = 0.25;
    let soft = clamp(l - threshold + knee, 0.0, 2.0 * knee);
    let contrib = max(soft * soft / (4.0 * knee + 1e-4), l - threshold) / max(l, 1e-4);
    let sky = select(1.0, 0.3, depth_at(uv) == 0.0);
    return c * max(contrib, 0.0) * sky;
}

fn post(input: PostInput) -> vec4<f32> {
    if bloom <= 0.0 {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }
    let px = 1.0 / frame.resolution;
    let uv = input.uv;
    var c = bright_tap(uv) * 0.125;
    c += (bright_tap(uv + vec2f(-1.0, -1.0) * px) + bright_tap(uv + vec2f(1.0, -1.0) * px)
        + bright_tap(uv + vec2f(-1.0, 1.0) * px) + bright_tap(uv + vec2f(1.0, 1.0) * px)) * 0.125;
    c += (bright_tap(uv + vec2f(-2.0, -2.0) * px) + bright_tap(uv + vec2f(2.0, -2.0) * px)
        + bright_tap(uv + vec2f(-2.0, 2.0) * px) + bright_tap(uv + vec2f(2.0, 2.0) * px)) * 0.03125;
    c += (bright_tap(uv + vec2f(-2.0, 0.0) * px) + bright_tap(uv + vec2f(2.0, 0.0) * px)
        + bright_tap(uv + vec2f(0.0, -2.0) * px) + bright_tap(uv + vec2f(0.0, 2.0) * px)) * 0.0625;
    return vec4f(c, 1.0);
}
