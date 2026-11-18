package com.example.satchel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satchel.AppContainer
import com.example.satchel.container
import com.example.satchel.data.Document
import com.example.satchel.data.Status
import com.example.satchel.ui.theme.Satchel

/** A screen's ViewModel, made with the app's shared objects; it lives as long as the screen's back-stack entry. */
@Composable
inline fun <reified VM : ViewModel> screenViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel { create(container) }
}

/** A coloured tile showing the file type, like the web app's file icons. */
@Composable
fun FileBadge(filename: String, modifier: Modifier = Modifier) {
    val kind = FileKind.of(filename)
    val colors = Satchel.colors
    val color = when (kind) {
        FileKind.Pdf -> colors.pdf
        FileKind.Docx -> colors.docx
        FileKind.Pptx -> colors.pptx
        FileKind.Other -> colors.otherFile
    }
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.short, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
    }
}

@Composable
fun Pill(text: String, color: Color, background: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

/** Lecturer material and student contributions look different, as on the web. */
@Composable
fun OfficialBadge(official: Boolean) {
    val c = Satchel.colors
    if (official) Pill("Official", c.official, c.officialBg) else Pill("Community", c.community, c.communityBg)
}

@Composable
fun StatusBadge(status: String) {
    val c = Satchel.colors
    when (status) {
        Status.PENDING -> Pill("Pending review", c.warn, c.warnBg)
        Status.REJECTED -> Pill("Rejected", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        else -> Pill("Published", c.ok, c.okBg)
    }
}

/** A document in a list: file type, title, course and type, and optionally a similarity score. */
@Composable
fun DocumentRow(
    doc: Document,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    score: Double? = null,
    scoreLabel: String = "match",
    showStatus: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open ${doc.title}", role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(doc.originalFilename)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                doc.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(doc.course.code, doc.documentType.name, doc.academicYear).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showStatus) StatusBadge(doc.status) else OfficialBadge(doc.official)
                if (score != null) {
                    Text(
                        "${percent(score)} $scoreLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** A coloured notice: kind is "warn", "error", "ok" or "info". */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, kind: String = "info") {
    val c = Satchel.colors
    val scheme = MaterialTheme.colorScheme
    val (fg, bg) = when (kind) {
        "warn" -> c.warn to c.warnBg
        "error" -> scheme.error to scheme.errorContainer
        "ok" -> c.ok to c.okBg
        else -> scheme.onPrimaryContainer to scheme.primaryContainer
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (kind == "warn" || kind == "error") Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null, tint = fg, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A full-screen message, with an optional action such as "Try again". */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}
