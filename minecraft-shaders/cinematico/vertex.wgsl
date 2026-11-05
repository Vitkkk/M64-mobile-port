// Vento nas folhas e plantas, e ondas na superficie da agua. Este arquivo tambem entra no
// modulo do terreno (material), por isso os nomes daqui nao podem repetir os de material.wgsl.
override waving: bool = true;
override wind_strength: f32 = 1.0;
override water_waves: f32 = 1.0;

const MAT_LEAVES: u32 = 1u;
const MAT_PLANT: u32 = 2u;
const MAT_PLANT_TOP: u32 = 3u;
const MAT_VINE: u32 = 4u;
const MAT_PLANT_LOW: u32 = 5u;
const MAT_WATER: u32 = 20u;
const MAT_LAVA: u32 = 21u;
const MAT_GLASS: u32 = 30u;
const MAT_ICE: u32 = 31u;
const MAT_METAL: u32 = 40u;
const MAT_POLISHED: u32 = 41u;

// A altura das ondas num ponto do mundo (em blocos). So depende da posicao, entao cantos
// vizinhos se movem juntos e a superficie nao abre frestas.
fn wave_height(w: vec2f, t: f32) -> f32 {
    var h = sin(dot(w, vec2f(0.8, 0.6)) * 1.3 + t * 1.6) * 0.5;
    h += sin(dot(w, vec2f(-0.5, 0.85)) * 2.1 + t * 2.3) * 0.3;
    h += sin(dot(w, vec2f(0.95, -0.3)) * 3.7 + t * 3.1) * 0.2;
    return h;
}

fn wind_offset(w: vec3f, t: f32) -> vec3f {
    let gust = 0.55 + 0.45 * sin(t * 0.37 + w.x * 0.021 + w.z * 0.017);
    let storm = 1.0 + frame.rain * 1.2 + frame.thunder * 1.3;
    let s = wind_strength * gust * storm;
    let dir = normalize(vec2f(1.0, 0.35));
    let primary = sin(t * 1.9 + dot(w.xz, dir) * 0.9 + w.y * 0.3);
    let flutter = sin(t * 4.7 + w.x * 2.3 + w.z * 1.7) * 0.35;
    let sway = (primary + flutter) * s;
    return vec3f(dir.x * sway, 0.0, dir.y * sway);
}

fn vertex(input: VertexInput) -> vec3<f32> {
    var p = input.position;
    let w = p + frame.camera_position;
    let t = frame.time;
    let m = input.material;

    if m == MAT_WATER && input.fluid == 1u && input.face == 1u {
        // Abaixa um pouco a superficie e a faz ondular.
        p.y += (wave_height(w.xz, t) - 1.0) * 0.022 * water_waves;
        return p;
    }
    if !waving || frame.dimension != 0 {
        return p;
    }
    // Plantas debaixo de teto mexem menos.
    let open = 0.35 + 0.65 * smoothstep(0.3, 0.9, input.light.y);
    if m == MAT_LEAVES {
        let a = vec3f(
            sin(t * 1.7 + w.x * 1.3 + w.y * 0.7),
            sin(t * 2.3 + w.z * 1.1 + w.x * 0.5) * 0.6,
            sin(t * 1.5 + w.z * 1.7 + w.y * 0.4),
        );
        p += (a * 0.035 + wind_offset(w, t) * 0.03) * wind_strength * open;
    } else if m == MAT_PLANT || m == MAT_PLANT_LOW || m == MAT_PLANT_TOP {
        // uv.y vai de 0 no alto do sprite a 1 embaixo: a base fica presa no chao. Nas plantas
        // de dois blocos a metade de baixo dobra ate 0.5 e a de cima continua de 0.5 a 1,
        // assim as duas metades se encontram sem quebrar.
        var bend = 1.0 - input.uv.y;
        if m == MAT_PLANT_LOW {
            bend *= 0.5;
        } else if m == MAT_PLANT_TOP {
            bend = 0.5 + bend * 0.5;
        }
        p += wind_offset(w, t) * 0.12 * bend * open;
    } else if m == MAT_VINE {
        p += wind_offset(w, t) * 0.03 * open;
    }
    return p;
}
