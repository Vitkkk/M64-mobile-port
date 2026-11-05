// Cor de cinema: ajusta o balanco de branco pela hora do dia e aplica o estilo de cor
// (contraste em S, split toning, saturacao).
override color_style: u32 = 1u;
override saturation: f32 = 1.05;
override contrast: f32 = 1.1;

fn soft_light(base: vec3f, blend: vec3f) -> vec3f {
    let low = 2.0 * base * blend + base * base * (1.0 - 2.0 * blend);
    let high = sqrt(max(base, vec3f(0.0))) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend);
    return select(low, high, blend > vec3f(0.5));
}

fn post(input: PostInput) -> vec4<f32> {
    var lin = to_linear(input.color.rgb);

    // Debaixo d'agua: a luz perde o vermelho.
    lin *= mix(vec3f(1.0), vec3f(0.55, 0.85, 1.0), frame.in_water);

    var c = to_gamma(lin);

    // Balanco de branco: dourado no por do sol, azulado na noite (so ao ar livre).
    if overworld() {
        let outside = frame.eye_light_smooth.y;
        let gold = golden_factor() * outside;
        let night = (1.0 - day_factor()) * outside;
        c *= mix(vec3f(1.0), vec3f(1.06, 1.0, 0.9), gold);
        c *= mix(vec3f(1.0), vec3f(0.92, 0.97, 1.08), night);
    }

    let l = luma(c);
    switch color_style {
        case 1u: {
            // Filme quente: sombras levemente esverdeadas/azuladas, luzes cor de mel, pretos
            // levemente "lavados" como pelicula.
            c = soft_light(c, mix(vec3f(0.47, 0.5, 0.52), vec3f(0.54, 0.5, 0.45), l));
            c = c * 0.97 + 0.015;
        }
        case 2u: {
            // Teal e laranja: o look de blockbuster.
            let teal = vec3f(0.38, 0.52, 0.56);
            let orange = vec3f(0.62, 0.5, 0.38);
            c = soft_light(c, mix(teal, orange, smoothstep(0.15, 0.75, l)));
        }
        case 3u: {
            // Frio nordico: dessaturado e azul-acinzentado.
            c = mix(vec3f(l), c, 0.75) * vec3f(0.95, 1.0, 1.06);
        }
        case 4u: {
            // Noir: preto e branco com contraste alto.
            let g = smoothstep(0.02, 0.98, l);
            c = vec3f(g * g * (3.0 - 2.0 * g));
        }
        default: {}
    }

    // Contraste em S em torno do meio-tom.
    let mid = 0.45;
    c = clamp(c, vec3f(0.0), vec3f(1.0));
    let lo = mid * pow(c / mid, vec3f(contrast));
    let hi = 1.0 - (1.0 - mid) * pow((1.0 - c) / (1.0 - mid), vec3f(contrast));
    c = select(lo, hi, c > vec3f(mid));

    // Saturacao com protecao das cores ja fortes (vibrance).
    let l2 = luma(c);
    let maxc = max(c.r, max(c.g, c.b));
    let minc = min(c.r, min(c.g, c.b));
    let sat_now = (maxc - minc) / max(maxc, 1e-4);
    let amount = mix(saturation, 1.0 + (saturation - 1.0) * 0.4, sat_now);
    c = mix(vec3f(l2), c, amount);

    return vec4f(clamp(c, vec3f(0.0), vec3f(1.0)), input.color.a);
}
