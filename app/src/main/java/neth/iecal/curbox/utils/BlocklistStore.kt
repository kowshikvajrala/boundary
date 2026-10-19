package neth.iecal.curbox.utils

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloadable domain blocklists such as hosts files, plain domain lists and adblock rules.
 *
 * A keyword group opts in with a `list:<id>` keyword. Each list keeps its domains in its
 * own text file, so large lists never bloat the settings JSON or need a Room migration.
 * The UI process downloads and writes the files; every process may read them.
 */
object BlocklistStore {

    const val KEYWORD_PREFIX = "list:"

    data class Source(
        val id: String = "",
        val name: String = "",
        val url: String = "",
        val domainCount: Int = 0,
        val updatedAtMs: Long = 0L
    )

    val DEFAULT_SOURCES = listOf(
        Source(
            id = "adult",
            name = "Adult sites (HaGeZi NSFW)",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt"
        )
    )

    private const val SOURCES_FILE = "sources.json"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private val ID_PATTERN = Regex("^[a-z0-9-]{1,40}$")
    private val DOMAIN_PATTERN = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
    private val WHITESPACE = Regex("\\s+")

    private class LoadedList(val modifiedAt: Long, val domains: Set<String>)

    private val loaded = ConcurrentHashMap<String, LoadedList>()
    private val gson = Gson()
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "blocklists")
    }

    fun keywordFor(id: String): String = "$KEYWORD_PREFIX$id"

    /** Returns the list id for a `list:<id>` keyword, or null for any other keyword. */
    fun idFromKeyword(keyword: String): String? {
        if (!keyword.startsWith(KEYWORD_PREFIX, ignoreCase = true)) return null
        val id = keyword.substring(KEYWORD_PREFIX.length).trim().lowercase(Locale.ROOT)
        return id.takeIf { ID_PATTERN.matches(it) }
    }

    // region Sources (UI process)

    @Synchronized
    fun sources(): List<Source> {
        val file = File(dir ?: return DEFAULT_SOURCES, SOURCES_FILE)
        if (!file.exists()) return DEFAULT_SOURCES
        return runCatching {
            gson.fromJson(file.readText(), Array<Source>::class.java).toList()
        }.getOrDefault(DEFAULT_SOURCES)
    }

    @Synchronized
    fun addSource(name: String, url: String): Source {
        val current = sources()
        val base = slugify(name)
        var id = base
        var suffix = 2
        while (current.any { it.id == id }) id = "$base-${suffix++}".take(40)
        val source = Source(id = id, name = name.trim(), url = url.trim())
        saveSources(current + source)
        return source
    }

    @Synchronized
    fun removeSource(id: String) {
        saveSources(sources().filterNot { it.id == id })
        dir?.let { File(it, "$id.txt").delete() }
        loaded.remove(id)
    }

    /** Downloads and stores a list, returning how many domains it holds. Blocking: call off the main thread. */
    fun download(id: String): Int {
        val directory = dir ?: throw IOException("Blocklists are not ready yet")
        val source = sources().find { it.id == id } ?: throw IOException("Unknown list")
        directory.mkdirs()

        val connection = URL(source.url).openConnection() as HttpURLConnection
        val domains = HashSet<String>()
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server answered $code")
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> parseLine(line)?.let(domains::add) }
            }
        } finally {
            connection.disconnect()
        }
        if (domains.isEmpty()) throw IOException("No sites found in this list")

        val temp = File(directory, "$id.txt.tmp")
        temp.bufferedWriter().use { writer ->
            domains.forEach { domain ->
                writer.write(domain)
                writer.newLine()
            }
        }
        if (!temp.renameTo(File(directory, "$id.txt"))) {
            temp.delete()
            throw IOException("Could not save the list")
        }

        synchronized(this) {
            saveSources(sources().map {
                if (it.id == id) it.copy(domainCount = domains.size, updatedAtMs = System.currentTimeMillis()) else it
            })
        }
        return domains.size
    }

    private fun saveSources(sources: List<Source>) {
        val directory = dir ?: return
        directory.mkdirs()
        val temp = File(directory, "$SOURCES_FILE.tmp")
        temp.writeText(gson.toJson(sources))
        temp.renameTo(File(directory, SOURCES_FILE))
    }

    private fun slugify(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(30)
            .ifEmpty { "list" }

    // endregion

    // region Matching (any process)

    /** Loads a list into memory now so the first lookup does not read the file. */
    fun preload(id: String) {
        load(id)
    }

    fun matchesHost(id: String, host: String): Boolean = matchesHost(load(id), host)

    /** Matches the host itself and every parent domain, so `m.site.com` hits a `site.com` entry. */
    internal fun matchesHost(domains: Set<String>, host: String): Boolean {
        if (domains.isEmpty()) return false
        var candidate = host.lowercase(Locale.ROOT).removePrefix("www.")
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun load(id: String): Set<String> {
        val file = File(dir ?: return emptySet(), "$id.txt")
        val modifiedAt = file.lastModified()
        if (modifiedAt == 0L) {
            loaded.remove(id)
            return emptySet()
        }
        loaded[id]?.let { if (it.modifiedAt == modifiedAt) return it.domains }
        val domains = runCatching {
            file.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.toHashSet() }
        }.getOrDefault(emptySet())
        loaded[id] = LoadedList(modifiedAt, domains)
        return domains
    }

    /**
     * Pulls the domain out of one line of a hosts file (`0.0.0.0 site.com`), a plain or
     * wildcard domain list (`site.com`, `*.site.com`) or an adblock rule (`||site.com^`).
     */
    internal fun parseLine(raw: String): String? {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) return null

        val columns = line.split(WHITESPACE)
        val entry = if (columns.size >= 2) columns[1] else columns[0]
        val domain = entry
            .removePrefix("||")
            .substringBefore('^')
            .substringBefore('$')
            .lowercase(Locale.ROOT)
            .removePrefix("*.")
            .removePrefix(".")
            .removePrefix("www.")
            .trimEnd('.')

        if (!DOMAIN_PATTERN.matches(domain)) return null
        if (domain.all { it.isDigit() || it == '.' }) return null
        return domain
    }

    // endregion
}
