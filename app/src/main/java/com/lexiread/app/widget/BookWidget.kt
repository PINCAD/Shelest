package com.lexiread.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import com.lexiread.app.MainActivity
import com.lexiread.app.R
import com.lexiread.app.data.db.AppDatabase
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.repository.ReadingStatsRepository
import java.io.File
import kotlin.math.roundToInt

/**
 * The three shapes the same widget comes in.
 *
 * Public, unlike the rest of this file, because the providers name it in a protected signature
 * and Kotlin will not let a visible declaration be described in terms of an invisible one.
 */
enum class WidgetSize(val layout: Int) {
    SMALL(R.layout.widget_book_small),
    MEDIUM(R.layout.widget_book_medium),
    LARGE(R.layout.widget_book_large)
}

/**
 * Everything the home-screen widgets know how to do, in one place.
 *
 * There are three providers because Android has no way to say "one widget, pick a size" — each
 * shape needs its own receiver and its own description in the picker. What they must not have is
 * three ideas of what a book looks like, so the providers are three lines each and all of the
 * thinking is here.
 *
 * What the widgets are for: getting back into a book costs a tap on the launcher, a wait for the
 * library, and a tap on the cover — three steps between wanting to read and reading, and the
 * reason a book gets abandoned at forty percent is rarely the book.
 *
 * Everything here runs in a broadcast, which is a few seconds of process time and no more, so
 * the work is: one small query, one pass over the reading days, one scaled bitmap. No Flows are
 * collected — there would be nothing left alive to deliver to.
 */
internal object BookWidget {

    const val ACTION_OPEN_BOOK = "com.lexiread.app.widget.OPEN_BOOK"
    const val EXTRA_BOOK_ID    = "bookId"
    const val EXTRA_LISTEN     = "listen"

    /** The current book plus [SHELF_SIZE] behind it, which is all the largest widget shows. */
    private const val SHELF_SIZE = 3

    /**
     * Target size of the cover on screen, in pixels at a generous density.
     *
     * A cover this size is about 430 KB in memory, and the whole RemoteViews tree has to fit
     * through a binder transaction of roughly a megabyte — which is why [coverBitmap] scales to
     * this exactly rather than trusting inSampleSize, whose halving can only land within a
     * factor of two of a target and on the wrong side of it.
     */
    private const val COVER_WIDTH_PX  = 270
    private const val COVER_HEIGHT_PX = 400

    /** Past this the minutes are noise: "~41 ч" is the same answer as "~41 ч 39 мин". */
    private const val COARSE_FROM_HOURS = 10

    data class Shelf(
        val current: Book?,
        val next: List<Book>,
        val streakDays: Int,
        /** This reader's measured pace, or the assumed one. See ReadingStatsRepository. */
        val charsPerMinute: Int = ReadingStatsRepository.ASSUMED_CHARS_PER_MINUTE
    )

