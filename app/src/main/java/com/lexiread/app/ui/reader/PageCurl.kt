package com.lexiread.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The "как в книге" page turn, drawn natively.
 *
 * It used to live in the WebView, as three copies of the chapter and a rotated
 * `overflow:hidden` moved by JavaScript. That could not be made smooth and the reason is
 * structural rather than a missing optimisation: a fold line at an angle is not a clip
 * any browser compositor can apply as a rectangle, so every frame went back to the main
 * thread and repainted a chapter-sized multicolumn flow underneath it.
 *
 * A native reader does not have that problem because it never animates text — it animates
 * a picture of text. This does the same. The page being turned is captured once, before
 * the finger has moved, and from then until the turn lands the screen is two draws of one
 * bitmap and a couple of gradients. Nothing relayouts, nothing repaints, and the cost no
 * longer depends on how long the chapter is.
 *
 * The WebView keeps the gesture — it is the only thing that knows whether the finger is
 * on a word being selected, and it already handles that correctly — and it keeps drawing
 * the page being uncovered, which shows through wherever this overlay leaves transparent.
 * Only the sheet that folds is a photograph. That division is why the overlay must cover
 * the whole screen while the fold is closed: the document underneath has already been sent
 * to the destination page, and anything this leaves uncovered shows it.
 */

/** Pixels of shadow the fold throws onto the page it is uncovering. */
private const val FOLD_SHADOW_WIDTH = 46f

/** Corner speed, px/s, past which a flick decides the turn regardless of position. */
private const val FLICK_SPEED = 450f

/**
 * How far the corner may stray from the height it was grabbed at, per pixel pulled
 * across. tan(28°) — the crease can lean, the way paper does when you pull a corner
 * diagonally, but it cannot swing round to horizontal.
 *
 * Without this the fold followed the finger's height exactly, and since a finger drifts
 * up or down far more than it means to, a page pulled two centimetres sideways could end
 * up hinged along its top edge with the text upside down. The sheet is attached to the
 * book; it is not a loose leaf being waved about.
 */
private const val MAX_TILT = 0.53f

/**
 * Fraction of the page width that must be pulled before the crease is allowed to lean at
 * all. A finger drifts vertically before it has decided which way it is going, and a lean
 * granted at that moment is the worst possible time for one: the crease is still near the
 * edge it started from, so a few degrees there sweep the far side of the fold across the
 * whole screen rather than across a corner.
 */
private const val TILT_DEADZONE = 0.08f

@Stable
internal class CurlController(private val scope: CoroutineScope) {

    /** The page being turned. Null means there is nothing to draw and no turn running. */
    var sheet by mutableStateOf<ImageBitmap?>(null)
        private set


    /**
     * A still of the page held over the top of everything.
     *
     * Turning back folds the *previous* page, and a picture can only be taken of what is
     * on screen — which the previous page, by definition, is not. So it is fetched ahead
     * of time by jumping the document there and back; this is what that jump happens
     * behind. It is a photograph of the very pixels it covers, so nothing appears to
     * happen, and it is up for about four frames while nobody is touching anything.
     */
    var cover by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Where the pulled corner is now. The entire animation state. */
    var corner by mutableStateOf(Offset.Zero)
        private set

    val live: Boolean get() = sheet != null || cover != null

    var from = 0; private set
    var to = 0; private set
    var forward = true; private set

    /**
     * Bumped on every turn so the caller can hang a one-shot effect off it — the WebView
     * underneath has to be moved to the destination page, but only after this overlay has
     * had a frame to cover it.
     */
    var session by mutableStateOf(0)
        private set

    private var pageW = 1f
    private var job: Job? = null

    /**
     * The height the corner was grabbed at. It does not move during a turn — the finger
     * moves, the corner follows it, and the bisector between the two is what tilts — so
     * the drawing code needs it to know where the corner started from.
     */
    var grabY = 0f
        private set

    // Corner speed along x in px/s, smoothed. Measured from the corner's own path rather
    // than the raw touch stream, so a finger that has run past the end of the travel
    // correctly reads as no longer moving the paper.
    private var vx = 0f
    private var lastX = 0f
    private var lastT = 0L

    private val restX: Float get() = pageW
    private val doneX: Float get() = -pageW

    fun begin(bitmap: ImageBitmap, pageWidth: Float, grabHeight: Float, from: Int, to: Int) {
        job?.cancel(); job = null
        this.from = from
        this.to = to
        forward = to > from
        pageW = if (pageWidth > 1f) pageWidth else 1f
        grabY = grabHeight
        cover = null
        sheet = bitmap
        corner = Offset(if (forward) restX else doneX, grabHeight)
        vx = 0f
        lastX = corner.x
        lastT = System.nanoTime()
        session++
    }

