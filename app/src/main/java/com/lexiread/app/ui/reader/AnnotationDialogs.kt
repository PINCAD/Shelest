package com.lexiread.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lexiread.app.data.model.Annotation

val DEFAULT_HIGHLIGHT_COLOR = Color(0xFFFFD54F).toArgb()

val ANNOTATION_COLORS = listOf(
    Color(0xFFFFD54F), // жёлтый
    Color(0xFFAED581), // зелёный
    Color(0xFF4FC3F7), // голубой
    Color(0xFFF48FB1), // розовый
    Color(0xFFFFB74D)  // оранжевый
)

/**
 * The dialog surface. Material's default container is `surface` *plus* tonal elevation,
 * and this app's accent is a warm red-brown, so every dialog came out beige — the same
 * colour as a sepia page, floating above a page. Plain background, no elevation tint,
 * and a rounder corner so it reads as a card rather than a system alert.
 */
private val DialogShape = RoundedCornerShape(24.dp)

@Composable
private fun ColorSwatchRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        ANNOTATION_COLORS.forEach { color ->
            val argb = color.toArgb()
            val isSelected = selected == argb
            // The mark goes inside the swatch instead of a thick ring around it: a ring
            // in onSurface put a black circle on five bright dots and made the row look
            // like a mistake.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.Black.copy(alpha = 0.10f), CircleShape)
                    .clickable { onSelect(argb) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** The selected passage, shown the way a quote should be: indented behind an accent rule. */
@Composable
private fun QuotedText(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text     = text,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3
        )
    }
}

@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onValueChange,
        placeholder     = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        modifier        = Modifier.fillMaxWidth(),
        minLines        = 3,
        maxLines        = 6,
        shape           = RoundedCornerShape(12.dp),
        textStyle       = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        colors          = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor   = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Choosing a swatch applies the highlight immediately to the captured selection. */
@Composable
fun AnnotationHighlightDialog(
    selectedText: String,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        title = { DialogTitle("Цвет выделения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                QuotedText(selectedText)
                ColorSwatchRow(selected = -1, onSelect = onColorSelected)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun AnnotationNoteDialog(
    selectedText: String,
    quoted: Boolean = true,
    onConfirm: (noteText: String, color: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(DEFAULT_HIGHLIGHT_COLOR) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape            = DialogShape,
        containerColor   = MaterialTheme.colorScheme.background,
        tonalElevation   = 0.dp,
        title            = { DialogTitle("Новая заметка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // A passage from the book gets the quote treatment; the PDF pin passes a
                // caption ("Заметка на странице 12"), which isn't a quotation of anything.
                if (quoted) {
                    QuotedText(selectedText)
                } else {
                    Text(
                        selectedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NoteField(noteText, { noteText = it }, "Ваша заметка…")
                ColorSwatchRow(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(noteText.trim(), color) },
                enabled = noteText.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun AnnotationEditDialog(
    annotation: Annotation,
    onSaveNote: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var noteText by remember(annotation.id) { mutableStateOf(annotation.noteText ?: "") }
    var color by remember(annotation.id) { mutableStateOf(annotation.color) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape            = DialogShape,
        containerColor   = MaterialTheme.colorScheme.background,
        tonalElevation   = 0.dp,
        title            = { DialogTitle(if (annotation.noteText != null) "Заметка" else "Выделение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                annotation.selectedText?.let { QuotedText(it) }
                NoteField(noteText, { noteText = it }, "Заметка…")
                ColorSwatchRow(selected = color, onSelect = {
                    color = it
                    onColorChange(it)
                })

                // Deleting lives in the body, not in the button row. Three buttons on one
                // action row overflowed and pushed "Сохранить" off the edge, and a
                // destructive action sitting shoulder to shoulder with the confirm button
                // is the wrong place for it anyway.
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onDelete(); onDismiss() }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Удалить",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveNote(noteText.trim())
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}