    suspend fun load(context: Context): Shelf {
        val db = AppDatabase.getInstance(context)
        val books = db.bookDao().getRecentBooks(SHELF_SIZE + 1)
        // The streak is counted by the same code the statistics screen uses, so the widget and
        // the app can never show two different numbers for the same day.
        val stats = ReadingStatsRepository(context, db.readingDayDao()).currentStats()
        return Shelf(books.firstOrNull(), books.drop(1), stats.streakDays, stats.charsPerMinute)
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────

    fun render(context: Context, shelf: Shelf, size: WidgetSize): RemoteViews {
        val views = RemoteViews(context.packageName, size.layout)
        val book = shelf.current

        if (book == null) {
            views.setViewVisibility(R.id.widget_content, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            return views
        }

        views.setViewVisibility(R.id.widget_content, View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        val percent = book.scrollPercent.coerceIn(0f, 100f).roundToInt()
        views.setTextViewText(R.id.widget_title, book.title)
        views.setProgressBar(R.id.widget_progress, 100, percent, false)
        views.setImageViewCover(context, book)

        when (size) {
            // Nothing but the figure: at two cells the bar is the picture and the percentage
            // is its caption, and a title is already on the cover above it.
            WidgetSize.SMALL -> {
                views.setTextViewText(R.id.widget_meta, "$percent%")
            }
            WidgetSize.MEDIUM -> {
                views.setTextViewText(R.id.widget_author, book.author.ifBlank { "Без автора" })
                views.setTextViewText(R.id.widget_percent, "$percent%")
                views.setTextViewText(
                    R.id.widget_meta,
                    meta(book, percent, shelf.streakDays, shelf.charsPerMinute)
                )
            }
            WidgetSize.LARGE -> {
                views.setTextViewText(R.id.widget_author, book.author.ifBlank { "Без автора" })
                views.setTextViewText(R.id.widget_percent, "$percent%")
                // The streak has its own place up in the header here, so it is kept out of the
                // line under the bar rather than said twice.
                views.setTextViewText(
                    R.id.widget_meta,
                    meta(book, percent, streakDays = 0, charsPerMinute = shelf.charsPerMinute)
                )
                if (shelf.streakDays > 1) {
                    views.setViewVisibility(R.id.widget_streak, View.VISIBLE)
                    views.setTextViewText(
                        R.id.widget_streak,
                        "${shelf.streakDays} ${days(shelf.streakDays)} подряд"
                    )
                } else {
                    views.setViewVisibility(R.id.widget_streak, View.GONE)
                }
                views.fillShelf(context, shelf.next)
            }
        }

        // The whole face opens the book, and so does the first button. Two ways to the same
        // place is not redundancy here: the buttons are small, and the card is what the thumb
        // actually lands on.
        views.setOnClickPendingIntent(R.id.widget_root, openBook(context, book.id, listen = false))
        if (size != WidgetSize.SMALL) {
            views.setOnClickPendingIntent(R.id.widget_read,   openBook(context, book.id, listen = false))
            views.setOnClickPendingIntent(R.id.widget_listen, openBook(context, book.id, listen = true))
        }
        return views
    }

    /** The three rows under the divider on the largest widget. */
    private fun RemoteViews.fillShelf(context: Context, next: List<Book>) {
        if (next.isEmpty()) {
            setViewVisibility(R.id.widget_shelf, View.GONE)
            return
        }
        setViewVisibility(R.id.widget_shelf, View.VISIBLE)

        val rows = listOf(
            Triple(R.id.widget_next_1, R.id.widget_next_1_title, R.id.widget_next_1_meta),
            Triple(R.id.widget_next_2, R.id.widget_next_2_title, R.id.widget_next_2_meta),
            Triple(R.id.widget_next_3, R.id.widget_next_3_title, R.id.widget_next_3_meta)
        )
        rows.forEachIndexed { index, (row, title, meta) ->
            val book = next.getOrNull(index)
            if (book == null) {
                setViewVisibility(row, View.GONE)
                return@forEachIndexed
            }
            setViewVisibility(row, View.VISIBLE)
            setTextViewText(title, book.title)
            setTextViewText(meta, "${book.scrollPercent.coerceIn(0f, 100f).roundToInt()}%")
            // Request codes have to differ from the two the card above uses, and from each
            // other, or all these rows would open whichever book was wired up first.
            setOnClickPendingIntent(row, openBook(context, book.id, listen = false, code = 10 + index))
        }
    }

    /**
     * The line under the progress bar: how much of the book is left, and — where there is no
     * better place for it — how many days in a row this has been going on.
     *
     * Each part is dropped when it has nothing to say rather than shown empty: a book with no
     * character count cannot be given a time, and "1 день подряд" is not a streak, it is today.
     */
    private fun meta(book: Book, percent: Int, streakDays: Int, charsPerMinute: Int): String {
        val parts = mutableListOf<String>()
        remaining(book, percent, charsPerMinute)?.let { parts += it }
        if (streakDays > 1) parts += "$streakDays ${days(streakDays)} подряд"
        return parts.joinToString(" · ")
    }

    /**
     * Roughly how long the rest of the book will take.
     *
     * [charsPerMinute] is this reader's own pace once they have read enough for it to be
     * measured, and a middling assumed one until then — either way an average, which is why the
     * answer carries a tilde and gets coarser the further out it reaches. "Ещё ~41 час 39 минут"
     * is precision the estimate does not have and nobody asked for; past ten hours the minutes
     * are dropped entirely.
     */
    private fun remaining(book: Book, percent: Int, charsPerMinute: Int): String? {
        if (book.totalChars <= 0 || charsPerMinute <= 0) return null
        val left = book.totalChars.toLong() * (100 - percent) / 100
        val minutes = (left / charsPerMinute).toInt()
        if (minutes < 1) return null

        val hours = minutes / 60
        return when {
            hours >= COARSE_FROM_HOURS -> "ещё ~$hours ч"
            hours >= 1 && minutes % 60 == 0 -> "ещё ~$hours ч"
            hours >= 1 -> "ещё ~$hours ч ${minutes % 60} мин"
            else -> "ещё ~$minutes мин"
        }
    }

    private fun days(n: Int): String {
        // Russian counts three ways; getting it wrong is the kind of thing a reader notices.
        val mod100 = n % 100
        if (mod100 in 11..14) return "дней"
        return when (n % 10) {
            1       -> "день"
            2, 3, 4 -> "дня"
            else    -> "дней"
        }
    }

    // ── The cover ────────────────────────────────────────────────────────────────

    private fun RemoteViews.setImageViewCover(context: Context, book: Book) {
        val bitmap = book.coverPath?.let { coverBitmap(it) }
        if (bitmap != null) {
            setImageViewBitmap(R.id.widget_cover, bitmap)
        } else {
            setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        }
    }

    /**
     * The cover, decoded no larger than it will be drawn and with its corners rounded.
     *
     * Rounding happens here rather than in the layout because a RemoteViews ImageView has no
     * clip to give it — the launcher inflates the tree in its own process and there is nowhere
     * to put an outline provider. The bitmap arrives round or it does not arrive round.
     *
     * Size is not a matter of taste either: a RemoteViews tree crosses a process boundary and is
     * refused outright past about a megabyte, so a full-size cover does not fail to look right,
     * it fails to appear.
     */
    private fun coverBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_WIDTH_PX &&
                   bounds.outHeight / (sample * 2) >= COVER_HEIGHT_PX) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            rounded(capped(decoded))
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The cover brought down to at most [COVER_WIDTH_PX] × [COVER_HEIGHT_PX], keeping its shape.
     *
     * inSampleSize alone is not a size limit: it halves, so a 900-pixel cover asked for 270 comes
     * back at 450 — nearly three times the pixels, and the widget is refused at the process
     * boundary rather than drawn small. Never enlarges: a cover smaller than the target is left
     * as it is and the ImageView stretches it, which costs nothing to carry across.
     */
    private fun capped(source: Bitmap): Bitmap {
        val scale = minOf(
            COVER_WIDTH_PX.toFloat() / source.width,
            COVER_HEIGHT_PX.toFloat() / source.height
        )
        if (scale >= 1f) return source
        return try {
            val scaled = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
            if (scaled !== source) source.recycle()
            scaled
        } catch (e: OutOfMemoryError) {
            source
        }
    }

    private fun rounded(source: Bitmap): Bitmap {
        // Proportional to the cover rather than a fixed number of pixels: the bitmap here has
        // been through an arbitrary power-of-two reduction, so a constant radius would come out
        // a different size on every book.
        val radius = source.width * CORNER_FRACTION
        return try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(
                RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                radius, radius, paint
            )
            output
        } catch (e: OutOfMemoryError) {
            source
        }
    }

    private const val CORNER_FRACTION = 0.055f

    // ── Getting back into the app ────────────────────────────────────────────────

    private fun openBook(
        context: Context,
        bookId: Long,
        listen: Boolean,
        code: Int = if (listen) 2 else 1
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_BOOK)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_LISTEN, listen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Two intents that differ only in their extras are the same intent as far as the
        // PendingIntent cache is concerned, so without distinct request codes every button on
        // the widget would do whichever was created first.
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ── Keeping them current ─────────────────────────────────────────────────────

    /**
     * How long the widgets are left alone after being redrawn.
     *
     * Progress is written down on every page turn, and while a book is being read aloud the
     * pages turn themselves — without this they would be rebuilt, bitmaps and all, several
     * times a minute for a percentage that has moved by one.
     */
    private const val QUIET_PERIOD_MS = 15_000L

    @Volatile
    private var lastRefresh = 0L

    private val PROVIDERS = listOf(
        SmallBookWidget::class.java,
        CurrentBookWidget::class.java,
        LargeBookWidget::class.java
    )

    /**
     * Redraw whichever widgets have been placed.
     *
     * Called from wherever the shelf changes — a book imported, deleted, or read a little
     * further. Cheap and safe when none has been placed: the id lookups come back empty and
     * nothing is broadcast.
     *
     * @param force skips the quiet period, for the changes that must be seen at once — a book
     *   appearing or disappearing, rather than a percentage creeping up.
     */
    fun refresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < QUIET_PERIOD_MS) return

        val manager = AppWidgetManager.getInstance(context) ?: return
        var sent = false
        for (provider in PROVIDERS) {
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, provider))
            } catch (e: Exception) {
                continue
            }
            if (ids == null || ids.isEmpty()) continue
            sent = true
            context.sendBroadcast(
                Intent(context, provider)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
        // Only start the quiet period once something was actually redrawn, so a reader who adds
        // their first widget does not have to wait out a period that began before it existed.
        if (sent) lastRefresh = now
    }
}
