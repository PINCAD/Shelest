package com.lexiread.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection

/** The gap between the book and the panel, so the two read as separate cards. */
private val ANCHOR_GAP = 6.dp

/**
 * The long-press "Удалить" menu, shared by the library, the vocabulary list and the
 * notes sheet so the same gesture produces the same thing in all three.
 *
 * A plain Popup rather than Material's DropdownMenu: that one draws itself on a tonally
 * elevated surface — the accent mixed into the background, which with this app's warm
 * red-brown reads as beige — and rounds its corners to 4dp where every other panel here is
 * at 16. Both are the menu's own decisions, not something a caller can pass in.
 *
 * Positioning is the other half. A popup anchors itself to a zero-sized placeholder node, so
 * left alone it opens from the top-left corner of whatever it is placed in and, at some fixed
 * width of its own, hangs off across the neighbouring book. Call it with [modifier] =
 * `Modifier.matchParentSize()` inside the Box you want it to belong to: it then takes that
 * box's width as its own and sits directly underneath it, so the panel is visibly a part of
 * the one book being deleted rather than a slab floating over the row.
 */
@Composable
fun DeleteMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    label: String = "Удалить",
    modifier: Modifier = Modifier,
    /**
     * An optional harmless action above the destructive one — the library puts «В избранное»
     * there. Left out, the menu is exactly the single red row it has always been, which is why
     * the vocabulary list and the notes sheet need no changes.
     */
    extraLabel: String? = null,
    extraIcon: ImageVector? = null,
    onExtra: (() -> Unit)? = null,
    actions: List<Triple<String, ImageVector, () -> Unit>> = emptyList()
) {
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.onSizeChanged { anchorSize = it }) {
        if (!expanded || anchorSize.width == 0) return@Box

        val density = LocalDensity.current
        val shape   = RoundedCornerShape(16.dp)
        val hasExtra = extraLabel != null && onExtra != null
        val gap = with(density) { ANCHOR_GAP.roundToPx() }
        val position = remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val below = anchorBounds.bottom + gap
                    val y = if (below + popupContentSize.height <= windowSize.height) below
                        else anchorBounds.top - gap - popupContentSize.height
                    return IntOffset(
                        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                        y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
                }
            }
        }

        Popup(
            popupPositionProvider = position,
            onDismissRequest = onDismissRequest,
            properties       = PopupProperties(focusable = true)
        ) {
            Column(
                modifier = Modifier
                    // Exactly the width of the thing being acted on. No fixed width can be
                    // right for both a book cover and a full-width note row, and a menu wider
                    // than its book is what made it look like it belonged to the shelf rather
                    // than to the cover under it.
                    .width(with(density) { anchorSize.width.toDp().coerceAtLeast(if (actions.isEmpty()) 0.dp else 190.dp) })
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            ) {
                actions.forEach { (text, icon, action) ->
                    MenuRow(icon, text, MaterialTheme.colorScheme.onSurface,
                        onClick = { onDismissRequest(); action() })
                }
                if (hasExtra) {
                    MenuRow(
                        icon    = extraIcon ?: Icons.Outlined.DeleteOutline,
                        label   = extraLabel!!,
                        tint    = MaterialTheme.colorScheme.onSurface,
                        onClick = { onDismissRequest(); onExtra!!() }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                MenuRow(
                    icon    = Icons.Outlined.DeleteOutline,
                    label   = label,
                    tint    = MaterialTheme.colorScheme.error,
                    onClick = { onDismissRequest(); onDelete() }
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        // Centred, not left-aligned: at a cover's width there is no column of other
        // items for a leading icon to line up with.
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
