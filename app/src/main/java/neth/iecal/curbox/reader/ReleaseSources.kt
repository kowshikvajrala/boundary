package neth.iecal.curbox.reader

import neth.iecal.curbox.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** A released chapter and the address to read it. */
data class Release(val chapter: Double, val url: String)

/**
 * MangaDex's public API (https://api.mangadex.org): no account needed. Used for series linked
 * to MangaDex, English chapters only.
 */
object MangaDex {
    private const val API = "https://api.mangadex.org"
    private val ID_IN_URL = Regex("""mangadex\.org/title/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
    private const val RATINGS = "contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica"

    data class Match(val id: String, val title: String)

    /** The MangaDex series id in a MangaDex address, as Kenmei exports them. */
    fun idFromUrl(url: String): String? = ID_IN_URL.find(url.lowercase())?.groupValues?.get(1)

    /** The series whose title, or one of its other titles, is the same as [title]. */
    fun search(title: String): Match? {
        val url = "$API/manga?title=${URLEncoder.encode(title, "UTF-8")}&limit=10&$RATINGS"
        return parseSearch(Http.get(url), SeriesKeys.of(title))
    }

    /** Every name of a series, and its cover image file name if it has one. */
    data class Info(val titles: List<String>, val coverFile: String?)

    /** Names and cover of each series in [ids] (up to 100), by id. */
    fun info(ids: List<String>): Map<String, Info> {
        val query = ids.joinToString("&") { "ids%5B%5D=$it" }
        return parseInfo(Http.get("$API/manga?$query&limit=100&includes%5B%5D=cover_art&$RATINGS"))
    }

    /** A small (256 px) copy of the cover, as MangaDex serves it. */
    fun coverBytes(id: String, coverFile: String): ByteArray =
        Http.getBytes("https://uploads.mangadex.org/covers/$id/$coverFile.256.jpg")

    internal fun parseInfo(json: String): Map<String, Info> {
        val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyMap()
        return data.associate { element ->
            val manga = element.asJsonObject
            val cover = manga.getAsJsonArray("relationships")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("type")?.asString == "cover_art" }
                ?.getAsJsonObject("attributes")
                ?.get("fileName")?.takeIf { it.isJsonPrimitive }?.asString
            manga.get("id").asString to Info(titlesOf(manga.getAsJsonObject("attributes")), cover)
        }
    }

    private fun titlesOf(attributes: JsonObject?): List<String> {
        attributes ?: return emptyList()
        val main = attributes.getAsJsonObject("title")?.let(::stringsOf).orEmpty()
        val alt = attributes.getAsJsonArray("altTitles")
            ?.flatMap { if (it.isJsonObject) stringsOf(it.asJsonObject) else emptyList() }
            .orEmpty()
        return main + alt
    }

    fun releases(id: String): List<Release> =
        parseFeed(Http.get("$API/manga/$id/feed?translatedLanguage%5B%5D=en&order%5Bchapter%5D=desc&limit=500&$RATINGS"))

    internal fun parseSearch(json: String, key: String): Match? {
        val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return null
        for (element in data) {
            val manga = element.asJsonObject
            val attributes = manga.getAsJsonObject("attributes") ?: continue
            val titles = titlesOf(attributes)
            if (titles.any { SeriesKeys.of(it) == key }) {
                val english = attributes.getAsJsonObject("title")?.get("en")?.takeIf { it.isJsonPrimitive }?.asString
                return Match(manga.get("id").asString, english ?: titles.first())
            }
        }
        return null
    }

    internal fun parseFeed(json: String): List<Release> {
        val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            val chapter = element.asJsonObject
            val attributes = chapter.getAsJsonObject("attributes") ?: return@mapNotNull null
            val number = attributes.get("chapter")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                ?: return@mapNotNull null
            val external = attributes.get("externalUrl")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.startsWith("http") }
            Release(number, external ?: "https://mangadex.org/chapter/${chapter.get("id").asString}")
        }
    }

    private fun stringsOf(obj: JsonObject): List<String> =
        obj.entrySet().mapNotNull { (_, value) -> value.takeIf { it.isJsonPrimitive }?.asString }
}

/**
 * Any reading site: downloads the series page and reads every chapter link on it with
 * [ChapterParser]. Works on sites that list chapters as plain links; sites that build the list
 * with scripts, or block automated visits, give nothing.
 */
object SitePage {
    private val HREF = Regex("""href\s*=\s*["']([^"'#\s]+)["']""", RegexOption.IGNORE_CASE)

    fun releases(seriesPageUrl: String, seriesKey: String): List<Release> =
        parseLinks(Http.get(seriesPageUrl, browserLike = true), seriesPageUrl, seriesKey)

    internal fun parseLinks(html: String, pageUrl: String, seriesKey: String): List<Release> {
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val byChapter = linkedMapOf<Double, String>()
        HREF.findAll(html).forEach { match ->
            val href = match.groupValues[1].replace("&amp;", "&")
            val absolute = runCatching { base.resolve(href).toString() }.getOrNull() ?: return@forEach
            if (!absolute.startsWith("http")) return@forEach
            val chapter = ChapterParser.parse(absolute) ?: return@forEach
            if (chapter.seriesKey == seriesKey) byChapter.putIfAbsent(chapter.number, absolute)
        }
        return byChapter.map { (number, url) -> Release(number, url) }
    }
}

internal object Http {
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun get(url: String, browserLike: Boolean = false): String = getBytes(url, browserLike).decodeToString()

    fun getBytes(url: String, browserLike: Boolean = false): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                if (browserLike) BROWSER_AGENT else "Boundary/${BuildConfig.VERSION_NAME} (personal reading list)"
            )
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
