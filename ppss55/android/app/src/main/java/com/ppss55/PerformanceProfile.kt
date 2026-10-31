package com.ppss55

import com.kytyps5.android.LauncherSettings
import com.kytyps5.android.PresentMode
import com.kytyps5.android.RenderScale
import com.kytyps5.android.Resolution

/**
 * One-tap presets for the launcher-wide settings. Each one only sets existing options (nothing hidden);
 * every value can still be changed afterwards, which turns the profile into "Personalizado".
 * The choices follow what each option costs, not measurements on every phone.
 */
enum class PerformanceProfile(val label: String, val description: String) {
    WEAK(
        "Celular fraco",
        "720p, resolução interna 50%, áudio 200 ms, espera de shader 4 ms, sem turbo da GPU (menos calor).",
    ),
    BALANCED(
        "Equilibrado (padrão)",
        "720p, resolução interna 75%, áudio 120 ms, espera de shader 8 ms, turbo da GPU.",
    ),
    QUALITY(
        "Qualidade",
        "1080p, resolução interna 100%, áudio 120 ms, espera de shader 8 ms, turbo da GPU. Para celulares topo de linha.",
    ),
    COMPATIBILITY(
        "Compatibilidade (imagem piscando)",
        "Desliga as três otimizações suspeitas do pisca-pisca: shaders síncronos (nenhum desenho é pulado, mas engasga ao compilar), sem fusão de limpezas e Turnip sem blocos. Mais lento.",
    ),
    CUSTOM("Personalizado", "Valores escolhidos à mão.");

    fun apply(s: LauncherSettings) {
        when (this) {
            WEAK -> set(s, Resolution.HD_720, RenderScale.HALF, audio = 200, turbo = false, grace = 4)
            BALANCED -> set(s, Resolution.HD_720, RenderScale.THREE_QUARTER, audio = 120, turbo = true, grace = 8)
            QUALITY -> set(s, Resolution.FULL_HD_1080, RenderScale.NATIVE, audio = 120, turbo = true, grace = 8)
            COMPATIBILITY -> {
                set(s, Resolution.HD_720, RenderScale.NATIVE, audio = 120, turbo = true, grace = 0)
                s.asyncShaders = false
                s.rpOpsOff = true
                s.turnipSysmem = true
            }
            CUSTOM -> Unit
        }
        s.profile = this
    }

    private fun set(s: LauncherSettings, res: Resolution, scale: RenderScale, audio: Int, turbo: Boolean, grace: Int) {
        s.pipelineGraceMs = grace
        s.coreLog = false
        s.resolution = res
        s.renderScale = scale
        s.audioLatencyMs = audio
        s.gpuTurbo = turbo
        s.presentMode = PresentMode.FIFO
        s.shaderDiskCache = true
        s.asyncShaders = true
        s.rpOpsOff = false
        s.turnipSysmem = false
    }

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
