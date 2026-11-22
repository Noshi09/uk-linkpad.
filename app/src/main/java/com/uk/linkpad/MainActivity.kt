package com.uk.linkpad

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("uk_linkpad", MODE_PRIVATE)
    }

    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private var dark = false
    private val links = mutableListOf<JSONObject>()

    private var bg = Color.WHITE
    private var fg = Color.BLACK
    private var card = Color.rgb(245, 245, 245)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = prefs.getBoolean("dark", false)
        loadLinks()
        buildScreen()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadLinks() {
        links.clear()
        val saved = prefs.getString("links", "[]") ?: "[]"
        val array = JSONArray(saved)
        for (i in 0 until array.length()) {
            links.add(array.getJSONObject(i))
        }
    }

    private fun saveLinks() {
        val array = JSONArray()
        links.forEach { array.put(it) }
        prefs.edit().putString("links", array.toString()).apply()
    }

    private fun theme() {
        bg = if (dark) Color.rgb(18, 18, 18) else Color.WHITE
        fg = if (dark) Color.WHITE else Color.BLACK
        card = if (dark) Color.rgb(38, 38, 38) else Color.rgb(245, 245, 245)
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(fg)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
    }

    private fun buildScreen() {
        theme()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(text("UK LinkPad", 26f, true),
            LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(button(if (dark) "☀ Light" else "☾ Dark") {
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            buildScreen()
        })

        root.addView(header)

        root.addView(text("Your personal link collection", 14f).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        input = EditText(this).apply {
            hint = "Paste a link here..."
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(fg)
            setHintTextColor(if (dark) Color.LTGRAY else Color.GRAY)
        }

        root.addView(input, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(button("＋ Save link") {
            addLink()
        }, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        actions.addView(button("Clear opened") {
            confirmClear("Clear opened links?") {
                links.removeAll { it.optBoolean("opened") }
                saveLinks()
                refreshList()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        actions.addView(button("Clear all") {
            confirmClear("Delete ALL saved links?") {
                links.clear()
                saveLinks()
                refreshList()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(actions)

        root.addView(text("SAVED LINKS", 13f, true).apply {
            setPadding(0, dp(15), 0, dp(8))
        })

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        refreshList()
    }

    private fun addLink() {
        val url = input.text.toString().trim()

        if (url.isEmpty()) {
            Toast.makeText(this, "Paste a link first", Toast.LENGTH_SHORT).show()
            return
        }

        val normalized = if (url.startsWith("http://", true) ||
            url.startsWith("https://", true)) url else "https://$url"

        val uri = Uri.parse(normalized)
        if (uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Please enter a valid link", Toast.LENGTH_SHORT).show()
            return
        }

        links.add(JSONObject().apply {
            put("url", normalized)
            put("opened", false)
        })

        saveLinks()
        input.text.clear()
        refreshList()
        Toast.makeText(this, "Link saved!", Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        list.removeAllViews()

        if (links.isEmpty()) {
            list.addView(text("No saved links yet. Add your first link above.", 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, dp(30))
            })
            return
        }

        links.forEachIndexed { index, item ->
            val url = item.optString("url")
            val opened = item.optBoolean("opened")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(card)
            }

            row.addView(text(url, 14f, true))

            row.addView(text(if (opened) "✓ Opened" else "● Not opened", 12f).apply {
                setPadding(0, dp(5), 0, dp(5))
            })

            val controls = LinearLayout(this)

            controls.addView(button("Open") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    links[index].put("opened", true)
                    saveLinks()
                    refreshList()
                } catch (_: Exception) {
                    Toast.makeText(this, "Cannot open this link", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))

            controls.addView(button("Copy") {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link", url))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))

            controls.addView(button("Delete") {
                links.removeAt(index)
                saveLinks()
                refreshList()
            }, LinearLayout.LayoutParams(0, -2, 1f))

            row.addView(controls)

            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(9)
            list.addView(row, params)
        }
    }

    private fun confirmClear(message: String, action: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> action() }
            .show()
    }
}
