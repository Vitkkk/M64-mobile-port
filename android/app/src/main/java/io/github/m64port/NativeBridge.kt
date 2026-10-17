package io.github.m64port

/** Resultado de uma chamada ao núcleo nativo. */
class NativeResult(val ok: Boolean, val message: String, val versionId: String)

object NativeBridge {
    init {
        System.loadLibrary("m64port")
    }

    /**
     * Valida a ROM pelo SHA-1. Se for suportada, [rom] é sobrescrito com a
     * versão normalizada (.z64).
     */
    @JvmStatic external fun validateRom(rom: ByteArray): NativeResult

    /**
     * Recompila todas as funções da ROM para código nativo, na memória, com
     * a libultra ligada ao runtime. Só no processo do jogo.
     */
    @JvmStatic external fun prepareGame(romPath: String, symsPath: String): NativeResult

    /**
     * Roda o jogo preparado, desenhando com o RT64 em [surface]. Bloqueia até
     * [stopGame]. Devolve um erro ou null.
     */
    @JvmStatic external fun runGame(dataDir: String, surface: android.view.Surface): String?

    @JvmStatic external fun stopGame()

    /** Quadros (VI) desde o início do jogo. */
    @JvmStatic external fun frameCount(): Long

    @JvmStatic external fun setController(port: Int, buttons: Int, stickX: Int, stickY: Int)
}
