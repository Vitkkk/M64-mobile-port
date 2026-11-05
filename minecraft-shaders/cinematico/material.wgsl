// O terreno: luz do sol e da lua com sombras suaves, luz do ceu, tochas coloridas, brilho
// especular (PBR), chao molhado, folhas translucidas, agua com reflexo/refracao/causticas e a
// neblina atmosferica. Os ids MAT_* e as opcoes de vento/ondas estao em vertex.wgsl, que
// entra neste mesmo modulo.

override quality: u32 = 2u;
override shadows: bool = true;
override shadow_softness: f32 = 1.0;
override ambient_strength: f32 = 1.0;
override torch_strength: f32 = 1.0;
override colored_lights: bool = true;
override foliage_glow: f32 = 0.6;
override auto_bump: bool = true;
override wet_reflections: bool = true;
override water_reflections: bool = true;
override water_clarity: f32 = 1.0;
override caustics: bool = true;
override haze: f32 = 1.0;

const TORCH_COLOR: vec3f = vec3f(1.0, 0.56, 0.24);

// ---------------------------------------------------------------- utilidades

fn is_foliage(m: u32) -> bool {
    return m == MAT_LEAVES || m == MAT_PLANT || m == MAT_PLANT_LOW || m == MAT_PLANT_TOP || m == MAT_VINE;
}

// A sombra vanilla de cada face (1 em cima, 0.5 embaixo, 0.8 norte/sul, 0.6 leste/oeste) ja
// vem no `tint`. A nossa luz direcional faz esse papel, entao desfazemos a maior parte dela.
fn vanilla_face_shade(n: vec3f) -> f32 {
    let a = abs(n);
    if a.y > 0.9 {
        return select(0.5, 1.0, n.y > 0.0);
    }
    if a.z > 0.9 {
        return 0.8;
    }
    if a.x > 0.9 {
        return 0.6;
    }
    return 1.0;
}

// Ponto camera-relativo atras de uma superficie, a partir da profundidade opaca em `screen`.
fn opaque_position(screen: vec2f) -> vec3f {
    let size = vec2f(textureDimensions(opaque_depth));
    let texel = vec2<i32>(clamp(screen * size, vec2f(0.0), size - 1.0));
    let depth = max(textureLoad(opaque_depth, texel, 0), 1e-7);
    let clip = vec4f(screen.x * 2.0 - 1.0, 1.0 - screen.y * 2.0, depth, 1.0);
    let view = frame.projection_inverse * clip;
    return (frame.view_inverse * vec4f(view.xyz / view.w, 0.0)).xyz;
}

fn opaque_rgb(screen: vec2f) -> vec3f {
    let size = vec2f(textureDimensions(opaque_color));
    let texel = vec2<i32>(clamp(screen * size, vec2f(0.0), size - 1.0));
    return textureLoad(opaque_color, texel, 0).rgb;
}

// ---------------------------------------------------------------- sombras

fn shadow_taps() -> u32 {
    var taps = 20u;
    if quality == 0u {
        taps = 4u;
    } else if quality == 1u {
        taps = 8u;
    } else if quality == 2u {
        taps = 12u;
    }
    return taps;
}

