package com.ppss55

/**
 * Known compatibility, copied from upstream KytyPS5-Android docs/android/STATUS.md (2026-10-23).
 * Those results were measured by upstream on a OnePlus Pad 3 (Adreno 830), NOT by PPSS55 on the
 * REDMAGIC 9S Pro; every other title is "não testado".
 */
object Compatibility {
    enum class Status(val label: String) {
        PLAYABLE("Jogável*"),
        NEARLY("Quase jogável*"),
        SLOW("Roda, lento*"),
        BOOTS("Inicia, muito lento*"),
        UNKNOWN("Não testado"),
    }

    private val KNOWN = mapOf(
        "PPSA21703" to Status.PLAYABLE, // Vampire Survivors
        "PPSA28855" to Status.NEARLY, // Totally Reliable Delivery Service
        "PPSA21567" to Status.SLOW, // Astro Bot
        "PPSA14708" to Status.BOOTS, // Commandos: Origins
    )

    fun of(titleId: String?): Status = titleId?.let { KNOWN[it.uppercase()] } ?: Status.UNKNOWN

    const val FOOTNOTE = "* resultado medido pelo projeto KytyPS5-Android num Adreno 830, ainda não verificado no REDMAGIC 9S Pro."
}
