package neth.iecal.curbox.reader

/**
 * Guesses which series in the list may be the same one under slightly different names, for the
 * owner to confirm. Series proven the same by MangaDex are merged by [ReleaseChecker] instead.
 */
object SeriesMatching {
    private val SMALL_WORDS = setOf("the", "a", "an", "of", "in", "to", "and", "s", "my", "is", "for", "with", "on")

    data class Pair(val first: SeriesEntity, val second: SeriesEntity)

    /** Name words that matter, so "The Greatest Estate Developer" and "Greatest Estate Developer" meet. */
    fun coreWords(key: String): List<String> = key.split(' ').filter { it.isNotEmpty() && it !in SMALL_WORDS }

    fun isSimilar(keyA: String, keyB: String): Boolean {
        val a = coreWords(keyA)
        val b = coreWords(keyB)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.size <= b.size) a to b else b to a
        // One name inside the other, such as a site adding a subtitle. Two words at least, so a
        // single common word does not pair unrelated series.
        return short.size >= 2 && long.windowed(short.size).any { it == short }
    }

    /** Pairs to ask about, skipping pairs already answered and series linked to different MangaDex series. */
    fun possibleDuplicates(series: List<SeriesEntity>, notDuplicates: Set<String>): List<Pair> {
        val pairs = mutableListOf<Pair>()
        for (i in series.indices) {
            for (j in i + 1 until series.size) {
                val a = series[i]
                val b = series[j]
                if (a.status == SeriesStatus.IGNORED.id || b.status == SeriesStatus.IGNORED.id) continue
                if (a.mangadexId != null && b.mangadexId != null && a.mangadexId != b.mangadexId) continue
                if (pairId(a.key, b.key) in notDuplicates) continue
                if (isSimilar(a.key, b.key)) pairs += Pair(a, b)
            }
        }
        return pairs
    }

    fun pairId(keyA: String, keyB: String): String = if (keyA <= keyB) "$keyA|$keyB" else "$keyB|$keyA"
}
