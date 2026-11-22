package com.uk.linkpad

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val TAB_HOME = 0
const val TAB_SAVED = 1
const val TAB_HISTORY = 2
const val TAB_SETTINGS = 3

/** What the screens need from the activity. */
interface Host {
    val ui: Ui
    val store: LinkStore
    val activity: Activity

    fun snack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null)
    fun confirm(title: String, message: String, confirmLabel: String, destructive: Boolean, onConfirm: () -> Unit)
    fun openLink(url: String)
    fun copyText(text: String, done: String)
    fun goTo(tab: Int)
    fun dataChanged()
    fun background(work: () -> Unit)
    fun onMain(work: () -> Unit)
    fun themeMode(): Int
    fun setThemeMode(mode: Int)
    fun exportLinks()
    fun importFile()
}

abstract class Screen {
    abstract val root: View
    abstract fun refresh()
    open fun onShown() {}
}

// ------------------------------------------------------------------ link cards

class RowData(
    val url: String,
    val host: String,
    val opened: Boolean,
    val meta: String,
    val payload: Any
)

interface CardActions {
    fun onOpen(payload: Any)
    fun onCopy(payload: Any)
    fun onDelete(payload: Any, card: LinkCard)
}

/** One link: letter tile, host, path, status, and Open / Copy / Delete buttons. */
class LinkCard(
    private val u: Ui,
    private val actions: CardActions,
    deleteIcon: Int,
    horizontalPadDp: Int
) : FrameLayout(u.ctx) {

    private val p = u.p
    private val card = LinearLayout(u.ctx)
    private val tile = TextView(u.ctx)
    private val tileBg = GradientDrawable()
    private val hostView = u.text("", 15f, p.text, u.medium)
    private val chip = u.text("Opened", 11f, p.accent, u.medium)
    private val pathView = u.text("", 12f, p.muted, u.mono)
    private val metaView = u.text("", 12f, p.muted)
    private var payload: Any? = null

    init {
        setPadding(u.dp(horizontalPadDp), u.dp(4), u.dp(horizontalPadDp), u.dp(4))

        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(u.dp(12), u.dp(12), u.dp(8), u.dp(12))
        card.background = u.pressable(u.rounded(p.surface, 18f, p.line, 1f), 18f)
        card.isClickable = true
        card.setOnClickListener {
            val pl = payload
            if (pl != null) actions.onOpen(pl)
        }

        tileBg.shape = GradientDrawable.RECTANGLE
        tileBg.setCornerRadius(u.dpf(12f))
        tile.background = tileBg
        tile.gravity = Gravity.CENTER
        tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        tile.setTextColor(p.tileText)
        tile.typeface = u.serif
        card.addView(tile, LinearLayout.LayoutParams(u.dp(42), u.dp(42)))

        val col = LinearLayout(u.ctx)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(u.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        hostView.maxLines = 1
        hostView.ellipsize = TextUtils.TruncateAt.END
        top.addView(hostView, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        chip.background = u.rounded(p.accentSoft, 8f)
        chip.setPadding(u.dp(7), u.dp(3), u.dp(7), u.dp(3))
        top.addView(chip, u.lpm(Ui.WRAP, Ui.WRAP, 8, 0, 4, 0))
        col.addView(top, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        pathView.maxLines = 1
        pathView.ellipsize = TextUtils.TruncateAt.END
        col.addView(pathView, u.lpm(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0))

        metaView.maxLines = 1
        metaView.ellipsize = TextUtils.TruncateAt.END
        col.addView(metaView, u.lpm(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0))

        val colParams = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        colParams.leftMargin = u.dp(12)
        card.addView(col, colParams)

        val open = u.iconButton(R.drawable.ic_open, "Open link", p.onAccent, 36, 18, p.accent) {
            val pl = payload
            if (pl != null) actions.onOpen(pl)
        }
        val copy = u.iconButton(R.drawable.ic_copy, "Copy link", p.muted, 36, 18) {
            val pl = payload
            if (pl != null) actions.onCopy(pl)
        }
        val del = u.iconButton(deleteIcon, "Delete", p.muted, 36, 18) {
            val pl = payload
            if (pl != null) actions.onDelete(pl, this)
        }
        card.addView(open, u.lpm(36, 36, 4, 0, 2, 0))
        card.addView(copy, u.lpm(36, 36, 0, 0, 0, 0))
        card.addView(del, u.lpm(36, 36, 0, 0, 0, 0))

        addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    fun bind(r: RowData) {
        payload = r.payload
        alpha = 1f
        translationX = 0f
        var letter = '#'
        for (c in r.host) {
            if (c.isLetterOrDigit()) {
                letter = c
                break
            }
        }
        tile.text = letter.toString().uppercase()
        tileBg.setColor(u.tileColor(r.host))
        hostView.text = r.host
        chip.visibility = if (r.opened) View.VISIBLE else View.GONE
        pathView.text = UrlParser.pathOf(r.url)
        metaView.text = r.meta
    }

    fun animateOut(end: () -> Unit) {
        animate().alpha(0f).translationX(width * 0.2f).setDuration(160).withEndAction { end() }.start()
    }
}

class CardAdapter(
    private val u: Ui,
    private val actions: CardActions,
    private val deleteIcon: Int,
    private val toRow: (Any) -> RowData
) : BaseAdapter() {

    private var items: List<Any> = emptyList()

    fun submit(list: List<Any>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val card = (convertView as? LinkCard) ?: LinkCard(u, actions, deleteIcon, 16)
        card.bind(toRow(items[position]))
        return card
    }
}

// ------------------------------------------------------------------ search bar

class SearchBar(
    private val u: Ui,
    hintText: String,
    private val onChange: (String) -> Unit
) : LinearLayout(u.ctx) {

    private val p = u.p
    val input = EditText(u.ctx)
    private val clearBtn: View

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = u.rounded(p.surface, 16f, p.line, 1f)
        setPadding(u.dp(14), 0, u.dp(4), 0)

        addView(u.icon(R.drawable.ic_search, 20, p.muted))

        input.background = null
        input.hint = hintText
        input.setHintTextColor(p.muted)
        input.setTextColor(p.text)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        input.typeface = u.regular
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setPadding(u.dp(10), 0, u.dp(4), 0)
        addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))

        clearBtn = u.iconButton(R.drawable.ic_close, "Clear search", p.muted, 40, 18) {
            input.setText("")
        }
        clearBtn.visibility = View.GONE
        addView(clearBtn)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString() ?: ""
                clearBtn.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                onChange(value)
            }
        })
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                u.hideKeyboard(v)
                true
            } else {
                false
            }
        }
    }

    fun query(): String = input.text.toString()
}

