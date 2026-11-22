package com.uk.linkpad

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

// ------------------------------------------------------------------ shared helpers

fun savedRow(l: SavedLink): RowData {
    val meta = if (l.isOpened) {
        "Opened " + relTime(l.lastOpenedAt)
    } else {
        "Saved " + relTime(l.addedAt)
    }
    return RowData(l.url, l.host, l.isOpened, meta, l)
}

/** Deletes one saved link with a short slide-out and an Undo. */
fun deleteSavedLink(h: Host, link: SavedLink, card: LinkCard) {
    card.animateOut {
        h.store.deleteLink(link.id)
        h.dataChanged()
        h.snack("Link deleted", "Undo") {
            h.store.restoreLinks(listOf(link))
            h.dataChanged()
        }
    }
}

private fun screenTitle(u: Ui, value: String): TextView = u.text(value, 28f, u.p.text, u.serif)

// ================================================================== HOME

class HomeScreen(private val h: Host, initialText: String) : Screen(), CardActions {

    private val u = h.ui
    private val p = u.p
    private val handler = Handler(Looper.getMainLooper())

    private val input = EditText(h.activity)
    private val chipScroll = HorizontalScrollView(h.activity)
    private val chipRow = LinearLayout(h.activity)
    private val saveBtn = TextView(h.activity)
    private val clearBtn = u.text("Clear", 13f, p.muted, u.medium)
    private val subtitle = u.text("", 13f, p.muted)
    private val recentBox = LinearLayout(h.activity)

    private var startText = initialText
    private var previewSeq = 0
    private var suppress = false
    private var saving = false
    private var cachedKeys = HashSet<String>()
    private var cachedVersion = -1L
    private val emptySummary = Importer.Summary(0, 0, 0, emptyList())
    private val previewRunnable = Runnable { runPreview() }

    override val root: View by lazy { build() }

