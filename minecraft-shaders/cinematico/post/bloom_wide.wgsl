// Bloom, passo 2 (1/4 da resolucao): um halo largo e macio a partir do passo 1, em dois aneis,
// para a luz "vazar" como numa lente de verdade.
override bloom: f32 = 0.5;

fn post(input: PostInput) -> vec4<f32> {
    if bloom <= 0.0 {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }
    let px = 2.0 / frame.resolution;
    var sum = textureSampleLevel(bloom_half, linear_sampler, input.uv, 0.0).rgb * 0.16;
    var weight = 0.16;
    for (var i = 0; i < 16; i++) {
        let ring = select(2.0, 5.0, i >= 8);
        let a = f32(i) * 0.7853982 + select(0.0, 0.3926991, i >= 8);
        let o = vec2f(cos(a), sin(a)) * ring * px * (frame.resolution.y / 720.0);
        let w = select(0.07, 0.035, i >= 8);
        sum += textureSampleLevel(bloom_half, linear_sampler, input.uv + o, 0.0).rgb * w;
        weight += w;
    }
    return vec4f(sum / weight, 1.0);
}
