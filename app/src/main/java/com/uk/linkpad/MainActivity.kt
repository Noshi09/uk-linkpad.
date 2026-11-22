package com.uk.linkpad

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), Host {

    companion object {
        private const val PREFS = "uk_linkpad"
        private const val KEY_THEME = "theme_mode"
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42
        private const val MAX_IMPORT_CHARS = 16000000
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var storeRef: LinkStore
    private lateinit var uiRef: Ui

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var mode = 0 // 0 = follow system, 1 = light, 2 = dark
    private var currentTab = TAB_HOME
    private var screens: List<Screen> = emptyList()
    private var dirty = BooleanArray(4)
    private var home: HomeScreen? = null
    private var saved: SavedScreen? = null
    private var history: HistoryScreen? = null
    private var nav: BottomNav? = null
    private var contentFrame: FrameLayout? = null
    private var snackBar: View? = null
    private val snackHide = Runnable { dismissSnack() }

    override val ui: Ui get() = uiRef
    override val store: LinkStore get() = storeRef
    override val activity: Activity get() = this

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        storeRef = LinkStore(this)
        storeRef.migrateLegacyIfNeeded(prefs)
        mode = readThemeMode()
        buildUi(TAB_HOME, "", "", LinkStore.FILTER_ALL, "")
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (screens.isNotEmpty()) dataChanged()
    }

    override fun onDestroy() {
        handler.removeCallbacks(snackHide)
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentTab != TAB_HOME) {
            show(TAB_HOME, true)
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ theme

    private fun readThemeMode(): Int {
        if (prefs.contains(KEY_THEME)) return prefs.getInt(KEY_THEME, 0)
        // version 1.0 stored a simple dark / light switch
        if (prefs.contains("dark")) return if (prefs.getBoolean("dark", false)) 2 else 1
        return 0
    }

    private fun isDark(): Boolean {
        if (mode == 1) return false
        if (mode == 2) return true
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    override fun themeMode(): Int = mode

    override fun setThemeMode(mode: Int) {
        this.mode = mode
        prefs.edit().putInt(KEY_THEME, mode).apply()
        rebuild()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(p: Palette) {
        val w = window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        w.statusBarColor = p.bg
        w.navigationBarColor = p.surface
        val decor = w.decorView
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val flags = decor.systemUiVisibility
        decor.systemUiVisibility = if (p.dark) flags and light.inv() else flags or light
    }

    // ------------------------------------------------------------------ building the UI

    private fun rebuild() {
        val draft = home?.currentText() ?: ""
        val savedQuery = saved?.currentQuery() ?: ""
        val savedFilter = saved?.currentFilter() ?: LinkStore.FILTER_ALL
        val historyQuery = history?.currentQuery() ?: ""
        buildUi(currentTab, draft, savedQuery, savedFilter, historyQuery)
    }

    private fun buildUi(tab: Int, draft: String, savedQuery: String, savedFilter: Int, historyQuery: String) {
        handler.removeCallbacks(snackHide)
        snackBar = null

        val pal = if (isDark()) Palette.DARK else Palette.LIGHT
        uiRef = Ui(this, pal)
        applySystemBars(pal)

        val homeScreen = HomeScreen(this, draft)
        val savedScreen = SavedScreen(this, savedQuery, savedFilter)
        val historyScreen = HistoryScreen(this, historyQuery)
        val settingsScreen = SettingsScreen(this)
        home = homeScreen
        saved = savedScreen
        history = historyScreen
        screens = listOf(homeScreen, savedScreen, historyScreen, settingsScreen)
        dirty = BooleanArray(4) { true }

        val content = FrameLayout(this)
        content.setBackgroundColor(pal.bg)
        for (sc in screens) {
            val v = sc.root
            v.visibility = View.GONE
            content.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        contentFrame = content

        val navBar = BottomNav(uiRef) { i -> show(i, true) }
        nav = navBar

        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(pal.bg)
        rootLayout.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        rootLayout.addView(navBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        setContentView(rootLayout)

        // Hide the bottom bar while the keyboard is open so the import box has room.
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootLayout.getWindowVisibleDisplayFrame(r)
            val hidden = rootLayout.rootView.height - r.bottom
            navBar.visibility = if (hidden > uiRef.dp(160)) View.GONE else View.VISIBLE
        }

        show(tab, false)
    }

    private fun show(tab: Int, animate: Boolean) {
        val list = screens
        val previous = currentTab
        for (i in list.indices) {
            val v = list[i].root
            if (i == tab) {
                v.visibility = View.VISIBLE
                if (dirty[i]) {
                    list[i].refresh()
                    dirty[i] = false
                }
                if (animate && i != previous) {
                    v.alpha = 0f
                    v.translationY = uiRef.dp(10).toFloat()
                    v.animate().alpha(1f).translationY(0f).setDuration(200).start()
                } else {
                    v.alpha = 1f
                    v.translationY = 0f
                }
            } else {
                v.animate().cancel()
                v.visibility = View.GONE
            }
        }
        currentTab = tab
        nav?.select(tab)
        list[tab].onShown()
        val focus = currentFocus
        if (focus != null && tab != TAB_HOME) uiRef.hideKeyboard(focus)
    }

    override fun goTo(tab: Int) {
        show(tab, true)
    }

    override fun dataChanged() {
        for (i in dirty.indices) dirty[i] = true
        if (screens.isEmpty()) return
        screens[currentTab].refresh()
        dirty[currentTab] = false
    }

    // ------------------------------------------------------------------ threads

    override fun background(work: () -> Unit) {
        try {
            io.execute {
                try {
                    work()
                } catch (e: Throwable) {
                    runOnUiThread { snack("Something went wrong. Please try again.") }
                }
            }
        } catch (e: Exception) {
            // the activity is closing, nothing to do
        }
    }

    override fun onMain(work: () -> Unit) {
        runOnUiThread { work() }
    }

    // ------------------------------------------------------------------ actions

    override fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            snack("No app found to open this link")
            return
        }
        // The link opened. Mark it on the saved link and add a history entry.
        // The saved link itself is never removed.
        try {
            store.recordOpen(url, UrlParser.keyOf(url), UrlParser.hostOf(url), System.currentTimeMillis())
        } catch (e: Exception) {
            snack("Opened, but the history could not be updated")
        }
        dataChanged()
    }

    override fun copyText(text: String, done: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Links", text))
            snack(done)
        } catch (e: Exception) {
            snack("Could not copy that. Try Export instead.")
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val type = i.type
        if (type == null || !type.startsWith("text/")) return
        val shared = i.getStringExtra(Intent.EXTRA_TEXT)
        if (shared.isNullOrBlank()) return
        home?.addText(shared)
        show(TAB_HOME, false)
        snack("Added to the import box. Tap Save all links.")
    }

    // ------------------------------------------------------------------ export / import

    override fun exportLinks() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TITLE, "uk-linkpad-links.txt")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            snack("No file manager found on this device")
        }
    }

    override fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            snack("No file manager found on this device")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_EXPORT) {
            doExport(uri)
        } else if (requestCode == REQ_IMPORT) {
            doImport(uri)
        }
    }

    private fun doExport(uri: Uri) {
        snack("Exporting\u2026")
        background {
            val urls = store.allUrls()
            try {
                val os = contentResolver.openOutputStream(uri, "wt")
                if (os == null) {
                    onMain { snack("Could not write the file") }
                    return@background
                }
                os.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (x in urls) {
                        w.write(x)
                        w.write("\n")
                    }
                }
                onMain {
                    snack("Exported " + fmt(urls.size) + " " + plural(urls.size, "link", "links"))
                }
            } catch (e: Exception) {
                onMain { snack("Export failed. Your links are untouched.") }
            }
        }
    }

    private fun doImport(uri: Uri) {
        snack("Reading file\u2026")
        background {
            try {
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) {
                    onMain { snack("Could not read that file") }
                    return@background
                }
                val sb = StringBuilder()
                var tooBig = false
                stream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (sb.length + n > MAX_IMPORT_CHARS) {
                            tooBig = true
                            break
                        }
                        sb.append(buf, 0, n)
                    }
                }
                if (tooBig) {
                    onMain { snack("That file is too large to import") }
                    return@background
                }
                val s = Importer.commit(store, sb.toString())
                onMain {
                    dataChanged()
                    snack(Importer.describe(s))
                }
            } catch (e: Exception) {
                onMain { snack("Could not read that file") }
            }
        }
    }

    // ------------------------------------------------------------------ snackbar

    override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) {
        val frame = contentFrame ?: return
        handler.removeCallbacks(snackHide)
        val old = snackBar
        if (old != null) {
            frame.removeView(old)
            snackBar = null
        }
        val u = uiRef
        val p = u.p
        val bg = if (p.dark) 0xFFF2EBDF.toInt() else 0xFF1F1B16.toInt()
        val fg = if (p.dark) 0xFF1F1B16.toInt() else 0xFFF6F1E9.toInt()
        val act = if (p.dark) 0xFFB24A24.toInt() else 0xFFF3B79D.toInt()

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = u.rounded(bg, 16f)
        bar.setPadding(u.dp(18), u.dp(2), u.dp(6), u.dp(2))
        bar.elevation = u.dpf(6f)

        val msg = u.text(message, 14f, fg, u.regular)
        msg.maxLines = 2
        msg.ellipsize = TextUtils.TruncateAt.END
        msg.setPadding(0, u.dp(12), u.dp(8), u.dp(12))
        bar.addView(msg, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        if (actionLabel != null && action != null) {
            val a = u.text(actionLabel, 14f, act, u.medium)
            a.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12))
            a.background = u.ghost(false, 12f)
            a.isClickable = true
            a.setOnClickListener {
                dismissSnack()
                action()
            }
            bar.addView(a, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }

        val params = FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM)
        params.setMargins(u.dp(16), 0, u.dp(16), u.dp(14))
        frame.addView(bar, params)
        snackBar = bar

        bar.alpha = 0f
        bar.translationY = u.dp(24).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        handler.postDelayed(snackHide, if (action != null) 6000L else 3200L)
    }

    private fun dismissSnack() {
        val bar = snackBar ?: return
        snackBar = null
        handler.removeCallbacks(snackHide)
        bar.animate().alpha(0f).translationY(uiRef.dp(24).toFloat()).setDuration(160).withEndAction {
            (bar.parent as? ViewGroup)?.removeView(bar)
        }.start()
    }

    // ------------------------------------------------------------------ confirmation dialog

    override fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean,
        onConfirm: () -> Unit
    ) {
        val u = uiRef
        val p = u.p
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = u.rounded(p.surface, 26f)
        card.setPadding(u.dp(24), u.dp(24), u.dp(18), u.dp(16))

        card.addView(u.text(title, 22f, p.text, u.serif), u.lp(Ui.MATCH, Ui.WRAP))
        val m = u.text(message, 15f, p.muted)
        m.setLineSpacing(0f, 1.25f)
        card.addView(m, u.lpm(Ui.MATCH, Ui.WRAP, 0, 12, 6, 22))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL

        val cancel = u.text("Cancel", 15f, p.text, u.medium)
        cancel.gravity = Gravity.CENTER
        cancel.setPadding(u.dp(20), u.dp(14), u.dp(20), u.dp(14))
        cancel.background = u.ghost(false, 14f)
        cancel.isClickable = true
        cancel.setOnClickListener { d.dismiss() }
        row.addView(cancel, u.lp(Ui.WRAP, Ui.WRAP))

        val fill = if (destructive) p.danger else p.accent
        val ok = u.text(confirmLabel, 15f, if (destructive) p.onDanger else p.onAccent, u.medium)
        ok.gravity = Gravity.CENTER
        ok.setPadding(u.dp(22), u.dp(14), u.dp(22), u.dp(14))
        ok.background = u.pressable(u.rounded(fill, 14f), 14f)
        ok.isClickable = true
        ok.setOnClickListener {
            d.dismiss()
            onConfirm()
        }
        row.addView(ok, u.lpm(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0))
        card.addView(row, u.lp(Ui.MATCH, Ui.WRAP))

        d.setContentView(card)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxWidth = u.dp(420)
            val width = minOf((resources.displayMetrics.widthPixels * 0.9f).toInt(), maxWidth)
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            val lp = w.attributes
            lp.windowAnimations = android.R.style.Animation_Dialog
            lp.dimAmount = 0.55f
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
    }
}