// Visibilidade da luz direta, em cor: 1 iluminado, 0 na sombra, e a cor da agua ou do vidro
// quando a luz atravessa um deles. `proxy` e o palpite usado fora do mapa de sombra.
fn sun_visibility(pos: vec3f, n: vec3f, nl: f32, pixel: vec2f, proxy: f32, foliage: bool) -> vec3f {
    if !shadows || frame.has_shadow == 0u {
        return vec3f(proxy);
    }
    let texel = max(frame.shadow_center_texel, 0.01);
    let dist = length(pos);
    // Desloca o ponto pela normal (mais nas faces de lado para a luz) para nao haver acne.
    let slope = sqrt(max(1.0 - nl * nl, 0.0)) / max(nl, 0.15);
    var offset = n * texel * (1.2 + slope * 0.8) * (1.0 + dist / 48.0);
    if foliage {
        offset = frame.light_direction * texel * 2.0;
    }
    let p = shadow_position(pos + offset);
    let edge = max(abs(p.x - 0.5), abs(p.y - 0.5)) * 2.0;
    let fade = smoothstep(0.82, 0.98, edge);
    if fade >= 1.0 {
        return vec3f(proxy);
    }

    let taps = shadow_taps();
    let radius = (0.6 + shadow_softness * 1.6) / max(frame.shadow_resolution, 1.0);
    let rot = ign(pixel) * TAU;
    var lit = 0.0;
    for (var i = 0u; i < taps; i++) {
        let fi = f32(i) + 0.5;
        let r = sqrt(fi / f32(taps)) * radius;
        let a = fi * 2.39996323 + rot;
        let o = vec2f(cos(a), sin(a)) * r;
        lit += textureSampleCompareLevel(shadow_map, shadow_sampler, p.xy + o, p.z);
    }
    lit /= f32(taps);

    // A luz que passa por agua, vidro colorido ou gelo leva a cor deles.
    var tint = vec3f(1.0);
    let through = textureSampleCompareLevel(shadow_map_translucent, shadow_sampler, p.xy, p.z);
    if through < 0.5 && lit > 0.0 {
        let size = vec2f(textureDimensions(shadow_color));
        let st = textureLoad(shadow_color, vec2<i32>(clamp(p.xy * size, vec2f(0.0), size - 1.0)), 0);
        if st.a > 0.995 {
            // Agua: absorve o vermelho e desenha as causticas no fundo.
            var c = mix(vec3f(0.35, 0.75, 0.85), to_linear(st.rgb) * 1.8, 0.35);
            if caustics {
                let w = pos + frame.camera_position;
                c *= 0.35 + caustic_pattern(w.xz + frame.light_direction.xz * w.y * 0.2, frame.time) * 1.6;
            }
            tint = c;
        } else {
            tint = mix(vec3f(1.0), to_linear(st.rgb) * 1.2, st.a);
        }
    }
    return mix(vec3f(lit) * tint, vec3f(proxy), fade);
}

// Causticas: a rede de luz que as ondas focam no fundo (padrao classico iterativo).
fn caustic_pattern(world_xz: vec2f, time: f32) -> f32 {
    let t = time * 0.5 + 23.0;
    let q = (fract(world_xz * 0.2) * TAU) - vec2f(250.0);
    var i = q;
    var c = 1.0;
    let inten = 0.005;
    for (var n = 0; n < 4; n++) {
        let tt = t * (1.0 - (3.5 / f32(n + 1)));
        i = q + vec2f(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        let dx = sin(i.x + tt) / inten;
        let dy = cos(i.y + tt) / inten;
        let l = length(vec2f(q.x / select(dx, 1e-4, abs(dx) < 1e-4), q.y / select(dy, 1e-4, abs(dy) < 1e-4)));
        c += 1.0 / max(l, 1e-4);
    }
    c /= 4.0;
    c = 1.17 - pow(max(c, 0.0), 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 2.0);
}

// ---------------------------------------------------------------- reflexo e especular

fn fresnel(cos_theta: f32, f0: vec3f) -> vec3f {
    return f0 + (1.0 - f0) * pow(1.0 - clamp(cos_theta, 0.0, 1.0), 5.0);
}

// GGX: o reflexo do sol numa superficie de aspereza `rough`.
fn sun_specular(n: vec3f, v: vec3f, l: vec3f, rough: f32, f0: vec3f) -> vec3f {
    let h = normalize(v + l);
    let nh = max(dot(n, h), 0.0);
    let nl = max(dot(n, l), 0.0);
    let nv = max(dot(n, v), 1e-3);
    let a = max(rough * rough, 0.002);
    let a2 = a * a;
    let d = a2 / (PI * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0));
    let k = a * 0.5;
    let g = nl / (nl * (1.0 - k) + k) * nv / (nv * (1.0 - k) + k);
    let f = fresnel(max(dot(h, v), 0.0), f0);
    return d * g * f / max(4.0 * nl * nv, 1e-3) * nl;
}

