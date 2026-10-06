package com.purrfectbytes.android.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import java.util.Locale
import kotlin.math.max

/**
 * Draws the frames of a video: the text on the background, one character highlighted
 * by a red box with the cat logo above it, and the QR code in the corner. Speech that
 * is slowed down is marked "SLOW" in the opposite corner.
 *
 * Android lays the text out, so every script is broken into lines and shaped the way
 * it is in any other app: Chinese and Japanese wrap between characters, Arabic letters
 * join and run right to left, Hindi vowel signs stay with their consonant.
 *
 * The bitmaps passed in must already have their final size; see the constants below.
 */
internal class FrameRenderer(
    private val background: Bitmap?,
    private val qrCode: Bitmap?,
    private val logo: Bitmap?,
    /** Chooses between the Chinese, Japanese and Korean shapes of shared characters. */
    private val locale: Locale? = null
) {
    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val LOGO_SIZE = 80
        const val QR_SIZE = 120

        private const val QR_MARGIN = 20
        private const val QR_OPACITY = 0.9f
        private const val LOGO_GAP = 10

        private const val SIDE_PADDING = 120
        const val TEXT_WIDTH = WIDTH - 2 * SIDE_PADDING

        /**
         * Where the lines of a conversation begin: to the right of the QR code, which
         * would otherwise lie over the names of the speakers in the lower lines.
         */
        const val START_PADDING = QR_MARGIN + QR_SIZE + 20
        const val START_ALIGNED_TEXT_WIDTH = WIDTH - START_PADDING - SIDE_PADDING

        /** Long text is drawn smaller, down to the minimum, until it fits the frame. */
        const val MAX_TEXT_SIZE = 64f
        const val MIN_TEXT_SIZE = 28f
        private const val TEXT_SIZE_STEP = 4f

        /** Space kept free above the first line for the logo. */
        private const val TOP_MARGIN = (LOGO_SIZE + 2 * LOGO_GAP).toFloat()
        private const val MAX_BLOCK_HEIGHT = HEIGHT - 2 * TOP_MARGIN

        // Measures as parts of the text size (80, 60, 60 and 10 pixels at size 64)
        private const val LINE_HEIGHT = 1.25f
        private const val LINE_TOP_TO_BASELINE = 0.9375f
        private const val BOX_ABOVE_BASELINE = 0.9375f
        private const val BOX_BELOW_BASELINE = 0.15625f
        private const val BOX_SIDE_PADDING = 4f

        // The badge of the web app (video_generation.py, _draw_slow_badge)
        private const val BADGE_TEXT = "SLOW"
        private const val BADGE_TEXT_SIZE = 28f
        private const val BADGE_MARGIN = 20f
        private const val BADGE_PADDING_X = 18f
        private const val BADGE_PADDING_Y = 10f

        private val TEXT_COLOR = "#50321e".toColorInt()
        private val HIGHLIGHT_TEXT_COLOR = Color.WHITE
        private val HIGHLIGHT_BOX_COLOR = "#dc3232".toColorInt()
        private val FALLBACK_BACKGROUND = "#1e1e28".toColorInt()
    }

    /**
     * One line of text, laid out on its own. [left] is where its layout begins in the
     * frame: a line in the middle lies across the whole frame, a line that starts at the
     * side begins at the margin.
     */
    internal class Line(
        val start: Int,
        val end: Int,
        val layout: StaticLayout,
        val baseline: Float,
        val left: Float
    )

    /** Text that has been laid out and drawn once, ready to be highlighted frame by frame. */
    class PreparedText internal constructor(
        val text: String,
        val units: List<HighlightUnit>,
        val textSize: Float,
        internal val lines: List<Line>,
        internal val paint: TextPaint,
        internal val base: Bitmap
    ) {
        val lineCount: Int get() = lines.size

        fun lineTexts(): List<String> = lines.map { text.substring(it.start, it.end) }

        fun recycle() = base.recycle()
    }

    /**
     * Lays [text] out and draws it. The lines are put in the middle of the frame, or,
     * with [startAligned], all begin at the same side, which is how a conversation is
     * read: the names of the speakers stand below each other.
     */
    fun prepare(text: String, startAligned: Boolean = false): PreparedText {
        val display = HighlightTiming.displayText(text)

        val width = if (startAligned) START_ALIGNED_TEXT_WIDTH else TEXT_WIDTH
        var size = MAX_TEXT_SIZE
        var paint = paintOf(size)
        var breaks = breakLines(display, paint, width)
        while (breaks.lineCount * size * LINE_HEIGHT > MAX_BLOCK_HEIGHT && size > MIN_TEXT_SIZE) {
            size = max(MIN_TEXT_SIZE, size - TEXT_SIZE_STEP)
            paint = paintOf(size)
            breaks = breakLines(display, paint, width)
        }

        val lineHeight = size * LINE_HEIGHT
        // Centered, but never so high that the logo above the first line is cut off.
        // Text that is too long even at the smallest size runs out at the bottom.
        val top = max((HEIGHT - breaks.lineCount * lineHeight) / 2f, TOP_MARGIN)

        val lines = (0 until breaks.lineCount).mapNotNull { index ->
            val start = breaks.getLineStart(index)
            val end = breaks.getLineVisibleEnd(index)
            if (end <= start) return@mapNotNull null
            val rightToLeft = breaks.getParagraphDirection(index) == Layout.DIR_RIGHT_TO_LEFT
            Line(
                start = start,
                end = end,
                layout = singleLine(display.substring(start, end), paint, rightToLeft, startAligned),
                baseline = top + index * lineHeight + size * LINE_TOP_TO_BASELINE,
                // A line from the right begins at the margin on the right, where its layout ends
                left = if (startAligned && !rightToLeft) START_PADDING.toFloat() else 0f
            )
        }

        val base = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(base)
        if (background != null) {
            canvas.drawBitmap(background, 0f, 0f, null)
        } else {
            canvas.drawColor(FALLBACK_BACKGROUND)
        }
        paint.color = TEXT_COLOR
        lines.forEach { drawLine(canvas, it) }

        return PreparedText(display, HighlightTiming.units(display), size, lines, paint, base)
    }

    /**
     * A new frame with unit [unitIndex] highlighted; -1 highlights nothing. With
     * [slowBadge] the frame is marked as one of slowed down speech.
     */
    fun render(prepared: PreparedText, unitIndex: Int, slowBadge: Boolean = false): Bitmap {
        val frame = prepared.base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(frame)

        if (slowBadge) drawSlowBadge(canvas)

        highlightBox(prepared, unitIndex)?.let { (line, box) ->
            canvas.drawRect(box, Paint().apply { color = HIGHLIGHT_BOX_COLOR })

            // The same line drawn again in white, but only as wide as the box: the character
            // keeps the shape it has in its word. Parts that reach above or below the box,
            // like the tail of a "g" or a vowel sign under a Hindi letter, turn white too.
            canvas.withClip(
                box.left,
                line.baseline - prepared.textSize * LINE_HEIGHT,
                box.right,
                line.baseline + prepared.textSize * LINE_HEIGHT / 2
            ) {
                prepared.paint.color = HIGHLIGHT_TEXT_COLOR
                drawLine(this, line)
                prepared.paint.color = TEXT_COLOR
            }

            if (logo != null) {
                val x = (box.centerX() - LOGO_SIZE / 2f).coerceIn(0f, (WIDTH - LOGO_SIZE).toFloat())
                val y = (box.top - LOGO_GAP - LOGO_SIZE).coerceIn(0f, (HEIGHT - LOGO_SIZE).toFloat())
                canvas.drawBitmap(logo, x, y, null)
            }
        }

        if (qrCode != null) {
            val paint = Paint().apply { alpha = (255 * QR_OPACITY).toInt() }
            canvas.drawBitmap(qrCode, QR_MARGIN.toFloat(), (HEIGHT - QR_SIZE - QR_MARGIN).toFloat(), paint)
        }
        return frame
    }

    /** The red box behind unit [unitIndex] and the line it is on, or null if it is not on screen. */
    internal fun highlightBox(prepared: PreparedText, unitIndex: Int): Pair<Line, RectF>? {
        val unit = prepared.units.getOrNull(unitIndex) ?: return null
        val line = prepared.lines.firstOrNull { unit.start >= it.start && unit.start < it.end } ?: return null

        val path = Path()
        line.layout.getSelectionPath(unit.start - line.start, minOf(unit.end, line.end) - line.start, path)
        val extent = RectF()
        path.computeBounds(extent, true)
        if (extent.width() <= 0f) return null

        return line to RectF(
            line.left + extent.left - BOX_SIDE_PADDING,
            line.baseline - prepared.textSize * BOX_ABOVE_BASELINE,
            line.left + extent.right + BOX_SIDE_PADDING,
            line.baseline + prepared.textSize * BOX_BELOW_BASELINE
        )
    }

    private fun drawLine(canvas: Canvas, line: Line) {
        canvas.withTranslation(line.left, line.baseline - line.layout.getLineBaseline(0)) {
            line.layout.draw(this)
        }
    }

    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BADGE_TEXT_SIZE
        typeface = Typeface.DEFAULT_BOLD
    }

    /** What the letters of the badge cover, measured from where they are drawn. */
    private val badgeLetters = Rect().also { badgePaint.getTextBounds(BADGE_TEXT, 0, BADGE_TEXT.length, it) }

    /** Where the badge of slowed down speech is drawn: the corner at the top right. */
    internal fun slowBadgeBox(): RectF {
        val width = badgeLetters.width() + 2 * BADGE_PADDING_X
        val height = badgeLetters.height() + 2 * BADGE_PADDING_Y
        val left = WIDTH - BADGE_MARGIN - width
        return RectF(left, BADGE_MARGIN, left + width, BADGE_MARGIN + height)
    }

    private fun drawSlowBadge(canvas: Canvas) {
        val box = slowBadgeBox()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HIGHLIGHT_BOX_COLOR }
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, fill)
        canvas.drawText(
            BADGE_TEXT,
            box.left + BADGE_PADDING_X - badgeLetters.left,
            box.top + BADGE_PADDING_Y - badgeLetters.top,
            badgePaint
        )
    }

    private fun paintOf(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        locale?.let { textLocale = it }
    }

    /**
     * Lets Android decide where the lines break. A layout built this way fills each line
     * before starting the next and never hyphenates.
     */
    private fun breakLines(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    /**
     * One line, in the middle of the frame or beginning at its side. Its layout is wider
     * than the space the line was broken for, so it can never break again: as wide as the
     * frame, or reaching from where the line begins to the far edge of the frame.
     */
    private fun singleLine(
        text: String,
        paint: TextPaint,
        rightToLeft: Boolean,
        startAligned: Boolean
    ): StaticLayout {
        val width = when {
            !startAligned -> WIDTH
            rightToLeft -> WIDTH - SIDE_PADDING
            else -> WIDTH - START_PADDING
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (startAligned) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setTextDirection(if (rightToLeft) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .build()
    }
}
