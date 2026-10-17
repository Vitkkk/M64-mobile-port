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

    /** Recompila todas as funções da ROM para código nativo, na memória. */
    @JvmStatic external fun recompile(romPath: String, symsPath: String): NativeResult

    @JvmStatic external fun setController(port: Int, buttons: Int, stickX: Int, stickY: Int)
}