// ---------------------------------------------------------------- neblina

// A neblina na cor da atmosfera (perspectiva aerea), mais densa embaixo, no amanhecer e no fim
// de tarde e na chuva, e a borda da distancia de renderizacao que some no ceu. Devolve a cor
// ja na tela (gama, com a exposicao do ceu, para casar com ele) e quanto ela cobre.
fn overworld_fog(pos: vec3f, dist: f32) -> vec4f {
    let dir = pos / max(dist, 1e-3);
    let world_y = pos.y + frame.camera_position.y;
    let outside = frame.eye_light_smooth.y;
    let height = exp(-max(world_y - 62.0, 0.0) / 90.0);
    let mist = golden_factor() * 1.5;
    let density = 0.0011 * haze * (1.0 + mist + frame.rain * 5.0) * mix(0.4, 1.0, height);
    var amount = (1.0 - exp(-dist * density)) * mix(0.25, 1.0, outside);
    // A borda do mundo carregado some na cor do ceu, como no jogo.
    let fog_far = max(frame.fog_end, 16.0);
    let fog_near = min(max(frame.fog_start, fog_far * 0.72), fog_far - 1.0);
    let border = smoothstep(fog_near, fog_far, dist);
    // A neblina e o ceu perto do horizonte naquela direcao, com um toque do sol espalhado.
    let air = atmosphere(normalize(vec3f(dir.x, max(dir.y, 0.0) * 0.6 + 0.02, dir.z)));
    let sun_glow = pow(max(dot(dir, frame.sun_direction), 0.0), 8.0) * light_color() * 0.12;
    let cave = tonemap(vec3f(0.01, 0.012, 0.016));
    var color = mix(cave, tonemap_sky(air + sun_glow), outside);
    // Na borda, exatamente o ceu daquela direcao (sem emenda com o estagio do ceu).
    color = mix(color, tonemap_sky(atmosphere(dir)), border);
    return vec4f(color, clamp(max(amount, border), 0.0, 1.0));
}

// Aplica a neblina do lugar onde a camera esta e devolve a cor final em gama.
fn finish(hdr: vec3f, pos: vec3f) -> vec3f {
    let dist = length(pos);
    if frame.eye_in == 1u {
        // Debaixo d'agua: azul denso que engole a luz.
        let fog_c = to_linear(frame.fog_color) * 0.35 * mix(0.15, 1.0, frame.eye_light_smooth.y);
        let a = 1.0 - exp(-dist * 0.07 / water_clarity);
        return tonemap(mix(hdr, fog_c, a));
    }
    if frame.eye_in != 0u || !overworld() || frame.fog_end < 24.0 {
        // Lava, neve em po, Nether, End e efeitos (cegueira): a neblina do jogo, em gama.
        let a = smoothstep(frame.fog_start, max(frame.fog_end, frame.fog_start + 0.1), dist);
        return mix(tonemap(hdr), frame.fog_color, a);
    }
    let fog = overworld_fog(pos, dist);
    return mix(tonemap(hdr), fog.rgb, fog.a);
}

// ---------------------------------------------------------------- agua

fn water_normal(world: vec3f, dist: f32) -> vec3f {
    let t = frame.time;
    let e = 0.05;
    let h0 = wave_height(world.xz, t) + wave_height(world.xz * 3.1 + vec2f(5.3, 1.7), t * 1.7) * 0.25;
    let hx = wave_height(world.xz + vec2f(e, 0.0), t) + wave_height((world.xz + vec2f(e, 0.0)) * 3.1 + vec2f(5.3, 1.7), t * 1.7) * 0.25;
    let hz = wave_height(world.xz + vec2f(0.0, e), t) + wave_height((world.xz + vec2f(0.0, e)) * 3.1 + vec2f(5.3, 1.7), t * 1.7) * 0.25;
    let strength = 0.06 * water_waves / (1.0 + dist * 0.03) * (1.0 + frame.rain);
    return normalize(vec3f(-(hx - h0) / e * strength, 1.0, -(hz - h0) / e * strength));
}