    private fun build(): View {
        val scroll = ScrollView(h.activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        val col = LinearLayout(h.activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(28))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // header
        val header = LinearLayout(h.activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(h.activity)
        logo.setImageResource(R.drawable.ic_logo_mark)
        header.addView(logo, u.lpm(44, 44, 0, 0, 14, 0))
        val titles = LinearLayout(h.activity)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(screenTitle(u, "UK LinkPad"))
        titles.addView(subtitle, u.lpm(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0))
        header.addView(titles, u.lp(0, Ui.WRAP, 1f))
        header.addView(
            u.iconButton(R.drawable.ic_search, "Search saved links", p.text, 44, 22, p.surface) {
                h.goTo(TAB_SAVED)
            }
        )
        col.addView(header, u.lp(Ui.MATCH, Ui.WRAP))

        // import card
        val card = LinearLayout(h.activity)
        card.orientation = LinearLayout.VERTICAL
        card.background = u.rounded(p.surface, 22f, p.line, 1f)
        card.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(16))

        val labelRow = LinearLayout(h.activity)
        labelRow.orientation = LinearLayout.HORIZONTAL
        labelRow.gravity = Gravity.CENTER_VERTICAL
        labelRow.addView(u.text("Paste links", 13f, p.muted, u.medium), u.lp(0, Ui.WRAP, 1f))
        clearBtn.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(8))
        clearBtn.background = u.ghost(false, 12f)
        clearBtn.isClickable = true
        clearBtn.visibility = View.GONE
        clearBtn.setOnClickListener { input.setText("") }
        labelRow.addView(clearBtn, u.lpm(Ui.WRAP, Ui.WRAP, 0, 0, 4, 0))

        val pasteBtn = LinearLayout(h.activity)
        pasteBtn.orientation = LinearLayout.HORIZONTAL
        pasteBtn.gravity = Gravity.CENTER_VERTICAL
        pasteBtn.background = u.pressable(u.rounded(p.accentSoft, 14f), 14f)
        pasteBtn.setPadding(u.dp(12), u.dp(8), u.dp(14), u.dp(8))
        pasteBtn.isClickable = true
        pasteBtn.addView(u.icon(R.drawable.ic_clipboard, 16, p.accent))
        pasteBtn.addView(u.text("Paste", 13f, p.accent, u.medium), u.lpm(Ui.WRAP, Ui.WRAP, 6, 0, 0, 0))
        pasteBtn.setOnClickListener { pasteFromClipboard() }
        labelRow.addView(pasteBtn, u.lp(Ui.WRAP, Ui.WRAP))
        card.addView(labelRow, u.lpm(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10))

        input.hint = "One link per line, or paste a whole list at once"
        input.setHintTextColor(p.muted)
        input.setTextColor(p.text)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        input.typeface = u.mono
        input.gravity = Gravity.TOP or Gravity.START
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setHorizontallyScrolling(false)
        input.minLines = 6
        input.maxLines = 10
        input.setLineSpacing(0f, 1.2f)
        input.setPadding(u.dp(12), u.dp(12), u.dp(12), u.dp(12))
        input.background = u.rounded(p.bg, 14f, p.line, 1f)
        input.highlightColor = (p.accent and 0x00FFFFFF) or 0x40000000
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        input.setOnTouchListener { v, ev ->
            if (input.lineCount > input.maxLines) v.parent.requestDisallowInterceptTouchEvent(true)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        card.addView(input, u.lp(Ui.MATCH, Ui.WRAP))

        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.visibility = View.GONE
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipScroll.addView(chipRow, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        card.addView(chipScroll, u.lpm(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0))

        saveBtn.text = "Save all links"
        saveBtn.gravity = Gravity.CENTER
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        saveBtn.setTextColor(p.onAccent)
        saveBtn.typeface = u.medium
        saveBtn.background = u.pressable(u.rounded(p.accent, 16f), 16f)
        saveBtn.isClickable = true
        saveBtn.setOnClickListener { onSave() }
        card.addView(saveBtn, u.lpm(Ui.MATCH, 54, 0, 12, 0, 0))

        col.addView(card, u.lpm(Ui.MATCH, Ui.WRAP, 0, 22, 0, 0))

        // recently saved
        val recentHeader = LinearLayout(h.activity)
        recentHeader.orientation = LinearLayout.HORIZONTAL
        recentHeader.gravity = Gravity.CENTER_VERTICAL
        recentHeader.addView(u.text("Recently saved", 13f, p.muted, u.medium), u.lp(0, Ui.WRAP, 1f))
        val seeAll = u.text("See all", 13f, p.accent, u.medium)
        seeAll.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(8))
        seeAll.background = u.ghost(false, 12f)
        seeAll.isClickable = true
        seeAll.setOnClickListener { h.goTo(TAB_SAVED) }
        recentHeader.addView(seeAll, u.lp(Ui.WRAP, Ui.WRAP))
        col.addView(recentHeader, u.lpm(Ui.MATCH, Ui.WRAP, 0, 24, 0, 6))

        recentBox.orientation = LinearLayout.VERTICAL
        col.addView(recentBox, u.lp(Ui.MATCH, Ui.WRAP))

        // text watcher goes on after the starting text is set
        if (startText.isNotEmpty()) {
            input.setText(startText)
            clearBtn.visibility = View.VISIBLE
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (suppress) return
                val has = (s?.length ?: 0) > 0
                clearBtn.visibility = if (has) View.VISIBLE else View.GONE
                schedulePreview()
            }
        })
        if (startText.isNotBlank()) schedulePreview()
        startText = ""
        return scroll
    }

    fun currentText(): String = input.text.toString()

    /** Used by "Share to UK LinkPad": drops text into the box without saving. */
    fun addText(t: String) {
        val cur = input.text.toString()
        val next = if (cur.isBlank()) t else cur.trimEnd() + "\n" + t
        input.setText(next)
    }

    override fun refresh() {
        val c = h.store.counts()
        subtitle.text = if (c.total == 0) {
            "Your personal link collection"
        } else {
            fmt(c.total) + " saved \u00B7 " + fmt(c.opened) + " opened"
        }
        recentBox.removeAllViews()
        val recent = h.store.queryLinks("", LinkStore.FILTER_ALL, 5)
        if (recent.isEmpty()) {
            val hint = u.text(
                "No links yet. Paste a list above and tap Save all links.", 14f, p.muted
            )
            hint.gravity = Gravity.CENTER
            hint.setLineSpacing(0f, 1.25f)
            hint.setPadding(u.dp(20), u.dp(26), u.dp(20), u.dp(26))
            hint.background = u.rounded(p.bg, 18f, p.line, 1f)
            recentBox.addView(hint, u.lp(Ui.MATCH, Ui.WRAP))
        } else {
            for (l in recent) {
                val card = LinkCard(u, this, R.drawable.ic_trash, 0)
                card.bind(savedRow(l))
                recentBox.addView(card, u.lp(Ui.MATCH, Ui.WRAP))
            }
        }
    }

    // -------- recent card actions

    override fun onOpen(payload: Any) {
        h.openLink((payload as SavedLink).url)
    }

    override fun onCopy(payload: Any) {
        h.copyText((payload as SavedLink).url, "Link copied")
    }

    override fun onDelete(payload: Any, card: LinkCard) {
        deleteSavedLink(h, payload as SavedLink, card)
    }

    // -------- import box

    private fun pasteFromClipboard() {
        val cm = h.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        var t = ""
        if (clip != null && clip.itemCount > 0) {
            t = clip.getItemAt(0).coerceToText(h.activity).toString()
        }
        if (t.isBlank()) {
            h.snack("Clipboard is empty")
            return
        }
        addText(t)
    }

    private fun schedulePreview() {
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 250L)
    }

    private fun runPreview() {
        val text = input.text.toString()
        previewSeq++
        val seq = previewSeq
        if (text.isBlank()) {
            showPreview(emptySummary)
            return
        }
        h.background {
            val v = h.store.version
            if (cachedVersion != v) {
                cachedKeys = h.store.existingKeys()
                cachedVersion = v
            }
            val s = Importer.preview(text, cachedKeys)
            h.onMain {
                if (seq == previewSeq) showPreview(s)
            }
        }
    }

    private fun addChip(label: String, kind: Int) {
        val fg = if (kind == 0) p.accent else if (kind == 2) p.danger else p.muted
        val fill = if (kind == 0) p.accentSoft else if (kind == 2) p.dangerSoft else p.bg
        val t = u.text(label, 12.5f, fg, u.medium)
        t.setPadding(u.dp(11), u.dp(6), u.dp(11), u.dp(6))
        t.background = if (kind == 1) u.rounded(fill, 14f, p.line, 1f) else u.rounded(fill, 14f)
        chipRow.addView(t, u.lpm(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0))
    }

    private fun setSaveLabel(newCount: Int) {
        saveBtn.text = if (newCount > 0) "Save all links \u00B7 " + fmt(newCount) else "Save all links"
    }

    private fun showPreview(s: Importer.Summary) {
        chipRow.removeAllViews()
        if (s.detected == 0 && s.invalid.isEmpty()) {
            chipScroll.visibility = View.GONE
            setSaveLabel(0)
            return
        }
        chipScroll.visibility = View.VISIBLE
        addChip(fmt(s.detected) + " detected", 0)
        if (s.duplicates > 0) {
            addChip(fmt(s.duplicates) + " " + plural(s.duplicates, "duplicate", "duplicates"), 1)
        }
        if (s.invalid.isNotEmpty()) addChip(fmt(s.invalid.size) + " invalid", 2)
        setSaveLabel(s.newCount)
    }

    private fun showResult(s: Importer.Summary) {
        chipRow.removeAllViews()
        chipScroll.visibility = View.VISIBLE
        addChip(fmt(s.newCount) + " saved", 0)
        if (s.duplicates > 0) {
            addChip(fmt(s.duplicates) + " " + plural(s.duplicates, "duplicate", "duplicates") + " skipped", 1)
        }
        if (s.invalid.isNotEmpty()) addChip(fmt(s.invalid.size) + " invalid, kept below", 2)
        setSaveLabel(0)
    }

    private fun setSaving(on: Boolean) {
        saving = on
        saveBtn.alpha = if (on) 0.6f else 1f
        if (on) saveBtn.text = "Saving\u2026"
    }

    private fun onSave() {
        if (saving) return
        val text = input.text.toString()
        if (text.isBlank()) {
            h.snack("Paste some links first")
            return
        }
        u.hideKeyboard(input)
        handler.removeCallbacks(previewRunnable)
        previewSeq++
        setSaving(true)
        h.background {
            val s = Importer.commit(h.store, text)
            h.onMain {
                setSaving(false)
                previewSeq++
                handler.removeCallbacks(previewRunnable)
                suppress = true
                input.setText(if (s.invalid.isEmpty()) "" else s.invalid.joinToString("\n"))
                suppress = false
                clearBtn.visibility = if (input.text.isEmpty()) View.GONE else View.VISIBLE
                if (s.detected == 0 && s.invalid.isEmpty()) {
                    chipRow.removeAllViews()
                    chipScroll.visibility = View.GONE
                    setSaveLabel(0)
                } else {
                    showResult(s)
                }
                h.dataChanged()
                h.snack(Importer.describe(s))
            }
        }
    }
}

