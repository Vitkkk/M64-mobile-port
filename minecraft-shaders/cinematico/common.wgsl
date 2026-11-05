// Cinematico: codigo compartilhado por todos os estagios (ceu, terreno, vertices, clima, pos).
// So usa `frame`, que todo estagio tem.
//
// Pipeline de cor: a tela do Horizon e de 8 bits em espaco gama e corta no branco. Por isso o
// terreno e o ceu calculam a luz em espaco linear (HDR), aplicam a exposicao e uma curva de
// filme (ACES) e devolvem a cor ja em gama. Os passes de pos trabalham sobre essa imagem.

override exposure: f32 = 1.0;
override auto_exposure: bool = true;
override sun_strength: f32 = 1.0;

const PI: f32 = 3.14159265;
const TAU: f32 = 6.28318531;

fn luma(c: vec3f) -> f32 {
    return dot(c, vec3f(0.2126, 0.7152, 0.0722));
}

fn to_linear(c: vec3f) -> vec3f {
    return pow(max(c, vec3f(0.0)), vec3f(2.2));
}

fn to_gamma(c: vec3f) -> vec3f {
    return pow(max(c, vec3f(0.0)), vec3f(1.0 / 2.2));
}

fn hash12(p: vec2f) -> f32 {
    var p3 = fract(vec3f(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

fn value_noise(p: vec2f) -> f32 {
    let i = floor(p);
    let f = fract(p);
    let u = f * f * (3.0 - 2.0 * f);
    let a = hash12(i);
    let b = hash12(i + vec2f(1.0, 0.0));
    let c = hash12(i + vec2f(0.0, 1.0));
    let d = hash12(i + vec2f(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Ruido de gradiente intercalado (Jimenez): espalha bem as amostras sem padrao visivel.
fn ign(pixel: vec2f) -> f32 {
    return fract(52.9829189 * fract(dot(pixel, vec2f(0.06711056, 0.00583715))));
}

fn overworld() -> bool {
    return frame.dimension == 0;
}

// 1 com o sol acima do horizonte, 0 de noite, com uma transicao suave no crepusculo.
fn day_factor() -> f32 {
    return smoothstep(-0.06, 0.12, frame.sun_direction.y);
}

// 1 com o sol baixo (hora dourada), 0 com ele alto ou bem abaixo do horizonte.
fn golden_factor() -> f32 {
    let y = frame.sun_direction.y;
    return smoothstep(-0.08, 0.02, y) * (1.0 - smoothstep(0.05, 0.4, y));
}

// ---------------------------------------------------------------- atmosfera
// Um modelo analitico barato de espalhamento Rayleigh + Mie: azul profundo no zenite, horizonte
// claro, laranja e rosa no por do sol e escuridao azulada de noite, sem raymarching.

const SKY_RAYLEIGH: vec3f = vec3f(0.39, 0.57, 1.0);

fn zenith_density(y: f32) -> f32 {
    return 0.7 / pow(max(y + 0.04, 0.0035), 0.75);
}

fn sky_absorption(density: f32) -> vec3f {
    return exp2(SKY_RAYLEIGH * -density) * 2.0;
}

// Cor da luz do sol depois de atravessar a atmosfera (mais laranja perto do horizonte).
fn sun_transmittance(sun_y: f32) -> vec3f {
    return sky_absorption(zenith_density(sun_y + 0.1));
}

// A cor da luz do astro normalizada (1 no canal mais forte): branca no alto, laranja no horizonte.
fn sun_tint(sun_y: f32) -> vec3f {
    let t = sun_transmittance(sun_y);
    return t / max(t.r, max(t.g, max(t.b, 1e-4)));
}

// O ceu numa direcao `d` iluminado por um astro na direcao `s`: gradiente zenite-horizonte,
// horizonte laranja e zenite arroxeado com o astro baixo, e o halo de Mie em volta dele.
fn scatter(d: vec3f, s: vec3f) -> vec3f {
    let y = max(d.y, 0.0);
    let mu = max(dot(d, s), 0.0);
    let intensity = pow(smoothstep(-0.2, 0.35, s.y), 1.3);
    let low = (1.0 - smoothstep(0.0, 0.4, s.y)) * smoothstep(-0.22, -0.02, s.y);
    let grad = pow(1.0 - y, 4.0);
    var base = mix(vec3f(0.08, 0.22, 0.62), vec3f(0.5, 0.66, 0.92), grad);
    let tn = sun_tint(s.y);
    let warm = pow(tn, vec3f(2.2)) * (0.4 + 0.8 * mu * mu);
    base = mix(base, warm, low * grad * (0.4 + 0.6 * mu));
    base *= mix(vec3f(1.0), vec3f(0.62, 0.55, 0.85), low * (1.0 - grad));
    let mie = tn * tn * (pow(mu, 8.0) * 0.2 + pow(mu, 64.0) * 0.5) * (1.0 + low);
    let col = (base + mie) * intensity * 1.1;
    // Abaixo do horizonte o ceu escurece como o chao distante.
    return col * mix(1.0, 0.35, smoothstep(0.0, -0.25, d.y));
}

// O ceu completo (HDR, linear) na direcao `d`: dia, crepusculo, noite e chuva.
fn atmosphere(d: vec3f) -> vec3f {
    let sun = scatter(d, frame.sun_direction);
    // A noite: a lua ilumina um ceu azul-escuro e sempre ha um pouco de luz das estrelas.
    let moon_up = smoothstep(-0.1, 0.15, frame.moon_direction.y);
    let moon = scatter(d, frame.moon_direction) * vec3f(0.5, 0.65, 1.0) * 0.025 * moon_up;
    let floor_light = vec3f(0.003, 0.005, 0.011) * (1.0 - day_factor());
    var c = sun + moon + floor_light;
    // Chuva e tempestade: um ceu nublado cinza, mais escuro com trovoada.
    let grey = vec3f(luma(c)) * vec3f(0.92, 0.96, 1.0);
    c = mix(c, grey * (0.55 - frame.thunder * 0.25), frame.rain * 0.85);
    return c;
}

// A luz direta: o sol de dia, a lua de noite (a mesma direcao que projeta as sombras).
fn light_color() -> vec3f {
    let s = frame.sun_direction;
    let sun = sun_transmittance(s.y) * 1.25 * sun_strength * smoothstep(-0.03, 0.06, s.y);
    let moon = vec3f(0.42, 0.52, 0.85) * 0.1 * smoothstep(-0.03, 0.1, frame.moon_direction.y);
    let c = select(moon, sun, s.y > 0.0);
    return c * (1.0 - frame.rain * 0.88);
}

// A luz que o ceu inteiro joga numa superficie com normal `n` (hemisferio).
fn sky_ambient(n: vec3f) -> vec3f {
    let s = frame.sun_direction;
    let up = atmosphere(vec3f(0.0, 1.0, 0.0));
    let side = atmosphere(normalize(vec3f(-s.z, 0.12, s.x)));
    let toward_sun = atmosphere(normalize(vec3f(s.x, 0.1, s.z)));
    let horizon = mix(side, toward_sun, 0.5 + 0.5 * dot(normalize(n.xz + vec2f(1e-4)), normalize(s.xz + vec2f(1e-4))) * length(n.xz));
    var dome = mix(horizon, up, clamp(n.y * 0.5 + 0.5, 0.0, 1.0));
    // A luz do ceu chega de todo lado e se mistura: bem menos saturada que o ceu visto.
    dome = mix(vec3f(luma(dome)), dome, 0.55);
    // A luz que volta do chao ilumina um pouco as faces de baixo.
    let bounce = light_color() * vec3f(0.35, 0.33, 0.28) * 0.08 * max(s.y, 0.0);
    return dome * 0.85 + bounce * clamp(-n.y * 0.5 + 0.5, 0.0, 1.0);
}

// ---------------------------------------------------------------- exposicao e curva de filme

// Quanto a camera abre: mede a luz do ceu e do sol e compensa em parte (como uma camera de
// verdade), e abre mais em cavernas. A noite continua escura, mas legivel.
fn eye_exposure() -> f32 {
    var e = 1.0;
    if auto_exposure && overworld() {
        let outside = frame.eye_light_smooth.y;
        let l = light_color();
        let scene = luma(l) * max(frame.light_direction.y, 0.2) * 0.6 + luma(atmosphere(vec3f(0.0, 1.0, 0.0)));
        let open_air = clamp(sqrt(1.75 / max(scene, 1e-3)), 1.0, 2.6);
        e = mix(2.4, open_air, outside);
    }
    e *= 1.0 + frame.night_vision * 2.0;
    return e * exposure;
}

// O ceu fica fora da compensacao total: com a camera aberta para o chao no por do sol, o ceu
// estouraria em branco. Assim ele mantem as cores.
fn sky_exposure() -> f32 {
    return mix(exposure, eye_exposure(), 0.4);
}

// ACES aproximado (Narkowicz): ombro suave nos brilhos, pretos profundos.
fn aces(x: vec3f) -> vec3f {
    let a = 2.51;
    let b = 0.03;
    let c = 2.43;
    let d = 0.59;
    let e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), vec3f(0.0), vec3f(1.0));
}

// Luz HDR linear -> cor final em gama, pronta para a tela.
fn tonemap(hdr: vec3f) -> vec3f {
    return to_gamma(aces(hdr * eye_exposure() * 0.9));
}

fn tonemap_sky(hdr: vec3f) -> vec3f {
    return to_gamma(aces(hdr * sky_exposure() * 0.9));
}
