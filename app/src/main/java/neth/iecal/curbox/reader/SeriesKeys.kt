package neth.iecal.curbox.reader

import java.util.Locale

/** Where a series is in the owner's list, as in Kenmei. */
enum class SeriesStatus(val id: String) {
    READING("reading"),
    PLAN_TO_READ("plan_to_read"),
    COMPLETED("completed"),
    ON_HOLD("on_hold"),
    DROPPED("dropped");

    companion object {
        fun of(id: String?): SeriesStatus? {
            val clean = id?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_') ?: return null
            return values().find { it.id == clean }
                ?: when (clean) {
                    "plan_to_read", "planning", "plan", "planned" -> PLAN_TO_READ
                    "onhold", "paused", "hold" -> ON_HOLD
                    "finished", "complete", "done" -> COMPLETED
                    "current", "watching" -> READING
                    else -> null
                }
        }
    }
}

object SeriesKeys {
    private val APOSTROPHES = Regex("""['’`]""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The same key for a title typed by people ("The Novel's Extra") and the same title in a page
     * address ("the-novels-extra"), so imported and detected series meet.
     */
    fun of(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "")
            .replace(NOT_WORD, " ")
            .trim()
}
