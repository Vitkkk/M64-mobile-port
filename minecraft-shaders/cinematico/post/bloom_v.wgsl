// Bloom, passo 2: o mesmo borrado na vertical, sobre o resultado do passo 1.
override bloom: f32 = 0.5;

fn post(input: PostInput) -> vec4<f32> {
    if bloom <= 0.0 {
        return vec4f(0.0, 0.0, 0.0, 1.0);
    }
    let step = vec2f(0.0, 2.5 / 1080.0);
    var sum = textureSampleLevel(previous, linear_sampler, input.uv, 0.0).rgb * 0.0997;
    var weight = 0.0997;
    for (var i = 1; i <= 12; i++) {
        let x = f32(i);
        let w = exp(-x * x / 50.0) * 0.0997;
        let a = textureSampleLevel(previous, linear_sampler, input.uv + step * x, 0.0).rgb;
        let b = textureSampleLevel(previous, linear_sampler, input.uv - step * x, 0.0).rgb;
        sum += (a + b) * w;
        weight += 2.0 * w;
    }
    return vec4f(sum / weight, 1.0);
}