// ------------------------------------------------------------------ pills

/** A row of selectable pills. Used for filters (segmented = false) and the theme switch (segmented = true). */
class PillRow(
    private val u: Ui,
    labels: List<String>,
    private var selectedIndex: Int,
    private val segmented: Boolean,
    private val onSelect: (Int) -> Unit
) : LinearLayout(u.ctx) {

    private val p = u.p
    private val pills = ArrayList<TextView>()

    init {
        orientation = LinearLayout.HORIZONTAL
        if (segmented) {
            background = u.rounded(p.surface, 18f, p.line, 1f)
            setPadding(u.dp(4), u.dp(4), u.dp(4), u.dp(4))
        }
        for (i in labels.indices) {
            val t = TextView(u.ctx)
            t.text = labels[i]
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            t.gravity = Gravity.CENTER
            t.setPadding(u.dp(16), 0, u.dp(16), 0)
            t.isClickable = true
            t.setOnClickListener {
                select(i)
                onSelect(i)
            }
            pills.add(t)
            val params = if (segmented) {
                LinearLayout.LayoutParams(0, u.dp(40), 1f)
            } else {
                LinearLayout.LayoutParams(Ui.WRAP, u.dp(36))
            }
            if (!segmented && i > 0) params.leftMargin = u.dp(8)
            addView(t, params)
        }
        style()
    }

    fun setLabels(labels: List<String>) {
        for (i in labels.indices) {
            if (i < pills.size) pills[i].text = labels[i]
        }
    }

    fun select(index: Int) {
        selectedIndex = index
        style()
    }

    private fun style() {
        for (i in pills.indices) {
            val t = pills[i]
            val on = i == selectedIndex
            t.typeface = if (on) u.medium else u.regular
            t.setTextColor(if (on) p.onAccent else p.text)
            val fill = if (on) p.accent else (if (segmented) 0 else p.surface)
            val stroke = if (on || segmented) 0 else p.line
            val radius = if (segmented) 14f else 18f
            val base = u.rounded(fill, radius, stroke, if (stroke != 0) 1f else 0f)
            t.background = u.pressable(base, radius)
        }
    }
}