    fun move(x: Float, y: Float) {
        if (sheet == null) return
        val px = x.coerceIn(-pageW, pageW)
        // The crease may lean, but only as much as the paper has actually been pulled from
        // where this gesture found it — not from the right edge. Going back the paper starts
        // fully across, so measuring against the edge handed the very first touch a free
        // hand: the crease could go near-horizontal at once, and a near-horizontal crease
        // does not lift a corner, it lifts the whole bottom half of the screen including
        // the far edge. The first tenth of the travel gets no lean at all, so a finger that
        // wanders vertically before it has committed to anything cannot tip the sheet over.
        // Bounded at both ends: by how far the paper has come from where the gesture found
        // it, and by how far it still is from the corner it hinges on. Going forward those
        // are the same number; going back they are a tent, upright at the start of the drag,
        // free in the middle, upright again once the sheet lies flat over the whole screen.
        val pulled = abs(px - (if (forward) restX else doneX)) - pageW * TILT_DEADZONE
        val room = min(pulled, abs(px - restX)).coerceAtLeast(0f) * MAX_TILT
        val py = y.coerceIn(grabY - room, grabY + room)
        val now = System.nanoTime()
        val dtMs = (now - lastT) / 1_000_000f
        if (dtMs > 0f && dtMs < 100f) {
            // Smoothed: one jittery sample must not be what decides whether a page turns.
            vx = vx * 0.6f + ((px - lastX) / dtMs * 1000f) * 0.4f
        }
        lastX = px
        lastT = now
        corner = Offset(px, py)
    }

    /**
     * Finger lifted. Distance decides the turn only when the finger was not going
     * anywhere: a flick is an instruction on its own, in either direction, whatever the
     * position says. Judging on position alone is what made short deliberate flicks from
     * the very edge silently do nothing.
     */
    fun release(onSettled: (commit: Boolean) -> Unit) {
        if (sheet == null) return
        val progress = (pageW - corner.x) / (2 * pageW)
        val commit = when {
            vx < -FLICK_SPEED -> forward
            vx > FLICK_SPEED -> !forward
            forward -> progress > 0.32f
            else -> progress < 0.68f
        }
        val target = if (forward == commit) doneX else restX
        glideTo(target, if ((target - corner.x) * vx > 0f) vx else 0f, commit, onSettled)
    }

    /** A tap, or a system-cancelled gesture: no speed to inherit, no choice to make. */
    fun finish(commit: Boolean, onSettled: (commit: Boolean) -> Unit) {
        if (sheet == null) return
        val target = if (forward == commit) doneX else restX
        glideTo(target, 0f, commit, onSettled)
    }

    private fun glideTo(
        targetX: Float,
        velocity: Float,
        commit: Boolean,
        onSettled: (Boolean) -> Unit
    ) {
        val start = corner
        val end = Offset(targetX, grabY)
        val span = targetX - start.x
        if (abs(span) < 1f && abs(start.y - grabY) < 1f) {
            corner = end
            onSettled(commit)
            return
        }
        job?.cancel()
        job = scope.launch {
            // One scalar drives both axes, so the fold un-tilts on the way out and lands
            // square however skewed it was being held. Seeding it with the finger's own
            // speed is what makes the paper carry on rather than stop dead and then play
            // a canned animation.
            val t = Animatable(0f)
            val v0 = if (abs(span) > 0.01f) velocity / span else 0f
            t.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,  // paper does not bounce
                    stiffness = 620f
                ),
                initialVelocity = v0
            ) {
                corner = Offset(
                    start.x + (end.x - start.x) * value,
                    start.y + (end.y - start.y) * value
                )
            }
            onSettled(commit)
        }
    }

    /** Take the fold off screen immediately, wherever it was. */
    fun hide() {
        job?.cancel(); job = null
        sheet = null
    }

    fun showCover(bitmap: ImageBitmap) { if (sheet == null) cover = bitmap }
    fun hideCover() { cover = null }
}

/**
 * Draws the fold for whatever position the controller is holding.
 *
 * The corner C the reader pulled is at the page's right edge, at the height it was
 * grabbed. Wherever it is dragged to, the paper folds along the perpendicular bisector of
 * C and the finger: that is the only line that sends C to the finger without stretching
 * the sheet, and it is why the fold tilts when you pull up or down instead of staying
 * stubbornly vertical.
 *
 * With n the unit vector from C towards the finger and M their midpoint, the half-plane
 * that n points into carries everything still lying flat plus the flap folded back onto
 * it; the other side is where the next page shows through, so nothing is drawn there and
 * the WebView underneath does that work.
 *
 * [contentOffset] is where the WebView sits inside this canvas. The two are not the same
 * rectangle: the text is kept clear of the status bar and the navigation bar, but the
 * paper is not — the page colour runs to all four edges — so the sheet that folds has to
 * run to the edges too. Drawn only as far as the text went, the fold stopped short in
 * those two strips and left them lying flat with no crease and no shadow, which reads as
 * the page having been trimmed top and bottom.
 */