// ================================================================== SAVED

class SavedScreen(
    private val h: Host,
    private var query: String,
    private var filter: Int
) : Screen(), CardActions {

    private val u = h.ui
    private val p = u.p
    private val handler = Handler(Looper.getMainLooper())
    private val subtitle = u.text("", 13f, p.muted)
    private val listView = ListView(h.activity)
    private val empty = EmptyState(u)
    private val adapter = CardAdapter(u, this, R.drawable.ic_trash) { o -> savedRow(o as SavedLink) }
    private val refreshRunnable = Runnable { refresh() }
    private var search: SearchBar? = null
    private var filters: PillRow? = null

    override val root: View by lazy { build() }

    fun currentQuery(): String = query

    fun currentFilter(): Int = filter

    private fun build(): View {
        val col = LinearLayout(h.activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, u.dp(20), 0, 0)

        val titles = LinearLayout(h.activity)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(u.dp(20), 0, u.dp(20), 0)
        titles.addView(screenTitle(u, "Saved links"))
        titles.addView(subtitle, u.lpm(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0))
        col.addView(titles, u.lp(Ui.MATCH, Ui.WRAP))

        val bar = SearchBar(u, "Search saved links") { q ->
            query = q
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 120L)
        }
        if (query.isNotEmpty()) bar.input.setText(query)
        search = bar
        col.addView(bar, u.lpm(Ui.MATCH, 48, 16, 16, 16, 0))

        val pills = PillRow(u, listOf("All", "Unopened", "Opened"), filter, false) { i ->
            filter = i
            refresh()
        }
        filters = pills
        val pillScroll = HorizontalScrollView(h.activity)
        pillScroll.isHorizontalScrollBarEnabled = false
        pillScroll.addView(pills, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        pillScroll.setPadding(u.dp(16), 0, u.dp(16), 0)
        pillScroll.clipToPadding = false
        col.addView(pillScroll, u.lpm(Ui.MATCH, Ui.WRAP, 0, 12, 0, 8))

        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.TRANSPARENT)
        listView.cacheColorHint = Color.TRANSPARENT
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        listView.isVerticalScrollBarEnabled = false
        listView.isFastScrollEnabled = true
        listView.clipToPadding = false
        listView.setPadding(0, u.dp(4), 0, u.dp(24))
        listView.adapter = adapter

        val frame = FrameLayout(h.activity)
        frame.addView(listView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        empty.visibility = View.GONE
        col.addView(frame, u.lp(Ui.MATCH, 0, 1f))
        return col
    }

    override fun refresh() {
        val c = h.store.counts()
        val list = h.store.queryLinks(query, filter)
        adapter.submit(list)
        filters?.setLabels(
            listOf(
                "All " + fmt(c.total),
                "Unopened " + fmt(c.total - c.opened),
                "Opened " + fmt(c.opened)
            )
        )
        val plain = query.isBlank() && filter == LinkStore.FILTER_ALL
        subtitle.text = if (plain) {
            fmt(c.total) + " " + plural(c.total, "link", "links")
        } else {
            fmt(list.size) + " of " + fmt(c.total)
        }
        if (list.isEmpty()) {
            listView.visibility = View.GONE
            empty.visibility = View.VISIBLE
            if (c.total == 0) {
                empty.set(
                    R.drawable.ic_bookmark,
                    "Your collection starts here",
                    "Paste a list of links on Home and they will all land here, ready to open.",
                    "Import links"
                ) { h.goTo(TAB_HOME) }
            } else {
                empty.set(
                    R.drawable.ic_search,
                    "No matches",
                    "Try a different search, or switch the filter.",
                    null,
                    null
                )
            }
        } else {
            listView.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
    }

    override fun onOpen(payload: Any) {
        h.openLink((payload as SavedLink).url)
    }

    override fun onCopy(payload: Any) {
        h.copyText((payload as SavedLink).url, "Link copied")
    }

    override fun onDelete(payload: Any, card: LinkCard) {
        deleteSavedLink(h, payload as SavedLink, card)
    }
}

// ================================================================== HISTORY

class HistoryScreen(
    private val h: Host,
    private var query: String
) : Screen(), CardActions {

    private val u = h.ui
    private val p = u.p
    private val handler = Handler(Looper.getMainLooper())
    private val subtitle = u.text("", 13f, p.muted)
    private val listView = ListView(h.activity)
    private val empty = EmptyState(u)
    private val clearPill = LinearLayout(h.activity)
    private val adapter = CardAdapter(u, this, R.drawable.ic_close) { o -> historyRow(o as HistoryEntry) }
    private val refreshRunnable = Runnable { refresh() }

    override val root: View by lazy { build() }

    fun currentQuery(): String = query

    private fun historyRow(e: HistoryEntry): RowData =
        RowData(e.url, e.host, false, "Opened " + relTime(e.openedAt), e)

    private fun build(): View {
        val col = LinearLayout(h.activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, u.dp(20), 0, 0)

        val header = LinearLayout(h.activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(u.dp(20), 0, u.dp(20), 0)
        val titles = LinearLayout(h.activity)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(screenTitle(u, "Opened history"))
        titles.addView(subtitle, u.lpm(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0))
        header.addView(titles, u.lp(0, Ui.WRAP, 1f))

        clearPill.orientation = LinearLayout.HORIZONTAL
        clearPill.gravity = Gravity.CENTER_VERTICAL
        clearPill.background = u.pressable(u.rounded(p.dangerSoft, 16f), 16f)
        clearPill.setPadding(u.dp(12), u.dp(9), u.dp(14), u.dp(9))
        clearPill.isClickable = true
        clearPill.addView(u.icon(R.drawable.ic_trash, 16, p.danger))
        clearPill.addView(u.text("Clear", 13.5f, p.danger, u.medium), u.lpm(Ui.WRAP, Ui.WRAP, 6, 0, 0, 0))
        clearPill.setOnClickListener { confirmClear() }
        clearPill.visibility = View.GONE
        header.addView(clearPill, u.lp(Ui.WRAP, Ui.WRAP))
        col.addView(header, u.lp(Ui.MATCH, Ui.WRAP))

        val bar = SearchBar(u, "Search history") { q ->
            query = q
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 120L)
        }
        if (query.isNotEmpty()) bar.input.setText(query)
        col.addView(bar, u.lpm(Ui.MATCH, 48, 16, 16, 16, 8))

        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.TRANSPARENT)
        listView.cacheColorHint = Color.TRANSPARENT
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        listView.isVerticalScrollBarEnabled = false
        listView.isFastScrollEnabled = true
        listView.clipToPadding = false
        listView.setPadding(0, u.dp(8), 0, u.dp(24))
        listView.adapter = adapter

        val frame = FrameLayout(h.activity)
        frame.addView(listView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        empty.visibility = View.GONE
        col.addView(frame, u.lp(Ui.MATCH, 0, 1f))
        return col
    }

    override fun refresh() {
        val c = h.store.counts()
        val list = h.store.queryHistory(query)
        adapter.submit(list)
        subtitle.text = if (query.isBlank()) {
            fmt(c.history) + " " + plural(c.history, "entry", "entries")
        } else {
            fmt(list.size) + " of " + fmt(c.history)
        }
        clearPill.visibility = if (c.history > 0) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            listView.visibility = View.GONE
            empty.visibility = View.VISIBLE
            if (c.history == 0) {
                empty.set(
                    R.drawable.ic_history,
                    "Nothing opened yet",
                    "Links you open show up here, newest first. Your saved links are never touched.",
                    "Browse saved links"
                ) { h.goTo(TAB_SAVED) }
            } else {
                empty.set(R.drawable.ic_search, "No matches", "Try a different search.", null, null)
            }
        } else {
            listView.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
    }

    private fun confirmClear() {
        val n = h.store.counts().history
        if (n == 0) return
        h.confirm(
            "Clear opened history?",
            "This removes all " + fmt(n) + " " + plural(n, "entry", "entries") +
                " from your history. Your saved links stay exactly as they are.",
            "Clear history",
            true
        ) {
            val all = h.store.queryHistory("")
            h.store.clearHistory()
            h.dataChanged()
            h.snack("History cleared", "Undo") {
                h.store.restoreHistory(all)
                h.dataChanged()
            }
        }
    }

    override fun onOpen(payload: Any) {
        h.openLink((payload as HistoryEntry).url)
    }

    override fun onCopy(payload: Any) {
        h.copyText((payload as HistoryEntry).url, "Link copied")
    }

    override fun onDelete(payload: Any, card: LinkCard) {
        val entry = payload as HistoryEntry
        card.animateOut {
            h.store.deleteHistoryEntry(entry.id)
            h.dataChanged()
            h.snack("Removed from history", "Undo") {
                h.store.restoreHistory(listOf(entry))
                h.dataChanged()
            }
        }
    }
}

// ================================================================== SETTINGS

class SettingsScreen(private val h: Host) : Screen() {

    private val u = h.ui
    private val p = u.p

    override val root: View by lazy { build() }

    override fun refresh() {}

    private fun section(label: String): TextView {
        val t = u.text(label.uppercase(), 12f, p.muted, u.medium)
        t.letterSpacing = 0.08f
        return t
    }

    private fun build(): View {
        val scroll = ScrollView(h.activity)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val col = LinearLayout(h.activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(32))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        col.addView(screenTitle(u, "Settings"), u.lp(Ui.WRAP, Ui.WRAP))

        col.addView(section("Appearance"), u.lpm(Ui.WRAP, Ui.WRAP, 4, 26, 0, 10))
        col.addView(
            PillRow(u, listOf("System", "Light", "Dark"), h.themeMode(), true) { i -> h.setThemeMode(i) },
            u.lp(Ui.MATCH, Ui.WRAP)
        )

        col.addView(section("Your data"), u.lpm(Ui.WRAP, Ui.WRAP, 4, 28, 0, 10))
        val data = LinearLayout(h.activity)
        data.orientation = LinearLayout.VERTICAL
        data.background = u.rounded(p.surface, 20f, p.line, 1f)
        data.addView(
            row(R.drawable.ic_export, "Export saved links", "Save every link to a text file", false) {
                h.exportLinks()
            }
        )
        data.addView(divider())
        data.addView(
            row(R.drawable.ic_import, "Import from file", "Add links from a .txt or .csv file", false) {
                h.importFile()
            }
        )
        data.addView(divider())
        data.addView(
            row(R.drawable.ic_copy, "Copy all links", "One link per line", false) { copyAll() }
        )
        data.addView(divider())
        data.addView(
            row(
                R.drawable.ic_reset,
                "Reset opened marks",
                "Links stay saved. Only the Opened badges go.",
                false
            ) { confirmReset() }
        )
        data.addView(divider())
        data.addView(
            row(
                R.drawable.ic_trash,
                "Delete all saved links",
                "Removes your whole collection",
                true
            ) { confirmDeleteAll() }
        )
        col.addView(data, u.lp(Ui.MATCH, Ui.WRAP))

        col.addView(section("About"), u.lpm(Ui.WRAP, Ui.WRAP, 4, 28, 0, 10))
        val about = LinearLayout(h.activity)
        about.orientation = LinearLayout.HORIZONTAL
        about.gravity = Gravity.CENTER_VERTICAL
        about.background = u.rounded(p.surface, 20f, p.line, 1f)
        about.setPadding(u.dp(16), u.dp(16), u.dp(16), u.dp(16))
        val logo = ImageView(h.activity)
        logo.setImageResource(R.drawable.ic_logo_mark)
        about.addView(logo, u.lpm(44, 44, 0, 0, 14, 0))
        val aboutText = LinearLayout(h.activity)
        aboutText.orientation = LinearLayout.VERTICAL
        aboutText.addView(u.text("UK LinkPad " + versionName(), 16f, p.text, u.serif))
        val note = u.text(
            "Everything stays on this device. No account, and the app never goes online.",
            12.5f,
            p.muted
        )
        note.setLineSpacing(0f, 1.2f)
        aboutText.addView(note, u.lpm(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0))
        about.addView(aboutText, u.lp(0, Ui.WRAP, 1f))
        col.addView(about, u.lp(Ui.MATCH, Ui.WRAP))
        return scroll
    }

    private fun versionName(): String {
        return try {
            val info = h.activity.packageManager.getPackageInfo(h.activity.packageName, 0)
            info.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun divider(): View {
        val v = View(h.activity)
        v.setBackgroundColor(p.line)
        val lp = LinearLayout.LayoutParams(Ui.MATCH, 1)
        lp.leftMargin = u.dp(72)
        v.layoutParams = lp
        return v
    }

    private fun row(icon: Int, title: String, sub: String, danger: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(h.activity)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14))
        r.background = u.ghost(false, 20f)
        r.isClickable = true
        r.setOnClickListener { onClick() }

        val tint = if (danger) p.danger else p.accent
        val box = FrameLayout(h.activity)
        box.background = u.rounded(if (danger) p.dangerSoft else p.accentSoft, 12f)
        val img = ImageView(h.activity)
        img.setImageResource(icon)
        img.setColorFilter(tint)
        box.addView(img, FrameLayout.LayoutParams(u.dp(20), u.dp(20), Gravity.CENTER))
        r.addView(box, LinearLayout.LayoutParams(u.dp(40), u.dp(40)))

        val text = LinearLayout(h.activity)
        text.orientation = LinearLayout.VERTICAL
        text.addView(u.text(title, 15f, if (danger) p.danger else p.text, u.medium))
        text.addView(u.text(sub, 12.5f, p.muted), u.lpm(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0))
        val tp = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        tp.leftMargin = u.dp(16)
        r.addView(text, tp)
        r.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        return r
    }

    private fun copyAll() {
        h.background {
            val urls = h.store.allUrls()
            h.onMain {
                if (urls.isEmpty()) {
                    h.snack("No saved links yet")
                } else {
                    h.copyText(
                        urls.joinToString("\n"),
                        "Copied " + fmt(urls.size) + " " + plural(urls.size, "link", "links")
                    )
                }
            }
        }
    }

    private fun confirmReset() {
        val c = h.store.counts()
        if (c.opened == 0) {
            h.snack("No links are marked as opened")
            return
        }
        h.confirm(
            "Reset opened marks?",
            "This clears the Opened badge on " + fmt(c.opened) +
                " saved " + plural(c.opened, "link", "links") +
                ". No link is deleted and your history is not changed.",
            "Reset marks",
            false
        ) {
            h.store.resetOpenedMarks()
            h.dataChanged()
            h.snack("Opened marks reset")
        }
    }

    private fun confirmDeleteAll() {
        val n = h.store.counts().total
        if (n == 0) {
            h.snack("There are no saved links")
            return
        }
        h.confirm(
            "Delete all saved links?",
            "This removes all " + fmt(n) + " saved " + plural(n, "link", "links") +
                " from this device. Your history is kept. Export first if you might need them again.",
            "Delete all",
            true
        ) {
            val all = h.store.queryLinks("", LinkStore.FILTER_ALL)
            h.store.deleteAllLinks()
            h.dataChanged()
            h.snack("Deleted " + fmt(all.size) + " " + plural(all.size, "link", "links"), "Undo") {
                h.store.restoreLinks(all)
                h.dataChanged()
            }
        }
    }
}
