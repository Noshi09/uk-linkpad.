package com.uk.linkpad

/**
 * Turns pasted text into clean, de-duplicated links.
 *
 * - Splits on any whitespace (new lines, spaces, tabs).
 * - Also splits on commas / semicolons, but only where that is clearly a separator:
 *   a comma right before "http(s)://", or when every comma-separated piece is a valid link.
 *   Commas inside a link (for example Google Maps "@51.5,-0.12,17z") are left alone.
 * - Adds https:// when the scheme is missing, accepts only http and https.
 * - Builds a "key" so https://www.site.com/a/ and http://site.com/a count as the same link.
 */
object UrlParser {

    class Parsed(val url: String, val key: String, val host: String)

    class Result(val valid: List<Parsed>, val duplicatesInPaste: Int, val invalid: List<String>)

    private const val MAX_URL = 8192

    private val WHITESPACE = Regex("[\\s\\u00A0\\u200B\\uFEFF]+")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
    private val SPLIT_BEFORE_SCHEME = Regex("[,;]+(?=https?://)", RegexOption.IGNORE_CASE)
    private val LIST_MARKER = Regex("^(?:[-*\\u2022\\u00B7\\u2013\\u2014>]+|\\d{1,4}[.):\\]]?)$")

    private const val LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?"
    private val HOST_ANY = Regex("^" + LABEL + "(?:\\." + LABEL + ")*$")
    private val TLD = Regex("^(?:\\p{L}{2,63}|xn--[A-Za-z0-9\\-]{1,59})$")
    private val IPV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun parse(text: String): Result {
        val seen = HashSet<String>()
        val valid = ArrayList<Parsed>()
        val invalid = LinkedHashSet<String>()
        var dups = 0
        for (raw in WHITESPACE.split(text)) {
            if (raw.isEmpty()) continue
            for (piece in splitCommas(raw)) {
                val t = trimEdges(piece)
                if (t.isEmpty() || LIST_MARKER.matches(t)) continue
                val parsed = normalize(t)
                if (parsed == null) {
                    invalid.add(t)
                } else if (seen.add(parsed.key)) {
                    valid.add(parsed)
                } else {
                    dups++
                }
            }
        }
        return Result(valid, dups, invalid.toList())
    }

    fun normalize(token: String): Parsed? {
        if (token.isEmpty() || token.length > MAX_URL) return null
        var scheme = "https"
        var s = token
        var schemeGiven = false
        val m = SCHEME.find(s)
        if (m != null) {
            val sch = m.groupValues[1].lowercase()
            if (sch != "http" && sch != "https") return null
            scheme = sch
            schemeGiven = true
            s = s.substring(m.value.length)
        }

        var end = s.length
        for (i in s.indices) {
            val c = s[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val authority = s.substring(0, end)
        val rest = s.substring(end)
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return null

        var host = authority
        var port = ""
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
            if (port.isEmpty() || port.length > 5) return null
            for (ch in port) {
                if (ch < '0' || ch > '9') return null
            }
        }
        host = host.lowercase()
        if (!validHost(host, schemeGiven)) return null

        val defaultPort = (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
        val portPart = if (port.isEmpty() || defaultPort) "" else ":$port"
        val url = scheme + "://" + host + portPart + rest

        var path = rest
        val hash = path.indexOf('#')
        if (hash >= 0) path = path.substring(0, hash)
        var query = ""
        val q = path.indexOf('?')
        if (q >= 0) {
            query = path.substring(q)
            path = path.substring(0, q)
        }
        if (query == "?") query = ""
        path = path.trimEnd('/')

        val bare = if (host.startsWith("www.") && host.length > 4) host.substring(4) else host
        return Parsed(url, bare + portPart + path + query, bare)
    }

    /** Used only when migrating old data: keep whatever the old app accepted. */
    fun lenient(raw: String): Parsed? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val withScheme = if (SCHEME.containsMatchIn(t)) t else "https://$t"
        return Parsed(withScheme, withScheme, hostOf(withScheme))
    }

    fun keyOf(url: String): String = normalize(url)?.key ?: url

    fun hostOf(url: String): String {
        var s = url
        val i = s.indexOf("://")
        if (i >= 0) s = s.substring(i + 3)
        var end = s.length
        for (k in s.indices) {
            val c = s[k]
            if (c == '/' || c == '?' || c == '#') {
                end = k
                break
            }
        }
        s = s.substring(0, end)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        val colon = s.lastIndexOf(':')
        if (colon > 0) s = s.substring(0, colon)
        s = s.lowercase()
        if (s.startsWith("www.") && s.length > 4) s = s.substring(4)
        return if (s.isEmpty()) url else s
    }

    /** Everything after the host, for display. */
    fun pathOf(url: String): String {
        var s = url
        val i = s.indexOf("://")
        if (i >= 0) s = s.substring(i + 3)
        for (k in s.indices) {
            val c = s[k]
            if (c == '/' || c == '?' || c == '#') return s.substring(k)
        }
        return "/"
    }

    private fun validHost(host: String, schemeGiven: Boolean): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (IPV4.matches(host)) {
            for (part in host.split('.')) {
                if (part.toInt() > 255) return false
            }
            return true
        }
        if (!HOST_ANY.matches(host)) return false
        val dot = host.lastIndexOf('.')
        if (dot < 0) return schemeGiven
        val tld = host.substring(dot + 1)
        if (TLD.matches(tld)) return true
        return schemeGiven
    }

    private fun splitCommas(raw: String): List<String> {
        if (raw.indexOf(',') < 0 && raw.indexOf(';') < 0) return listOf(raw)
        val out = ArrayList<String>()
        for (part in SPLIT_BEFORE_SCHEME.split(raw)) {
            if (part.indexOf(',') < 0 && part.indexOf(';') < 0) {
                out.add(part)
                continue
            }
            val pieces = ArrayList<String>()
            for (x in part.split(',', ';')) {
                if (x.isNotEmpty()) pieces.add(x)
            }
            var allUrls = pieces.size > 1
            if (allUrls) {
                for (x in pieces) {
                    if (normalize(trimEdges(x)) == null) {
                        allUrls = false
                        break
                    }
                }
            }
            if (allUrls) out.addAll(pieces) else out.add(part)
        }
        return out
    }

    private fun trimEdges(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isLeading(s[start])) start++
        while (end > start) {
            val c = s[end - 1]
            if (isTrailing(c)) {
                end--
                continue
            }
            if (c == ')' && unbalanced(s, start, end, '(', ')')) {
                end--
                continue
            }
            if (c == ']' && unbalanced(s, start, end, '[', ']')) {
                end--
                continue
            }
            if (c == '}' && unbalanced(s, start, end, '{', '}')) {
                end--
                continue
            }
            break
        }
        return s.substring(start, end)
    }

    private fun isLeading(c: Char): Boolean =
        c == '<' || c == '(' || c == '[' || c == '{' || c == '"' || c == '\'' || c == '`' ||
            c == '\u201C' || c == '\u2018'

    private fun isTrailing(c: Char): Boolean =
        c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '"' ||
            c == '\'' || c == '`' || c == '>' || c == '\u201D' || c == '\u2019'

    private fun unbalanced(s: String, start: Int, end: Int, open: Char, close: Char): Boolean {
        var opens = 0
        var closes = 0
        for (i in start until end) {
            if (s[i] == open) opens++ else if (s[i] == close) closes++
        }
        return closes > opens
    }
}