@Composable
internal fun PageCurlOverlay(
    curl: CurlController,
    pageColor: Color,
    contentOffset: Offset,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        // Read inside the draw lambda, not in the composable body. Read outside, every
        // one of the sixty corner positions a second invalidates composition and walks
        // the whole subtree; read here it invalidates only this draw, which is the one
        // thing that actually has to happen again.
        val coverSheet = curl.cover
        if (coverSheet != null) {
            drawRect(color = pageColor)
            drawImage(coverSheet, topLeft = contentOffset)
            return@Canvas
        }
        val sheet = curl.sheet ?: return@Canvas
        val corner = curl.corner

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // The controller works in the WebView's coordinates, because that is where the
        // finger is; this canvas is the whole screen. One offset reconciles them.
        val cx = contentOffset.x + sheet.width
        val cy = contentOffset.y + curl.grabY
        val fingerX = contentOffset.x + corner.x
        val fingerY = contentOffset.y + corner.y

        var vxComp = fingerX - cx
        var vyComp = fingerY - cy
        var len = sqrt(vxComp * vxComp + vyComp * vyComp)
        if (len < 1f) { vxComp = -1f; vyComp = 0f; len = 1f }   // dead flat: keep it defined
        val nx = vxComp / len
        val ny = vyComp / len
        val mx = (cx + fingerX) / 2f
        val my = (cy + fingerY) / 2f

        // Big enough that a rectangle laid along the fold is a half-plane at any angle.
        val big = (w + h) * 2f
        val ux = -ny
        val uy = nx

        val near = Path().apply {
            moveTo(mx + ux * big, my + uy * big)
            lineTo(mx + ux * big + nx * big, my + uy * big + ny * big)
            lineTo(mx - ux * big + nx * big, my - uy * big + ny * big)
            lineTo(mx - ux * big, my - uy * big)
            close()
        }

        // Reflection about the fold line: I − 2nnᵀ, re-anchored to pass through M.
        val a = 1f - 2f * nx * nx
        val b = -2f * nx * ny
        val d = 1f - 2f * ny * ny
        val mirror = Matrix().apply {
            // Column-major 4×4: [0]=scaleX [1]=skewY [4]=skewX [5]=scaleY [12]=tx [13]=ty
            values[0] = a
            values[1] = b
            values[4] = b
            values[5] = d
            values[12] = mx - (a * mx + b * my)
            values[13] = my - (b * mx + d * my)
        }

        // The shadow the curve at the fold throws forward onto the page being uncovered.
        // Drawn first and outside the clip, because it falls on the far side.
        withTransform({
            transform(Matrix().apply {
                values[0] = nx;  values[1] = ny      // local +x runs along n…
                values[4] = ux;  values[5] = uy      // …and local +y along the fold line
                values[12] = mx; values[13] = my
            })
        }) {
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                    start = Offset(-FOLD_SHADOW_WIDTH, 0f),
                    end = Offset(0f, 0f)
                ),
                topLeft = Offset(-FOLD_SHADOW_WIDTH, -big),
                size = Size(FOLD_SHADOW_WIDTH, big * 2f)
            )
        }

        clipPath(near) {
            // The part of the page that has not turned yet. It is simply the page: paper
            // to all four edges, with the text laid into it where the text lives.
            drawRect(color = pageColor)
            drawImage(sheet, topLeft = contentOffset)

            // The flap: the same page seen through the fold. Reflecting the whole sheet
            // and clipping to the near side leaves exactly the part that folded over.
            withTransform({ transform(mirror) }) {
                drawRect(color = pageColor, size = Size(w, h))
                drawImage(sheet, topLeft = contentOffset)
                // The back of a page is not blank, but it is not readable either. A sheet
                // of paper over the text, in the flap's own frame so it tilts for free.
                drawRect(color = pageColor.copy(alpha = 0.88f), size = Size(w, h))
                // The page's right edge is the flap's outer edge whatever the angle: that
                // end lies flat and takes the shadow, the fold end catches the light.
                drawRect(
                    brush = Brush.linearGradient(
                        0.00f to Color.White.copy(alpha = 0.10f),
                        0.34f to Color.Black.copy(alpha = 0.03f),
                        1.00f to Color.Black.copy(alpha = 0.20f),
                        start = Offset(0f, 0f),
                        end = Offset(w, 0f)
                    ),
                    size = Size(w, h)
                )
            }
        }
    }
}
