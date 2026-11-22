package com.uk.linkpad

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The "Paper" design: warm cream and ink, one terracotta accent. */
class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val onDanger: Int,
    val tiles: IntArray,
    val tileText: Int
) {
    companion object {
        val LIGHT = Palette(
            dark = false,
            bg = 0xFFF6F1E9.toInt(),
            surface = 0xFFFFFDF8.toInt(),
            line = 0xFFE4DACB.toInt(),
            text = 0xFF1F1B16.toInt(),
            muted = 0xFF6F6455.toInt(),
            accent = 0xFFB24A24.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0xFFF3D9CB.toInt(),
            danger = 0xFFA32D2D.toInt(),
            dangerSoft = 0xFFF8E1DE.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            tiles = intArrayOf(
                0xFFF3D9CB.toInt(), 0xFFE3E6C8.toInt(), 0xFFD8E3EA.toInt(),
                0xFFEAD9E8.toInt(), 0xFFF0E2B8.toInt()
            ),
            tileText = 0xFF3B2A1E.toInt()
        )

        val DARK = Palette(
            dark = true,
            bg = 0xFF17130F.toInt(),
            surface = 0xFF211C17.toInt(),
            line = 0xFF3A322A.toInt(),
            text = 0xFFF2EBDF.toInt(),
            muted = 0xFFA89C8B.toInt(),
            accent = 0xFFE2825A.toInt(),
            onAccent = 0xFF24130B.toInt(),
            accentSoft = 0xFF3A2519.toInt(),
            danger = 0xFFF09595.toInt(),
            dangerSoft = 0xFF3B2220.toInt(),
            onDanger = 0xFF2A1210.toInt(),
            tiles = intArrayOf(
                0xFF3A2519.toInt(), 0xFF2B3020.toInt(), 0xFF1F2E36.toInt(),
                0xFF33263A.toInt(), 0xFF3A3219.toInt()
            ),
            tileText = 0xFFF2EBDF.toInt()
        )
    }
}

fun relTime(ts: Long): String {
    if (ts <= 0L) return "earlier"
    val now = System.currentTimeMillis()
    if (now - ts < 60000L) return "just now"
    return DateUtils.getRelativeTimeSpanString(
        ts, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}

class Ui(val ctx: Context, val p: Palette) {

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val density: Float = ctx.resources.displayMetrics.density

    val serif: Typeface = Typeface.SERIF
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val mono: Typeface = Typeface.MONOSPACE

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun dpf(v: Float): Float = v * density

    /** Sizes: positive numbers are dp, MATCH / WRAP / 0 are passed through unchanged. */
    private fun size(v: Int): Int = if (v > 0) dp(v) else v

    fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(size(w), size(h))

    fun lp(w: Int, h: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(size(w), size(h), weight)

    /** LinearLayout params; sizes and margins are in dp. */
    fun lpm(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val x = LinearLayout.LayoutParams(size(w), size(h))
        x.setMargins(dp(l), dp(t), dp(r), dp(b))
        return x
    }

    fun rounded(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.setCornerRadius(dpf(radiusDp))
        if (strokeDp > 0f) {
            var w = (strokeDp * density).toInt()
            if (w < 1) w = 1
            d.setStroke(w, strokeColor)
        }
        return d
    }

    fun circle(fill: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        return d
    }

    private fun rippleColor(): ColorStateList =
        ColorStateList.valueOf((p.accent and 0x00FFFFFF) or 0x2A000000)

    /** A rounded shape with a touch ripple on top. */
    fun pressable(content: Drawable, radiusDp: Float): RippleDrawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.setCornerRadius(dpf(radiusDp))
        return RippleDrawable(rippleColor(), content, mask)
    }

    /** A ripple with no fill, for icon buttons and plain tappable areas. */
    fun ghost(oval: Boolean, radiusDp: Float = 12f): RippleDrawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        if (oval) mask.shape = GradientDrawable.OVAL else mask.setCornerRadius(dpf(radiusDp))
        return RippleDrawable(rippleColor(), null, mask)
    }

    fun text(value: CharSequence, sp: Float, color: Int = p.text, face: Typeface = regular): TextView {
        val t = TextView(ctx)
        t.text = value
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.typeface = face
        t.includeFontPadding = false
        return t
    }

    fun icon(res: Int, sizeDp: Int, tint: Int): ImageView {
        val v = ImageView(ctx)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    /**
     * A round, tappable icon. fill = 0 gives a plain icon with a ripple,
     * any other color gives a filled circle.
     */
    fun iconButton(
        res: Int,
        description: String,
        tint: Int,
        sizeDp: Int,
        iconDp: Int,
        fill: Int = 0,
        onClick: () -> Unit
    ): FrameLayout {
        val f = FrameLayout(ctx)
        val img = ImageView(ctx)
        img.setImageResource(res)
        img.setColorFilter(tint)
        f.addView(img, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        if (fill != 0) {
            val mask = GradientDrawable()
            mask.shape = GradientDrawable.OVAL
            mask.setColor(Color.WHITE)
            f.background = RippleDrawable(rippleColor(), circle(fill), mask)
        } else {
            f.background = ghost(true)
        }
        f.contentDescription = description
        f.isClickable = true
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    fun tileColor(host: String): Int {
        val n = p.tiles.size
        val idx = ((host.hashCode() % n) + n) % n
        return p.tiles[idx]
    }

    fun hideKeyboard(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }
}
