package com.example.satchel.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.container
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.Document
import com.example.satchel.data.DocumentFiles
import com.example.satchel.data.OfficeText
import com.example.satchel.data.PdfPages
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.FileKind
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.openExternally
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ViewerContent {
    class Pdf(val pages: PdfPages) : ViewerContent
    class Text(val sections: List<OfficeText.Section>, val kind: FileKind) : ViewerContent
}

class ViewerViewModel(private val id: Int, private val api: ApiClient, private val files: DocumentFiles) : ViewModel() {
    var document by mutableStateOf<Document?>(null)
        private set
    var file by mutableStateOf<File?>(null)
        private set
    var content by mutableStateOf<ViewerContent?>(null)
        private set
    var stage by mutableStateOf("Downloading…")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                val doc = document ?: api.document(id, related = 0).document.also { document = it }
                stage = "Downloading…"
                val f = files.fetch(doc).also { file = it }
                stage = "Preparing…"
                content = withContext(Dispatchers.IO) {
                    when (val kind = FileKind.of(doc.originalFilename)) {
                        FileKind.Pdf -> ViewerContent.Pdf(PdfPages(f))
                        FileKind.Pptx -> ViewerContent.Text(OfficeText.pptx(f), kind)
                        FileKind.Docx -> ViewerContent.Text(OfficeText.docx(f), kind)
                        FileKind.Other -> null
                    }
                }
                if (content == null) error = "This kind of file can’t be shown in Satchel."
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                // A damaged or password-protected file, or one in an older format (.doc, .ppt).
                error = "This file couldn’t be shown in Satchel."
            }
        }
    }

    override fun onCleared() {
        (content as? ViewerContent.Pdf)?.pages?.close()
    }
}

@Composable
fun ViewerScreen(documentId: Int) {
    val vm = screenViewModel { ViewerViewModel(documentId, it.api, it.files) }
    val context = LocalContext.current
    var externalError by remember { mutableStateOf<String?>(null) }
    val openElsewhere: (() -> Unit)? = vm.file?.let { f ->
        { externalError = openExternally(context, context.container.files.shareableUri(f), f.name) }
    }

    Column(Modifier.fillMaxSize()) {
        externalError?.let { Notice(it, kind = "error", modifier = Modifier.padding(16.dp)) }
        Box(Modifier.weight(1f)) {
            val content = vm.content
            val error = vm.error
            when {
                error != null -> EmptyState(
                    error,
                    actionLabel = if (openElsewhere != null) "Open in another app" else "Try again",
                    onAction = openElsewhere ?: vm::load,
                )
                content is ViewerContent.Pdf -> PdfViewer(content.pages, openElsewhere)
                content is ViewerContent.Text -> TextViewer(content, openElsewhere)
                else -> Loading(message = vm.stage)
            }
        }
    }
}

// ---------------------------------- PDF ----------------------------------- //

@Composable
private fun PdfViewer(pages: PdfPages, openElsewhere: (() -> Unit)?) {
    if (pages.pageCount == 0) {
        EmptyState("This PDF has no pages.")
        return
    }
    val pager = rememberPagerState { pages.pageCount }
    val scope = rememberCoroutineScope()
    // Draw pages at twice the screen width (at most 2048 px), so zooming in stays sharp.
    val screenWidth = LocalWindowInfo.current.containerSize.width
    val renderWidth = (screenWidth * 2).coerceIn(600, 2048)

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
        ) { index ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, index, renderWidth) {
                value = pages.render(index, renderWidth)
            }
            ZoomablePage(pages.aspectRatios[index], "Page ${index + 1} of ${pages.pageCount}") {
                val b = bitmap
                if (b == null) {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else {
                    Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
            }
            Text("Page ${pager.currentPage + 1} of ${pages.pageCount}", style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = pager.currentPage < pages.pageCount - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
            }
            Box(Modifier.weight(1f))
            if (openElsewhere != null) TextButton(onClick = openElsewhere) { Text("Open in another app") }
        }
    }
}

/**
 * A page that fits the screen, with pinch and double-tap zoom. While zoomed in,
 * one finger moves around the page; at normal size it swipes between pages.
 */
@Composable
private fun ZoomablePage(aspectRatio: Float, description: String, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // The page's size when it fits the box.
        val fitW = minOf(boxW, boxH * aspectRatio)
        val fitH = fitW / aspectRatio

        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-maxOf(0f, (fitW * s - boxW) / 2), maxOf(0f, (fitW * s - boxW) / 2)),
            o.y.coerceIn(-maxOf(0f, (fitH * s - boxH) / 2), maxOf(0f, (fitH * s - boxH) / 2)),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // Keep the tapped point under the finger.
                            offset = clamp((Offset(boxW / 2, boxH / 2) - tap) * 1.5f, 2.5f)
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zooming = event.changes.count { it.pressed } > 1
                            if (zooming || scale > 1f) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), scale)
                                // Claim the gesture, so the pager does not turn the page meanwhile.
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = boxW / boxH > aspectRatio)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .semantics { contentDescription = description },
            ) { content() }
        }
    }
}

// ---------------------------- PowerPoint, Word ---------------------------- //

@Composable
private fun TextViewer(content: ViewerContent.Text, openElsewhere: (() -> Unit)?) {
    val slides = content.kind == FileKind.Pptx
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column {
                Notice(
                    (if (slides) "The slides’ text, in order." else "The document’s text.") +
                        " Pictures and layout aren’t shown here; open the original to see them.",
                )
                if (openElsewhere != null) {
                    TextButton(onClick = openElsewhere) { Text("Open the original in another app") }
                }
            }
        }
        if (content.sections.all { it.title == null && it.paragraphs.isEmpty() }) item {
            Text("No text was found in this file. It may be made of pictures: open the original to see it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(content.sections) { section ->
            if (slides) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(section.label.orEmpty(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        section.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        section.paragraphs.forEach { Paragraph(it, bullet = true) }
                        if (section.title == null && section.paragraphs.isEmpty()) {
                            Text("(No text on this slide)", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.paragraphs.forEach { Paragraph(it, bullet = false) }
                }
            }
        }
    }
}

@Composable
private fun Paragraph(p: OfficeText.Paragraph, bullet: Boolean) {
    val indent = (p.level.coerceAtMost(4) * 16).dp
    when (p.style) {
        OfficeText.Style.Title -> Text(p.text, style = MaterialTheme.typography.headlineSmall)
        OfficeText.Style.Heading -> Text(p.text, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        else -> Row(Modifier.padding(start = indent)) {
            if (bullet || p.style == OfficeText.Style.ListItem) {
                Text(if (p.level == 0) "•" else "◦", modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
            Text(p.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