// ------------------------------------------------------------------ empty state

class EmptyState(private val u: Ui) : LinearLayout(u.ctx) {

    private val p = u.p
    private val iconView = ImageView(u.ctx)
    private val title = u.text("", 21f, p.text, u.serif)
    private val body = u.text("", 14f, p.muted)
    private val cta = TextView(u.ctx)
    private var lastTitle = ""

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(u.dp(36), u.dp(24), u.dp(36), u.dp(48))

        val box = FrameLayout(u.ctx)
        box.background = u.circle(p.accentSoft)
        iconView.setColorFilter(p.accent)
        box.addView(iconView, FrameLayout.LayoutParams(u.dp(34), u.dp(34), Gravity.CENTER))
        addView(box, LinearLayout.LayoutParams(u.dp(84), u.dp(84)))

        title.gravity = Gravity.CENTER
        addView(title, u.lpm(Ui.WRAP, Ui.WRAP, 0, 20, 0, 0))

        body.gravity = Gravity.CENTER
        body.setLineSpacing(0f, 1.25f)
        addView(body, u.lpm(Ui.WRAP, Ui.WRAP, 0, 8, 0, 0))

        cta.gravity = Gravity.CENTER
        cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        cta.setTextColor(p.onAccent)
        cta.typeface = u.medium
        cta.setPadding(u.dp(26), u.dp(14), u.dp(26), u.dp(14))
        cta.background = u.pressable(u.rounded(p.accent, 16f), 16f)
        cta.isClickable = true
        addView(cta, u.lpm(Ui.WRAP, Ui.WRAP, 0, 22, 0, 0))
    }

    fun set(iconRes: Int, titleText: String, bodyText: String, ctaLabel: String?, onCta: (() -> Unit)?) {
        iconView.setImageResource(iconRes)
        title.text = titleText
        body.text = bodyText
        if (ctaLabel != null && onCta != null) {
            cta.text = ctaLabel
            cta.visibility = View.VISIBLE
            cta.setOnClickListener { onCta() }
        } else {
            cta.visibility = View.GONE
        }
        if (lastTitle != titleText) {
            lastTitle = titleText
            alpha = 0f
            translationY = u.dp(8).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }
}

// ------------------------------------------------------------------ bottom navigation

class BottomNav(
    private val u: Ui,
    private val onSelect: (Int) -> Unit
) : LinearLayout(u.ctx) {

    private val p = u.p
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private val pills = ArrayList<View>()
    private val iconRes = intArrayOf(
        R.drawable.ic_home, R.drawable.ic_bookmark, R.drawable.ic_history, R.drawable.ic_settings
    )
    private val names = arrayOf("Home", "Saved", "History", "Settings")

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(p.surface)

        val line = View(u.ctx)
        line.setBackgroundColor(p.line)
        addView(line, LinearLayout.LayoutParams(Ui.MATCH, 1))

        val row = LinearLayout(u.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(u.dp(8), u.dp(8), u.dp(8), u.dp(8))

        for (i in 0 until 4) {
            val item = LinearLayout(u.ctx)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.background = u.ghost(false, 16f)
            item.isClickable = true
            item.contentDescription = names[i]
            item.setPadding(0, u.dp(2), 0, u.dp(4))

            val box = FrameLayout(u.ctx)
            val pill = View(u.ctx)
            pill.background = u.rounded(p.accentSoft, 16f)
            pill.alpha = 0f
            box.addView(pill, FrameLayout.LayoutParams(u.dp(56), u.dp(32), Gravity.CENTER))
            val img = ImageView(u.ctx)
            img.setImageResource(iconRes[i])
            box.addView(img, FrameLayout.LayoutParams(u.dp(22), u.dp(22), Gravity.CENTER))
            item.addView(box, LinearLayout.LayoutParams(u.dp(56), u.dp(32)))

            val label = u.text(names[i], 11.5f, p.muted, u.medium)
            item.addView(label, u.lpm(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0))

            item.setOnClickListener { onSelect(i) }
            row.addView(item, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

            icons.add(img)
            labels.add(label)
            pills.add(pill)
        }
        addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        select(0)
    }

    fun select(index: Int) {
        for (i in icons.indices) {
            val on = i == index
            icons[i].setColorFilter(if (on) p.accent else p.muted)
            labels[i].setTextColor(if (on) p.accent else p.muted)
            pills[i].animate().alpha(if (on) 1f else 0f).setDuration(160).start()
        }
    }
}
