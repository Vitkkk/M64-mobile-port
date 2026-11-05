// Bloom, passo 1: pega so o que e mais claro que o resto da cena e borra na horizontal
// (gaussiana larga, amostras bilineares). O ceu claro do dia brilha bem menos que o sol, a
// lava ou as tochas. A saida e luz linear, guardada no alvo de 16 bits entre os passes.
override bloom: f32 = 0.5;

fn bright_tap(uv: vec2f) -> vec3f {
    let c = to_linear(textureSampleLevel(scene_color, linear_sampler, uv, 0.0).rgb);
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
    // Passo de 2.5 pixels a 1080p, proporcional a altura da imagem.
    let step = vec2f(2.5 * frame.resolution.y / 1080.0 / frame.resolution.x, 0.0);
    var sum = bright_tap(input.uv) * 0.0997;
    var weight = 0.0997;
    for (var i = 1; i <= 12; i++) {
        let x = f32(i);
        let w = exp(-x * x / 50.0) * 0.0997;
        sum += (bright_tap(input.uv + step * x) + bright_tap(input.uv - step * x)) * w;
        weight += 2.0 * w;
    }
    return vec4f(sum / weight, 1.0);
}
