package com.uk.linkpad

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("uk_linkpad_v2", MODE_PRIVATE) }
    private val saved = mutableListOf<String>()
    private val history = mutableListOf<Pair<String, Long>>()

    private var dark = true
    private var page = "Home"
    private var searchText = ""

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var search: EditText

    private val lime get() = fg
    private val bg get() = if (dark) Color.rgb(15, 17, 16) else Color.rgb(245, 247, 242)
    private val panel get() = if (dark) Color.rgb(27, 30, 28) else Color.WHITE
    private val fg get() = if (dark) Color.rgb(240, 244, 237) else Color.rgb(25, 30, 25)
    private val muted get() = if (dark) Color.rgb(145, 153, 144) else Color.rgb(105, 115, 103)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        render()
    }

    private fun loadData() {
        saved.clear()
        history.clear()

        val s = JSONArray(prefs.getString("saved", "[]"))
        for (i in 0 until s.length()) saved.add(s.getString(i))

        val h = JSONArray(prefs.getString("history", "[]"))
        for (i in 0 until h.length()) {
            val item = h.getJSONObject(i)
            history.add(item.getString("url") to item.getLong("time"))
        }

        dark = prefs.getBoolean("dark", true)
    }

    private fun saveData() {
        val s = JSONArray()
        saved.forEach { s.put(it) }

        val h = JSONArray()
        history.forEach {
            h.put(JSONObject().put("url", it.first).put("time", it.second))
        }

        prefs.edit()
            .putString("saved", s.toString())
            .putString("history", h.toString())
            .putBoolean("dark", dark)
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float = 15f,
        color: Int = fg,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
    }

    private fun button(
        label: String,
        action: () -> Unit,
        accent: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (accent) if (dark) Color.BLACK else Color.WHITE else fg)
            setBackgroundColor(if (accent) lime else panel)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        header.addView(text("UK LINKPAD", 13f, lime, true))
        header.addView(text(page, 29f, fg, true))
        header.addView(text("YOUR LINKS. YOUR CONTROL.", 10f, muted, true))
        root.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(15), 0, dp(12))
        }

        listOf("Home", "Saved", "History", "Settings").forEach { item ->
            val tab = TextView(this).apply {
                text = item
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (page == item) lime else muted)
                setPadding(dp(7), dp(12), dp(7), dp(12))
                setOnClickListener {
                    page = item
                    searchText = ""
                    render()
                }
            }
            nav.addView(tab, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(nav)

        if (page != "Settings") {
            search = EditText(this).apply {
                hint = "Search links..."
                setSingleLine(true)
                textSize = 14f
                setTextColor(fg)
                setHintTextColor(muted)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(panel)
                setText(searchText)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchText = s?.toString() ?: ""
                        showContent()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            root.addView(search)
        }

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        showContent()
    }

    private fun showContent() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        when (page) {
            "Home" -> {
                content.addView(text("LINK LIBRARY", 11f, lime, true))
                content.addView(text("${saved.size} saved links", 23f, fg, true))
                content.addView(text("${history.size} opening records", 14f, muted))
                content.addView(button("+  IMPORT LINKS", { importLinks() }, true),
                    LinearLayout.LayoutParams(-1, dp(48)).apply {
                        topMargin = dp(18)
                        bottomMargin = dp(12)
                    })
                content.addView(button("VIEW SAVED LINKS", {
                    page = "Saved"; render()
                }))
                content.addView(button("VIEW HISTORY", {
                    page = "History"; render()
                }))
            }

            "Saved" -> {
                content.addView(button("+  ADD / BULK IMPORT", { importLinks() }, true))
                content.addView(text("${saved.size} saved links", 13f, muted))
                val filtered = saved.filter { it.contains(searchText, true) }
                if (filtered.isEmpty()) content.addView(text("No saved links found.", 15f, muted))
                filtered.forEach { addLinkRow(it, false) }
            }

            "History" -> {
                content.addView(text("${history.size} opening records", 13f, muted))
                content.addView(button("CLEAR HISTORY", { clearHistory() }))
                val filtered = history.filter { it.first.contains(searchText, true) }
                if (filtered.isEmpty()) content.addView(text("No history yet.", 15f, muted))
                filtered.asReversed().forEach { addHistoryRow(it) }
            }

            "Settings" -> {
                content.addView(text("APPEARANCE", 11f, lime, true))
                content.addView(text(if (dark) "Dark theme enabled" else "Light theme enabled"))
                content.addView(button("SWITCH TO ${if (dark) "LIGHT" else "DARK"} MODE", {
                    dark = !dark
                    saveData()
                    render()
                }, true))
                content.addView(text("STORAGE", 11f, lime, true))
                content.addView(text("Saved links: ${saved.size}"))
                content.addView(text("History records: ${history.size}"))
                content.addView(text("UK LinkPad stores your data locally on this device.", 13f, muted))
                content.addView(button("CLEAR ALL SAVED LINKS", { clearSaved() }))
            }
        }
    }

    private fun addLinkRow(url: String, isHistory: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        val address = text(url, 13f, lime)
        address.typeface = Typeface.MONOSPACE
        card.addView(address)
        val opened = history.any { it.first == url }
        card.addView(text(
            if (opened) "✓ OPENED" else "• NOT OPENED",
            11f, if (opened) lime else muted, true
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("OPEN", { openLink(url) }, true))
        actions.addView(button("COPY", { copyLink(url) }))
        if (!isHistory) {
            actions.addView(button("DELETE", {
                saved.remove(url)
                saveData()
                showContent()
            }))
        }
        card.addView(actions)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun addHistoryRow(item: Pair<String, Long>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        val address = text(item.first, 13f, lime)
        address.typeface = Typeface.MONOSPACE
        card.addView(address)
        card.addView(text(
            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.second)),
            11f, muted
        ))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("OPEN", { openLink(item.first) }, true))
        actions.addView(button("COPY", { copyLink(item.first) }))
        actions.addView(button("DELETE", {
            history.remove(item)
            saveData()
            showContent()
        }))
        card.addView(actions)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun importLinks() {
        val input = EditText(this).apply {
            hint = "Paste one or many URLs here..."
            minLines = 5
            gravity = Gravity.TOP
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(muted)
        }

        AlertDialog.Builder(this)
            .setTitle("Import links")
            .setMessage("Paste URLs separated by new lines, spaces or commas.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT") { _, _ ->
                val parts = input.text.toString().split(Regex("[\\s,;]+"))
                var added = 0
                var skipped = 0

                parts.map { it.trim().trim('"', '\'') }
                    .filter { it.isNotBlank() }
                    .forEach { raw ->
                        val url = normalizeUrl(raw)
                        if (url != null && !saved.contains(url)) {
                            saved.add(url)
                            added++
                        } else {
                            skipped++
                        }
                    }

                saveData()
                page = "Saved"
                render()
                Toast.makeText(
                    this,
                    "$added added, $skipped skipped",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun normalizeUrl(raw: String): String? {
        val candidate = if (raw.startsWith("http://", true) ||
            raw.startsWith("https://", true)) raw else "https://$raw"

        return try {
            val uri = Uri.parse(candidate)
            if ((uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() &&
                uri.host!!.contains(".")) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    private fun openLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
            history.add(url to System.currentTimeMillis())
            saveData()
            Toast.makeText(this, "Added to history", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("This removes opening records only. Your saved links will remain.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CLEAR") { _, _ ->
                history.clear()
                saveData()
                showContent()
            }
            .show()
    }

    private fun clearSaved() {
        AlertDialog.Builder(this)
            .setTitle("Clear saved links?")
            .setMessage("This removes all saved links. History will remain.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CLEAR") { _, _ ->
                saved.clear()
                saveData()
                showContent()
            }
            .show()
    }
}
