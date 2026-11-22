package com.uk.linkpad

import java.text.NumberFormat

fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n.toLong())

fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

object Importer {

    /**
     * detected   - valid links found in the text (including repeats)
     * newCount   - links that are new (preview) or that were actually saved (after commit)
     * duplicates - repeats inside the text plus links that were already saved
     * invalid    - entries that are not usable links
     */
    class Summary(val detected: Int, val newCount: Int, val duplicates: Int, val invalid: List<String>)

    /** Dry run. Nothing is written. */
    fun preview(text: String, existingKeys: Set<String>): Summary {
        val r = UrlParser.parse(text)
        var fresh = 0
        for (p in r.valid) {
            if (!existingKeys.contains(p.key)) fresh++
        }
        val already = r.valid.size - fresh
        return Summary(r.valid.size + r.duplicatesInPaste, fresh, r.duplicatesInPaste + already, r.invalid)
    }

    /** Parses and saves in one database transaction. */
    fun commit(store: LinkStore, text: String): Summary {
        val r = UrlParser.parse(text)
        val saved = store.insertParsed(r.valid, System.currentTimeMillis())
        val already = r.valid.size - saved
        return Summary(r.valid.size + r.duplicatesInPaste, saved, r.duplicatesInPaste + already, r.invalid)
    }

    fun describe(s: Summary): String {
        if (s.detected == 0 && s.invalid.isEmpty()) return "No links found"
        val parts = ArrayList<String>()
        if (s.newCount > 0) {
            parts.add("Saved " + fmt(s.newCount) + " " + plural(s.newCount, "link", "links"))
        } else {
            parts.add("Nothing new to save")
        }
        if (s.duplicates > 0) {
            parts.add(fmt(s.duplicates) + " " + plural(s.duplicates, "duplicate", "duplicates") + " skipped")
        }
        if (s.invalid.isNotEmpty()) {
            parts.add(fmt(s.invalid.size) + " invalid")
        }
        return parts.joinToString(" \u00B7 ")
    }
}
