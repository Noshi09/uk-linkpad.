package com.uk.linkpad

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/** A saved link. "Opened" is only a mark on the link; it never removes it. */
class SavedLink(
    val id: Long,
    val url: String,
    val key: String,
    val host: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val openCount: Int
) {
    val isOpened: Boolean get() = openCount > 0
}

/** One entry in the opened history. Lives in its own table, separate from saved links. */
class HistoryEntry(
    val id: Long,
    val url: String,
    val host: String,
    val openedAt: Long
)

class Counts(val total: Int, val opened: Int, val history: Int)

/**
 * All data lives in one local SQLite database (no network, no extra dependencies).
 * Two independent tables:
 *   links    - the saved collection
 *   history  - a log of every time a link was opened
 * Clearing history only touches the history table.
 */
class LinkStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "linkpad.db"
        private const val DB_VERSION = 1
        private const val KEY_MIGRATED = "migrated_to_db_v2"

        const val FILTER_ALL = 0
        const val FILTER_UNOPENED = 1
        const val FILTER_OPENED = 2

        private val SPACES = Regex("\\s+")
    }

    private val versionCounter = AtomicLong(0L)

    /** Goes up on every change, so callers can tell when cached data is stale. */
    val version: Long get() = versionCounter.get()

    private fun bump() {
        versionCounter.incrementAndGet()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE links (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT NOT NULL, " +
                "url_key TEXT NOT NULL UNIQUE, " +
                "host TEXT NOT NULL, " +
                "added_at INTEGER NOT NULL, " +
                "last_opened_at INTEGER NOT NULL DEFAULT 0, " +
                "open_count INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_links_added ON links(added_at DESC, id)")
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT NOT NULL, " +
                "host TEXT NOT NULL, " +
                "opened_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_history_opened ON history(opened_at DESC, id DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to upgrade yet. Never drop tables here: that would delete user data.
    }

    // ---------------------------------------------------------------- saved links

    /** Inserts new links in one transaction. Returns how many were really new. */
    fun insertParsed(items: List<UrlParser.Parsed>, now: Long): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var saved = 0
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO links (url, url_key, host, added_at) VALUES (?, ?, ?, ?)"
            )
            try {
                for (p in items) {
                    st.clearBindings()
                    st.bindString(1, p.url)
                    st.bindString(2, p.key)
                    st.bindString(3, p.host)
                    st.bindLong(4, now)
                    if (st.executeInsert() != -1L) saved++
                }
            } finally {
                st.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
        return saved
    }

    fun queryLinks(search: String = "", filter: Int = FILTER_ALL, limit: Int = -1): ArrayList<SavedLink> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        addTerms(search, "url", where, args)
        if (filter == FILTER_UNOPENED) {
            where.append(" AND open_count = 0")
        } else if (filter == FILTER_OPENED) {
            where.append(" AND open_count > 0")
        }
        var sql = "SELECT id, url, url_key, host, added_at, last_opened_at, open_count " +
            "FROM links WHERE " + where.toString() + " ORDER BY added_at DESC, id ASC"
        if (limit > 0) sql += " LIMIT " + limit
        val out = ArrayList<SavedLink>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(mapLink(c))
        }
        return out
    }

    private fun mapLink(c: Cursor): SavedLink =
        SavedLink(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getInt(6))

    private fun addTerms(search: String, column: String, where: StringBuilder, args: MutableList<String>) {
        val terms = search.trim().lowercase().split(SPACES)
        for (t in terms) {
            if (t.isEmpty()) continue
            where.append(" AND instr(lower(").append(column).append("), ?) > 0")
            args.add(t)
        }
    }

    fun deleteLink(id: Long) {
        writableDatabase.delete("links", "id = ?", arrayOf(id.toString()))
        bump()
    }

    fun deleteAllLinks() {
        writableDatabase.delete("links", null, null)
        bump()
    }

    /** Puts deleted links back exactly as they were (used by Undo). */
    fun restoreLinks(list: List<SavedLink>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO links (id, url, url_key, host, added_at, last_opened_at, open_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                for (l in list) {
                    st.clearBindings()
                    st.bindLong(1, l.id)
                    st.bindString(2, l.url)
                    st.bindString(3, l.key)
                    st.bindString(4, l.host)
                    st.bindLong(5, l.addedAt)
                    st.bindLong(6, l.lastOpenedAt)
                    st.bindLong(7, l.openCount.toLong())
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    /** Forgets the "opened" marks on saved links. Never deletes a link. */
    fun resetOpenedMarks() {
        writableDatabase.execSQL("UPDATE links SET open_count = 0, last_opened_at = 0 WHERE open_count > 0")
        bump()
    }

    fun existingKeys(): HashSet<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT url_key FROM links", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun allUrls(): ArrayList<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT url FROM links ORDER BY added_at DESC, id ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun counts(): Counts {
        val db = readableDatabase
        var total = 0
        var opened = 0
        var history = 0
        db.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN open_count > 0 THEN 1 ELSE 0 END) FROM links", null
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                opened = c.getInt(1)
            }
        }
        db.rawQuery("SELECT COUNT(*) FROM history", null).use { c ->
            if (c.moveToFirst()) history = c.getInt(0)
        }
        return Counts(total, opened, history)
    }

    // ------------------------------------------------------------------- history

    /**
     * Opening a link does two separate things:
     *  1. marks the saved link as opened (the link stays in Saved Links)
     *  2. adds a row to the history table
     */
    fun recordOpen(url: String, key: String, host: String, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE links SET last_opened_at = ?, open_count = open_count + 1 WHERE url_key = ?",
                arrayOf<Any?>(now, key)
            )
            val cv = ContentValues()
            cv.put("url", url)
            cv.put("host", host)
            cv.put("opened_at", now)
            db.insertOrThrow("history", null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    fun queryHistory(search: String = ""): ArrayList<HistoryEntry> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        addTerms(search, "url", where, args)
        val sql = "SELECT id, url, host, opened_at FROM history WHERE " + where.toString() +
            " ORDER BY opened_at DESC, id DESC"
        val out = ArrayList<HistoryEntry>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        return out
    }

    fun deleteHistoryEntry(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        bump()
    }

    /** Clears the history table only. Saved links are not touched. */
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
        bump()
    }

    fun restoreHistory(list: List<HistoryEntry>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO history (id, url, host, opened_at) VALUES (?, ?, ?, ?)"
            )
            try {
                for (e in list) {
                    st.clearBindings()
                    st.bindLong(1, e.id)
                    st.bindString(2, e.url)
                    st.bindString(3, e.host)
                    st.bindLong(4, e.openedAt)
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    // ----------------------------------------------------------------- migration

    /**
     * Version 1.0 kept everything in SharedPreferences as a JSON array
     * ([{"url": "...", "opened": true}, ...]). Copy it into the database once.
     * The old data is left in place as a backup.
     */
    fun migrateLegacyIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val json = prefs.getString("links", null)
        if (json.isNullOrBlank()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).commit()
            return
        }
        try {
            val arr = JSONArray(json)
            val db = writableDatabase
            db.beginTransaction()
            try {
                val st = db.compileStatement(
                    "INSERT OR IGNORE INTO links (url, url_key, host, added_at, open_count) VALUES (?, ?, ?, 0, ?)"
                )
                try {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val raw = o.optString("url", "")
                        val parsed = UrlParser.normalize(raw.trim()) ?: UrlParser.lenient(raw) ?: continue
                        val wasOpened = o.optBoolean("opened", false)
                        st.clearBindings()
                        st.bindString(1, parsed.url)
                        st.bindString(2, parsed.key)
                        st.bindString(3, parsed.host)
                        st.bindLong(4, if (wasOpened) 1L else 0L)
                        val rowId = st.executeInsert()
                        if (rowId != -1L && wasOpened) {
                            val cv = ContentValues()
                            cv.put("url", parsed.url)
                            cv.put("host", parsed.host)
                            cv.put("opened_at", 0L)
                            db.insertOrThrow("history", null, cv)
                        }
                    }
                } finally {
                    st.close()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).commit()
            bump()
        } catch (e: Exception) {
            // Leave the flag unset so the next launch tries again. Old data is untouched.
        }
    }
}
