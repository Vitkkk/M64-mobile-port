// O ceu: atmosfera fisica aproximada e um sol redondo com escurecimento de borda e halo.
// A lua, as estrelas e as nuvens do jogo sao desenhadas por cima.
override sun_size: f32 = 1.0;

fn sun_disk(d: vec3f) -> vec3f {
    let s = frame.sun_direction;
    let mu = dot(d, s);
    // Raio angular de ~1.1 grau (o sol real tem 0.27; um pouco maior fica mais cinematografico).
    let radius = 0.019 * sun_size;
    let angle = acos(clamp(mu, -1.0, 1.0));
    let edge = 1.0 - smoothstep(radius * 0.92, radius, angle);
    // Escurecimento de borda: o centro do disco e mais claro e mais branco.
    let r = clamp(angle / radius, 0.0, 1.0);
    let limb = pow(max(1.0 - r * r, 0.0), 0.4);
    let glow = pow(max(mu, 0.0), 900.0 / sun_size) * 0.6;
    let color = sun_tint(s.y);
    let visible = smoothstep(-0.02, 0.01, d.y) * (1.0 - frame.rain);
    return color * (edge * mix(0.6, 1.0, limb) * 60.0 + glow * 2.0) * visible;
}

fn sky(input: SkyInput) -> vec4<f32> {
    // Debaixo d'agua ou na lava o jogo usa a cor da neblina; fica com ela.
    if !overworld() || frame.eye_in != 0u {
        return input.color;
    }
    let d = input.direction;
    var c = atmosphere(d);
    c += sun_disk(d) * smoothstep(-0.05, 0.02, frame.sun_direction.y);
    // A cegueira e a escuridao fecham o ceu como o jogo faz.
    let dark = max(frame.blindness, frame.darkness);
    let result = mix(tonemap_sky(c), input.color.rgb, dark);
    return vec4<f32>(result, input.color.a);
}
