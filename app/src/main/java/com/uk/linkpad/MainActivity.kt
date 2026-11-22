package com.uk.linkpad

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("uk_linkpad_v2", MODE_PRIVATE)
    }

    private val saved = mutableListOf<String>()
    private val history = mutableListOf<Pair<String, Long>>()

    private var dark = true
    private var page = "Home"
    private var searchText = ""

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var search: EditText

    private val bg get() = if (dark) Color.rgb(12, 13, 15) else Color.rgb(246, 246, 244)
    private val panel get() = if (dark) Color.rgb(25, 27, 30) else Color.WHITE
    private val fg get() = if (dark) Color.rgb(245, 245, 245) else Color.rgb(22, 22, 22)
    private val muted get() = if (dark) Color.rgb(155, 158, 164) else Color.rgb(112, 112, 112)
    private val accent get() = if (dark) Color.WHITE else Color.BLACK
    private val red get() = Color.rgb(220, 53, 53)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            showContent()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun button(
        label: String,
        action: () -> Unit,
        primary: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setTextColor(if (primary) {
                if (dark) Color.BLACK else Color.WHITE
            } else fg)
            background = rounded(
                if (primary) accent else panel,
                14
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun sectionTitle(value: String) {
        content.addView(
            text(value.uppercase(), 11f, muted, true)
        )
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        header.addView(text("LP", 14f, muted, true))
        header.addView(text(
            when (page) {
                "Saved" -> "Saved links"
                "Settings" -> "Settings"
                else -> "Your links."
            },
            30f, fg, true
        ))
        header.addView(text(
            when (page) {
                "Saved" -> "Your collection, all in one place."
                "Settings" -> "Make LP yours."
                else -> "Everything you save, in one place."
            },
            13f, muted
        ))

        root.addView(header)

        if (page != "Settings") {
            search = EditText(this).apply {
                hint = "Search links..."
                setSingleLine(true)
                textSize = 14f
                setTextColor(fg)
                setHintTextColor(muted)
                setPadding(dp(15), dp(11), dp(15), dp(11))
                background = rounded(panel, 14)
                setText(searchText)

                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?, start: Int, count: Int, after: Int
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?, start: Int, before: Int, count: Int
                    ) {
                        searchText = s?.toString() ?: ""
                        showContent()
                    }

                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            root.addView(search, LinearLayout.LayoutParams(
                -1, dp(48)
            ).apply {
                topMargin = dp(20)
            })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(20))
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            -1, 0, 1f
        ))

        // Fixed bottom navigation
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = rounded(panel, 20)
        }

        listOf(
            Triple("Home", "⌂", "Home"),
            Triple("Saved", "▣", "Saved"),
            Triple("Settings", "⚙", "Settings")
        ).forEach { item ->
            val selected = page == item.first

            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener {
                    page = item.first
                    searchText = ""
                    render()
                }
            }

            tab.addView(TextView(this).apply {
                text = item.second
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(if (selected) accent else muted)
            })

            tab.addView(TextView(this).apply {
                text = item.third
                textSize = 11f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setTextColor(if (selected) fg else muted)
            })

            nav.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f))
        }

        root.addView(nav, LinearLayout.LayoutParams(
            -1, dp(66)
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(5)
        })

        setContentView(root)
        showContent()
    }

    private fun showContent() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        when (page) {
            "Home" -> {
                sectionTitle("YOUR LIBRARY")
                content.addView(text("${saved.size}", 38f, fg, true))
                content.addView(text(
                    if (saved.size == 1) "saved link" else "saved links",
                    14f, muted
                ))

                content.addView(button(
                    "+   IMPORT LINKS",
                    { importLinks() },
                    true
                ), LinearLayout.LayoutParams(-1, dp(52)).apply {
                    topMargin = dp(22)
                    bottomMargin = dp(25)
                })

                sectionTitle("RECENTLY SAVED")

                val filtered = saved.filter {
                    it.contains(searchText, true)
                }

                if (filtered.isEmpty()) {
                    content.addView(text(
                        if (searchText.isNotBlank()) "No matching links." else "No links yet. Import your first links to get started.",
                        14f, muted
                    ))
                } else {
                    filtered.take(5).forEach {
                        addLinkRow(it)
                    }
                }

                if (saved.size > 5) {
                    content.addView(button(
                        "VIEW ALL SAVED LINKS",
                        {
                            page = "Saved"
                            searchText = ""
                            render()
                        }
                    ))
                }
            }

            "Saved" -> {
                content.addView(button(
                    "+   IMPORT LINKS",
                    { importLinks() },
                    true
                ))

                content.addView(text(
                    "${saved.size} saved links",
                    14f, muted
                ))

                val filtered = saved.filter {
                    it.contains(searchText, true)
                }

                if (filtered.isEmpty()) {
                    content.addView(text(
                        "No saved links found.",
                        14f, muted
                    ))
                }

                filtered.forEach {
                    addLinkRow(it)
                }

                if (saved.isNotEmpty()) {
                    content.addView(button(
                        "CLEAR ALL SAVED LINKS",
                        { clearSaved() }
                    ))
                }
            }

            "Settings" -> {
                sectionTitle("APPEARANCE")
                content.addView(text(
                    if (dark) "Dark appearance" else "Light appearance",
                    17f, fg, true
                ))
                content.addView(text(
                    "Choose the look that feels right.",
                    13f, muted
                ))

                content.addView(button(
                    "SWITCH TO ${if (dark) "LIGHT" else "DARK"} MODE",
                    {
                        dark = !dark
                        saveData()
                        render()
                    },
                    true
                ), LinearLayout.LayoutParams(-1, dp(50)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(28)
                })

                sectionTitle("STORAGE")
                content.addView(text(
                    "Saved links: ${saved.size}",
                    14f, fg
                ))
                content.addView(text(
                    "LP stores your data locally on this device.",
                    13f, muted
                ))

                content.addView(button(
                    "CLEAR ALL SAVED LINKS",
                    { clearSaved() }
                ), LinearLayout.LayoutParams(-1, dp(48)).apply {
                    topMargin = dp(18)
                })
            }
        }
    }

    private fun addLinkRow(url: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(panel, 17)
        }

        val address = text(url, 13f, fg, true)
        address.typeface = Typeface.MONOSPACE
        card.addView(address)

        val opened = history.any { it.first == url }
        card.addView(text(
            if (opened) "OPENED" else "SAVED LINK",
            10f, if (opened) Color.parseColor("#30D158") else muted, true
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        actions.addView(button(
            "OPEN",
            { openLink(url) },
            true
        ), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(6)
        })

        actions.addView(button(
            "COPY",
            { copyLink(url) }
        ), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(6)
        })

        actions.addView(button(
            "DELETE",
            {
                confirm(
                    "Delete this link?",
                    "This link will be removed from your saved collection.",
                    "DELETE"
                ) {
                    saved.remove(url)
                    saveData()
                    showContent()
                }
            }
        ), LinearLayout.LayoutParams(0, dp(42), 1f))

        card.addView(actions)

        content.addView(card, LinearLayout.LayoutParams(
            -1, -2
        ).apply {
            topMargin = dp(9)
            bottomMargin = dp(5)
        })
    }

    private fun confirm(
        title: String,
        message: String,
        positive: String,
        action: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton(positive) { _, _ -> action() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(red)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(fg)
            dialog.window?.setBackgroundDrawable(
                rounded(panel, 20)
            )
        }

        dialog.show()
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

        val dialog = AlertDialog.Builder(this)
            .setTitle("Import links")
            .setMessage("Paste URLs separated by new lines, spaces or commas.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(fg)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(muted)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
                searchText = ""
                render()

                Toast.makeText(
                    this,
                    "$added added, $skipped skipped",
                    Toast.LENGTH_LONG
                ).show()

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun normalizeUrl(raw: String): String? {
        val candidate = if (
            raw.startsWith("http://", true) ||
            raw.startsWith("https://", true)
        ) raw else "https://$raw"

        return try {
            val uri = Uri.parse(candidate)
            if (
                (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() &&
                uri.host!!.contains(".")
            ) candidate else null
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
            Toast.makeText(this, "Opening link", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Unable to open link",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearSaved() {
        confirm(
            "Clear saved links?",
            "This removes all saved links. This action cannot be undone.",
            "DELETE ALL"
        ) {
            saved.clear()
            saveData()
            showContent()
            Toast.makeText(
                this,
                "Saved links cleared",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
