package neth.iecal.curbox.hardcoded

/**
 * Websites that are the same service as an app. While a browser shows one of these sites, the
 * app blocker treats the browser as that app, so blocking the app also blocks its website.
 * Websites typed into an app group (AppGroup.websites) work the same way under [siteKey].
 */
object AppWebsites {

    private const val SITE_KEY_PREFIX = "site:"

    private val domainsByPackage: Map<String, List<String>> = mapOf(
        "com.instagram.android" to listOf("instagram.com"),
        "com.facebook.katana" to listOf("facebook.com", "fb.com"),
        "com.twitter.android" to listOf("x.com", "twitter.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "com.ss.android.ugc.trill" to listOf("tiktok.com"),
        "com.reddit.frontpage" to listOf("reddit.com"),
        "com.snapchat.android" to listOf("snapchat.com"),
        "com.instagram.barcelona" to listOf("threads.net", "threads.com"),
        "com.google.android.youtube" to listOf("youtube.com", "youtu.be"),
    )

    /** The apps whose website [domain] is, for example "m.facebook.com" gives Facebook. */
    fun packagesFor(domain: String): List<String> =
        domainsByPackage.filterValues { domains -> domains.any { matches(domain, it) } }.keys.toList()

    /** True when [domain] is [site] or one of its subdomains, for example "m.example.com" and "example.com". */
    fun matches(domain: String, site: String): Boolean {
        val host = domain.lowercase().removePrefix("www.")
        return host == site || host.endsWith(".$site")
    }

    /** Turns what the user typed ("https://www.Example.com/page") into a site ("example.com"), or null. */
    fun normalize(input: String): String? {
        val host = input.trim().lowercase()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
            .trim('.')
        return host.takeIf { '.' in it && it.none(Char::isWhitespace) }
    }

    /** Stands in for a package name in app group lists, so a typed website is blocked like an app. */
    fun siteKey(site: String) = SITE_KEY_PREFIX + site

    fun isSiteKey(packageName: String) = packageName.startsWith(SITE_KEY_PREFIX)
}
