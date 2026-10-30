package neth.iecal.curbox.reader

import java.net.URLDecoder
import java.util.Locale

/**
 * Finds the series and chapter in a page address, such as
 * `asuracomic.net/series/solo-leveling-abc123/chapter/179` → "solo leveling", 179.
 *
 * Works on any site, because reading sites change their addresses often. The series is matched
 * by its cleaned name, not the site, so the same series read on two sites stays one entry.
 */
object ChapterParser {

    data class Chapter(
        /** Lowercase words of the series name; the same series on another site gets the same key. */
        val seriesKey: String,
        val seriesName: String,
        val number: Double,
        val site: String,
        /** The address to open the chapter again. */
        val url: String
    )

    private const val NUMBER = """\d{1,5}(?:[.-]\d{1,2})?"""
    // "chapter-179", "ch.12", "episode_3" inside one path part, maybe after the series name.
    private val CHAPTER_IN_PART = Regex("""(?:^|[-_.])(?:chapters?|chap|ch|episodes?|ep)[-_.]?($NUMBER)(?=$|[-_.])""")
    // "chapter" and "179" as two path parts.
    private val CHAPTER_WORD = Regex("""^(?:chapters?|chap|ch|episodes?|ep)$""")
    private val NUMBER_PART = Regex("""^$NUMBER$""")
    private val QUERY_CHAPTER = Regex("""(?:^|&)(?:chapter|chap|ch|episode_no|episode|ep)=(\d{1,5}(?:\.\d{1,2})?)(?=&|$)""")
    private val WORD_SPLIT = Regex("""[-_.+ ]+""")
    private val LETTERS = Regex("""[a-z]""")
    private val DIGITS = Regex("""\d""")

    private val SEASON_WORDS = setOf("season", "s", "vol", "volume", "part", "arc", "book")

    // Path parts that name the kind of page, not the series.
    private val GENERIC_PARTS = setOf(
        "series", "serie", "manga", "mangas", "manhwa", "manhua", "comic", "comics", "webtoon",
        "webtoons", "read", "reader", "reading", "title", "titles", "book", "books", "novel",
        "novels", "viewer", "view", "chapter", "chapters", "online", "en", "eng", "m", "amp", "list"
    )

    fun parse(urlIdentifier: String): Chapter? {
        val original = urlIdentifier.trim().removePrefix("https://").removePrefix("http://")
        if (original.isEmpty()) return null
        val lower = original.lowercase(Locale.ROOT)
        val host = lower.substringBefore('/').substringBefore('?').substringBefore('#')
        val site = host.removePrefix("www.").removePrefix("m.")
        if (!site.contains('.')) return null
        val rest = lower.substring(host.length)
        val path = rest.substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "").substringBefore('#')
        val parts = path.split('/').map(::decode).filter(String::isNotBlank)

        val found = locate(parts, query) ?: return null
        val chapterNumber = found.number
        val chapterIndex = found.index
        val seriesInPart = found.seriesInPart

        val words = seriesInPart?.let(::seriesWords)?.takeIf { it.isNotEmpty() && !isOnlySeasonWords(it) }
            ?: (chapterIndex - 1 downTo 0).asSequence()
                .map { parts[it] }
                .filter { it !in GENERIC_PARTS }
                .map(::seriesWords)
                .firstOrNull { it.isNotEmpty() && !isOnlySeasonWords(it) }
            ?: return null

        return Chapter(
            seriesKey = SeriesKeys.of(words.joinToString(" ")),
            seriesName = words.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } },
            number = chapterNumber,
            site = site,
            url = "https://$original"
        )
    }

    /**
     * The series page for a chapter address, where sites list every chapter, such as
     * `https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9` for chapter 144 of it.
     * Null when the address has no separate series level (the chapter is in the query or sits
     * in the same path part as the series name).
     */
    fun seriesPage(urlIdentifier: String): String? {
        val original = urlIdentifier.trim().removePrefix("https://").removePrefix("http://")
        val host = original.substringBefore('/').substringBefore('?').substringBefore('#')
        val rest = original.substring(host.length)
        val rawParts = rest.substringBefore('?').substringBefore('#').split('/').filter(String::isNotBlank)
        val query = rest.lowercase(Locale.ROOT).substringAfter('?', "").substringBefore('#')
        val found = locate(rawParts.map { decode(it.lowercase(Locale.ROOT)) }, query) ?: return null
        if (found.seriesInPart != null || found.index <= 0 || found.index >= rawParts.size) return null
        return "https://" + host + "/" + rawParts.take(found.index).joinToString("/")
    }

    private data class Found(val number: Double, val index: Int, val seriesInPart: String?)

    // Finds the chapter number and the path part it is in, looking from the end of the path.
    private fun locate(parts: List<String>, query: String): Found? {
        for (i in parts.indices.reversed()) {
            val part = parts[i]
            if (CHAPTER_WORD.matches(part) && i + 1 < parts.size && NUMBER_PART.matches(parts[i + 1])) {
                return toNumber(parts[i + 1])?.let { Found(it, i, null) }
            }
            val match = CHAPTER_IN_PART.find(part) ?: continue
            val number = toNumber(match.groupValues[1]) ?: return null
            return Found(number, i, part.substring(0, match.range.first).takeIf { it.isNotBlank() })
        }
        val match = QUERY_CHAPTER.find(query) ?: return null
        return toNumber(match.groupValues[1])?.let { Found(it, parts.size, null) }
    }

    /** "179" for whole chapters, "179.5" otherwise. */
    fun format(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    // "season-3-ep-120" names a part of the series; the series name is in an earlier path part.
    private fun isOnlySeasonWords(words: List<String>): Boolean = words.all { it in SEASON_WORDS }

    // Splits a path part into name words and drops ids such as "abc123de" or "12345".
    private fun seriesWords(part: String): List<String> {
        val words = part.split(WORD_SPLIT).filter(String::isNotBlank).toMutableList()
        while (words.isNotEmpty() && isIdWord(words.last())) words.removeAt(words.lastIndex)
        while (words.isNotEmpty() && isIdWord(words.first())) words.removeAt(0)
        if (words.none { LETTERS.containsMatchIn(it) }) return emptyList()
        return words
    }

    private fun isIdWord(word: String): Boolean =
        word.all(Char::isDigit) ||
            (word.length >= 5 && LETTERS.containsMatchIn(word) && DIGITS.containsMatchIn(word))

    private fun toNumber(text: String): Double? = text.replace('-', '.').toDoubleOrNull()

    private fun decode(part: String): String =
        runCatching { URLDecoder.decode(part, "UTF-8") }.getOrDefault(part)
}