fn water_surface(input: MaterialInput) -> vec4f {
    let pos = input.position;
    let dist = length(pos);
    let v = -pos / max(dist, 1e-3);
    let world = pos + frame.camera_position;
    let top = input.face == 1u && input.normal.y > 0.5 && frame.eye_in != 1u;
    var n = input.normal;
    if top {
        n = water_normal(world, dist);
    }
    let nv = max(dot(n, v), 0.0);
    let f = fresnel(nv, vec3f(0.02)).x;
    let water_tint = to_linear(input.tint.rgb);
    let sky_vis = pow(input.light.y, 3.0);
    let l = frame.light_direction;
    let sun = light_color();

    // O que se ve atraves da agua: o fundo, deslocado pelas ondas e absorvido pela distancia.
    let base_behind = opaque_position(input.screen);
    var screen = input.screen + n.xz * 0.04 / (1.0 + dist * 0.08);
    let shifted_behind = opaque_position(screen);
    if length(shifted_behind) < dist {
        screen = input.screen;
    }
    var behind = to_linear(opaque_rgb(screen));
    let thickness = clamp(length(select(shifted_behind, base_behind, length(shifted_behind) < dist)) - dist, 0.0, 256.0);
    let absorb = vec3f(0.45, 0.11, 0.07) / water_clarity;
    let trans = exp(-absorb * thickness);
    // A luz espalhada dentro da agua tem a cor da agua do bioma.
    let scatter_light = sky_ambient(vec3f(0.0, 1.0, 0.0)) * ambient_strength * sky_vis + sun * 0.15 * max(l.y, 0.0);
    let deep = water_tint * vec3f(0.18, 0.32, 0.36) * scatter_light;
    let deep_display = to_linear(tonemap(deep));
    var under = behind * trans + deep_display * (1.0 - trans);

    // O que a agua reflete: o espelho do mundo (se ligado) sobre a atmosfera.
    let r = reflect(-v, n);
    var refl = to_linear(tonemap_sky(atmosphere(r) * mix(0.15, 1.0, sky_vis)));
    if water_reflections && frame.has_reflection == 1u && top {
        let ruv = clamp(input.screen + n.xz * 0.05, vec2f(0.001), vec2f(0.999));
        let mirror = textureSampleLevel(reflection, reflection_sampler, ruv, 0.0);
        refl = mix(refl, to_linear(mirror.rgb), mirror.a * 0.85);
    }
    // O brilho do sol nas ondas.
    let vis = sun_visibility(pos, input.normal, max(dot(input.normal, l), 0.0), input.screen * frame.resolution, sky_vis, false);
    let glint = sun_specular(n, v, l, 0.06, vec3f(0.02)) * sun * vis;
    var color = mix(under, refl, f) + to_linear(tonemap(glint)) * 1.5;

    if !top {
        // Visto de baixo ou pelas laterais: mistura simples.
        let lit = water_tint * 0.3 * (scatter_light + sun * vis * 0.3);
        return vec4f(finish(lit, pos), 0.55);
    }
    // A neblina da distancia por cima da agua.
    if overworld() && frame.eye_in == 0u {
        let fog = overworld_fog(pos, dist);
        color = mix(color, to_linear(fog.rgb), fog.a);
    }
    return vec4f(to_gamma(color), 1.0);
}

// ---------------------------------------------------------------- o terreno

