// Chuva e neve iluminadas pelo ceu e pelo sol, mais finas e discretas que as do jogo.
fn weather(input: WeatherInput) -> vec4<f32> {
    let base = to_linear(input.color.rgb);
    var light = vec3f(0.0);
    if overworld() {
        let sky_vis = pow(input.light.y, 2.0);
        light = sky_ambient(vec3f(0.0, 1.0, 0.0)) * sky_vis * 1.4 + light_color() * 0.25 * sky_vis;
    } else {
        light = vec3f(0.6);
    }
    light += vec3f(1.0, 0.56, 0.24) * pow(input.light.x, 3.0) * 1.5;
    var c = base * light;
    var a = input.color.a;
    if input.snow {
        a *= 0.9;
        c *= 1.2;
    } else {
        // Gotas de chuva sao quase transparentes: so pegam um pouco da luz.
        a *= 0.45;
        c = mix(c, light * 0.8, 0.5);
    }
    let dist = length(input.position);
    a *= 1.0 - smoothstep(24.0, 48.0, dist) * 0.5;
    return vec4<f32>(tonemap(c), a);
}
