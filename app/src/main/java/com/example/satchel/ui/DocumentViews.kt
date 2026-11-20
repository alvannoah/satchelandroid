package com.example.satchel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.satchel.data.Document
import com.example.satchel.data.ReadingMark
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.satchel.data.Status
import com.example.satchel.ui.theme.LightExtra
import com.example.satchel.ui.theme.Satchel
import com.example.satchel.ui.theme.SatchelColors

/** How a list of documents is shown. */
enum class DocLayout(val label: String, val description: String, val icon: ImageVector) {
    Cards("Cards", "Title, course, type and source", LayoutIcons.Cards),
    Compact("Compact", "One line each: fits the most on screen", LayoutIcons.Compact),
    Grid("Grid", "Tiles with a page preview", LayoutIcons.Grid);

    companion object {
        fun from(name: String?): DocLayout = entries.firstOrNull { it.name == name } ?: Cards
    }
}

/**
 * Icons for the layouts, drawn on Material's 24-unit grid. The core icon set has
 * none, and the extended set is a large dependency for three icons.
 */
object LayoutIcons {
    private fun icon(name: String, shapes: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black), pathBuilder = shapes).build()

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
        moveTo(x, y); horizontalLineToRelative(w); verticalLineToRelative(h); horizontalLineToRelative(-w); close()
    }

    /** Two stacked cards. */
    val Cards = icon("Cards") { rect(3f, 4f, 18f, 7f); rect(3f, 13f, 18f, 7f) }

    /** Four thin rows. */
    val Compact = icon("Compact") { listOf(4f, 9f, 14f, 19f).forEach { rect(3f, it - 1f, 18f, 2f) } }

    /** An arrow down onto a tray: save to the phone. */
    val Download = icon("Download") {
        rect(5f, 18f, 14f, 2f)
        moveTo(11f, 4f); horizontalLineToRelative(2f); verticalLineTo(12.2f); lineTo(16.6f, 8.6f); lineTo(18f, 10f)
        lineTo(12f, 16f); lineTo(6f, 10f); lineTo(7.4f, 8.6f); lineTo(11f, 12.2f); close()
    }

    /** Four tiles. */
    val Grid = icon("Grid") { rect(3f, 3f, 8f, 8f); rect(13f, 3f, 8f, 8f); rect(3f, 13f, 8f, 8f); rect(13f, 13f, 8f, 8f) }
}

/** An icon button showing the current layout, with a menu of layouts. */
@Composable
fun LayoutMenu(layout: DocLayout, onChange: (DocLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(layout.icon, contentDescription = "View: ${layout.label}. Change layout")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DocLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            Text(option.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    trailingIcon = {
                        if (option == layout) Icon(Icons.Filled.Check, contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = { open = false; onChange(option) },
                )
            }
        }
    }
}

private fun kindColor(kind: FileKind, c: SatchelColors): Color = when (kind) {
    FileKind.Pdf -> c.pdf
    FileKind.Docx -> c.docx
    FileKind.Pptx -> c.pptx
    FileKind.Other -> c.otherFile
}

/** Dense: a coloured type label, the title, and course · type (or the match score) on one row. */
@Composable
fun CompactDocumentRow(
    doc: Document,
    onClick: () -> Unit,
    score: Double? = null,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    reading: ReadingMark? = null,
) {
    val kind = FileKind.of(doc.originalFilename)
    val color = kindColor(kind, Satchel.colors)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .documentClick(doc, selected, onClick, onLongClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(kind.short, color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${doc.course.code} · ${doc.documentType.name}" + (doc.academicYear?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (reading != null) {
                if (reading.completed) {
                    Icon(Icons.Filled.Check, contentDescription = "Read", tint = Satchel.colors.ok,
                        modifier = Modifier.size(18.dp))
                } else {
                    Text("${reading.percent}%", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { contentDescription = "${reading.percent}% read" })
                }
                Spacer(Modifier.width(8.dp))
            }
            if (selected != null) {
                SelectionMark(selected)
            } else if (score != null) {
                Text(percent(score), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                // A small dot keeps the official/community distinction without a full badge.
                val c = Satchel.colors
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(50))
                        .background(if (doc.official) c.official else c.community),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A tile with a drawn page, like the web app's grid: the backend keeps text, not rendered pages. */
@Composable
fun DocumentTile(
    doc: Document,
    onClick: () -> Unit,
    score: Double? = null,
    showStatus: Boolean = false,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    reading: ReadingMark? = null,
) {
    val kind = FileKind.of(doc.originalFilename)
    val colors = Satchel.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectionBorder(selected, shape)
            .documentClick(doc, selected, onClick, onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3.4f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Sheet(doc, kind, colors)
            Box(Modifier.padding(6.dp)) {
                if (showStatus && doc.status != Status.PUBLISHED) StatusBadge(doc.status) else OfficialBadge(doc.official)
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
            // How far you have read, as a bar along the foot of the page.
            if (reading != null) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(reading.percent.coerceIn(2, 100) / 100f).height(4.dp)
                        .background(if (reading.completed) colors.ok else MaterialTheme.colorScheme.primary),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(doc.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.short, color = kindColor(kind, colors), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
                Text("${doc.course.code} · ${doc.documentType.name}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (score != null) ScoreBar(score)
                    else Text(doc.academicYear ?: formatDate(doc.uploadedAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (reading != null) ProgressBadge(reading)
            }
        }
    }
}

@Composable
private fun Sheet(doc: Document, kind: FileKind, colors: SatchelColors) {
    // The page is always light, so it always uses the light theme's ink and accent colours.
    val accent = kindColor(kind, LightExtra)
    val ink = Color(0xFF1F1F1F)
    val slides = kind == FileKind.Pptx
    val widths = remember(doc.id, slides) { lineWidths(doc.id, if (slides) 3 else 7) }
    val modifier = if (slides) {
        Modifier.fillMaxWidth(0.86f).aspectRatio(16f / 10f)
    } else {
        Modifier.fillMaxWidth(0.72f).fillMaxHeight()
    }
    Box(Modifier.fillMaxWidth().padding(top = if (slides) 34.dp else 14.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = if (slides) 4.dp else 0.dp,
                    bottomEnd = if (slides) 4.dp else 0.dp))
                .background(colors.paper)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (slides) Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
            Text("${doc.course.code} · ${doc.documentType.name}".uppercase(), color = accent, fontSize = 6.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(doc.title, color = ink, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            widths.forEach { w ->
                Box(Modifier.fillMaxWidth(w).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.paperLine))
            }
        }
    }
}

/** A similarity score as a short bar and a percentage. */
@Composable
fun ScoreBar(score: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(48.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(score.toFloat().coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(6.dp))
        Text("${percent(score)} match", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** Replaces a screen's top bar while documents are selected: count, Select all, then the screen's actions. */
@Composable
fun SelectionBar(
    count: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onSelectAll: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = !busy) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
        }
        Text("$count selected", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
        if (onSelectAll != null) TextButton(onClick = onSelectAll, enabled = !busy) { Text("Select all") }
        actions()
    }
}
