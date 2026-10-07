package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.content.res.ResourcesCompat
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

/**
 * kudcrafts: small helpers for the web-like look of the fdroid flavor: relative times ("3m", "2h", "Yesterday"),
 * letter avatars for apps without an icon, and tag chips. The play flavor keeps upstream's rendering.
 */
object KcStyle {
    val enabled = BuildConfig.FLAVOR == "fdroid"

    /** Like the web inbox: 45s -> "now", 3m, 5h, Yesterday, Mon, 6 Oct, 6 Oct 2025 */
    fun relativeTime(timestampSeconds: Long, nowMillis: Long = System.currentTimeMillis(), context: Context? = null): String {
        if (timestampSeconds <= 0L) return ""
        val diff = nowMillis / 1000 - timestampSeconds
        if (diff < 60) return "now"
        if (diff < 3600) return "${diff / 60}m"
        val then = Calendar.getInstance().apply { timeInMillis = timestampSeconds * 1000 }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay || diff < 6 * 3600) return "${diff / 3600}h"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) {
            return context?.getString(R.string.main_item_date_yesterday) ?: "Yesterday"
        }
        val date = Date(timestampSeconds * 1000)
        if (diff < 6 * 86400) return android.text.format.DateFormat.format("EEE", date).toString()
        if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) return android.text.format.DateFormat.format("d MMM", date).toString()
        return android.text.format.DateFormat.format("d MMM yyyy", date).toString()
    }

    fun fullTime(timestampSeconds: Long): String {
        val date = Date(timestampSeconds * 1000)
        return android.text.format.DateFormat.format("EEE, d MMM yyyy", date).toString() + ", " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    }

    // Soft pastel discs like the web's letter avatars (K green, T lavender, L peach ...)
    private val LIGHT = listOf(0xFFDCF1E6 to 0xFF1B6B45, 0xFFEDE4FB to 0xFF6B3FBF, 0xFFFDE8DA to 0xFFA6531B,
        0xFFE1ECFB to 0xFF2F5FAF, 0xFFFBE3EA to 0xFFAF3157, 0xFFF1EFDC to 0xFF7A6A12)
    private val DARK = listOf(0xFF173A2A to 0xFF8FDCB4, 0xFF2D2347 to 0xFFC9B2FF, 0xFF3D2817 to 0xFFF5B98C,
        0xFF1C2B45 to 0xFF9FC2FF, 0xFF3D1A26 to 0xFFF4A3BC, 0xFF35321A to 0xFFE5D37A)

    fun letterAvatar(context: Context, name: String, sizePx: Int): Bitmap {
        val palette = if (isDarkThemeOn(context)) DARK else LIGHT
        val (bg, fg) = palette[abs(name.lowercase().hashCode()) % palette.size]
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bg.toInt()
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.color = fg.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sizePx * 0.40f
        paint.typeface = ResourcesCompat.getFont(context, R.font.instrument_sans)?.let { Typeface.create(it, 600, false) } ?: Typeface.DEFAULT_BOLD
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, sizePx / 2f, y, paint)
        return bitmap
    }

    /** "facemap  perf" as small outlined chips, like the web's tag pills */
    fun tagChips(context: Context, tags: List<String>): CharSequence {
        val builder = SpannableStringBuilder()
        tags.forEachIndexed { i, tag ->
            if (i > 0) builder.append(" ")
            val start = builder.length
            builder.append(tag)
            builder.setSpan(ChipSpan(context), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private class ChipSpan(context: Context) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private val padH = 7 * density
        private val stroke = context.getColor(R.color.kc_line_strong)
        private val text = context.getColor(R.color.kc_ink_3)
        private val radius = 6 * density

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val m = paint.fontMetricsInt
                it.ascent = m.ascent - (3 * density).toInt(); it.descent = m.descent + (3 * density).toInt()
                it.top = it.ascent; it.bottom = it.descent
            }
            return (paint.measureText(text, start, end) + 2 * padH + 4 * density).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val w = paint.measureText(text, start, end) + 2 * padH
            val m = paint.fontMetrics
            val rect = RectF(x, y + m.ascent - 3 * density, x + w, y + m.descent + 3 * density)
            val old = paint.color
            val oldStyle = paint.style
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = stroke
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = this.text
            canvas.drawText(text, start, end, x + padH, y.toFloat(), paint)
            paint.color = old
            paint.style = oldStyle
        }
    }
}