fn material(input: MaterialInput) -> Surface {
    if input.fluid == 1u && input.material == MAT_WATER {
        return Surface(water_surface(input));
    }

    let pos = input.position;
    let dist = length(pos);
    let v = -pos / max(dist, 1e-3);
    let pixel = input.screen * frame.resolution;
    let m = input.material;
    let foliage = is_foliage(m);
    let geo_n = input.normal;

    // Cor da superficie em linear, sem a sombra de face vanilla.
    var tint = input.tint.rgb;
    if !foliage && input.fluid == 0u {
        tint /= mix(1.0, vanilla_face_shade(geo_n), 0.8);
    }
    var albedo = to_linear(input.albedo.rgb * tint);
    var alpha = input.albedo.a * input.tint.a;

    // ------------------------------------------------ PBR: normal, lisura, F0, emissao
    var n = geo_n;
    var smooth_s = 0.0;
    var f0 = vec3f(0.04);
    var ao = 1.0;
    var emission = 0.0;
    var porosity = 0.5;
    let t = input.tangent.xyz;
    let b = cross(geo_n, t) * input.tangent.w;
    if block_pbr_maps() {
        let nm = block_normal_map(input.uv);
        let xy = nm.xy * 2.0 - 1.0;
        let z = sqrt(max(1.0 - dot(xy, xy), 0.0));
        n = normalize(t * xy.x + b * xy.y + geo_n * z);
        ao = nm.z;
        let sp = block_specular_map(input.uv);
        smooth_s = sp.r;
        if sp.g * 255.0 >= 229.5 {
            f0 = albedo;
        } else {
            f0 = vec3f(max(sp.g, 0.02));
        }
        porosity = select(sp.b * 255.0 / 64.0, 0.0, sp.b * 255.0 > 64.5);
        emission = select(sp.a, 0.0, sp.a > 0.998);
    } else {
        if auto_bump && !input.translucent && input.fluid == 0u && !foliage && dist < 40.0 {
            // Relevo gerado da propria textura: claro = alto, escuro = baixo.
            let ts = block_texture_size();
            let lod = block_texture_lod();
            let du = vec2f(1.0 / ts.x, 0.0);
            let dv = vec2f(0.0, 1.0 / ts.y);
            let hc = luma(input.albedo.rgb);
            let hu = luma(block_texture_level(input.uv + du, lod).rgb);
            let hv = luma(block_texture_level(input.uv + dv, lod).rgb);
            let k = 0.9 * (1.0 - smoothstep(16.0, 40.0, dist));
            n = normalize(geo_n - (t * (hu - hc) + b * (hv - hc)) * k);
        }
        // Valores tipicos por tipo de bloco.
        if m == MAT_METAL {
            smooth_s = 0.72;
            f0 = mix(vec3f(0.5), albedo * 1.4, 0.7);
        } else if m == MAT_POLISHED {
            smooth_s = 0.42;
        } else if m == MAT_GLASS {
            smooth_s = 0.96;
        } else if m == MAT_ICE {
            smooth_s = 0.9;
            f0 = vec3f(0.03);
        }
        if foliage {
            smooth_s = 0.25;
        }
    }

    // ------------------------------------------------ chuva: chao molhado e pocas
    let sky_light = input.light.y;
    if wet_reflections && overworld() && frame.wetness > 0.01 && frame.precipitation == 1u && !input.translucent {
        let exposed = smoothstep(0.88, 0.98, sky_light);
        let up = smoothstep(0.6, 0.95, n.y);
        let world = pos + frame.camera_position;
        let puddle = smoothstep(0.45, 0.7, value_noise(world.xz * 0.35) * 0.7 + value_noise(world.xz * 1.3) * 0.3);
        let wet = frame.wetness * exposed;
        let flood = wet * up * puddle;
        albedo *= 1.0 - wet * 0.35 * (0.5 + porosity);
        smooth_s = mix(smooth_s, 0.95, max(flood, wet * 0.55));
        n = normalize(mix(n, geo_n, flood));
        f0 = max(f0, vec3f(0.02 * wet));
    }

    let rough = max(1.0 - smooth_s, 0.02);
    let l = frame.light_direction;
    let nl_geo = dot(geo_n, l);

    // ------------------------------------------------ luz
    var hdr = vec3f(0.0);
    if overworld() {
        let sun = light_color();
        let nl = max(dot(n, l), 0.0);
        var diffuse = nl;
        if foliage {
            // Folhas e plantas sao finas: iluminadas dos dois lados.
            diffuse = 0.35 + 0.65 * abs(dot(n, l));
        }
        // Fora do mapa de sombra: o ceu aberto e um bom palpite de sol.
        let proxy = smoothstep(0.86, 0.98, sky_light);
        var vis = vec3f(0.0);
        if (foliage || nl_geo > 0.0) && luma(sun) > 1e-4 {
            vis = sun_visibility(pos, geo_n, max(abs(nl_geo), 0.0), pixel, proxy, foliage);
        }
        var direct = sun * diffuse * vis;
        if foliage {
            // Luz que atravessa a folha quando se olha contra o sol.
            let back = pow(max(dot(-v, l), 0.0), 5.0) * foliage_glow * 2.0;
            direct += sun * vis * back * albedo * 2.5;
        }
        let ambient = sky_ambient(n) * ambient_strength * pow(sky_light, 2.6) * ao;
        hdr = albedo * (direct + ambient);
        // Reflexo especular do sol e do ceu.
        if smooth_s > 0.05 {
            hdr += sun_specular(n, v, l, rough, f0) * sun * vis;
            let r = reflect(-v, n);
            let env = atmosphere(r) * pow(sky_light, 3.0);
            hdr += env * fresnel(max(dot(n, v), 0.0), f0) * smooth_s * smooth_s * 0.9;
        }
    } else {
        // Nether, End: a luz do jogo, em linear, um pouco mais contrastada.
        hdr = albedo * to_linear(input.lightmap) * 1.4;
    }

    // Tochas e outras luzes de bloco, com a cor do bloco que as emite.
    let bl = input.light.x;
    var block_falloff = pow(bl, 3.2) * 1.3 + pow(bl, 12.0) * 2.2;
    var block_col = TORCH_COLOR;
    if colored_lights {
        let c = block_light_color(pos);
        block_col = mix(TORCH_COLOR, c.rgb, clamp(c.a * 1.5, 0.0, 1.0));
    }
    // De dia, a luz das tochas some no sol.
    let day_wash = select(1.0, 1.0 - day_factor() * smoothstep(0.7, 1.0, sky_light) * 0.7, overworld());
    hdr += albedo * block_col * block_falloff * torch_strength * day_wash * ao;
    hdr += albedo * input.colored_light * 2.0 * torch_strength;

    // Um minimo de luz para cavernas nunca ficarem 100% pretas (segue o Brilho do jogo).
    hdr += albedo * (0.0025 + frame.brightness * 0.012) * ao;

    // Blocos que emitem luz: as partes claras da textura brilham de verdade.
    let emissive = max(input.emission, emission);
    if emissive > 0.0 {
        let bright = smoothstep(0.35, 0.85, max(input.albedo.r, max(input.albedo.g, input.albedo.b)));
        let e = select(bright * input.emission, emission, emission > 0.0);
        hdr += albedo * e * 6.0;
    }
    if input.fluid == 2u {
        hdr = albedo * 5.0;
    }

    // ------------------------------------------------ vidro e gelo
    if input.translucent && input.fluid == 0u {
        let nv = max(dot(n, v), 0.0);
        let f = fresnel(nv, vec3f(0.04)).x * select(0.5, 1.0, m == MAT_GLASS || m == MAT_ICE);
        let r = reflect(-v, n);
        let refl = atmosphere(r) * mix(0.1, 1.0, pow(sky_light, 3.0));
        let new_alpha = alpha + f * (1.0 - alpha);
        hdr = (hdr * alpha + refl * f) / max(new_alpha, 1e-3);
        alpha = new_alpha;
    }

    return Surface(vec4f(finish(hdr, pos), alpha));
}
