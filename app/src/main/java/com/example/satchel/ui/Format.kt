package com.example.satchel.ui

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class FileKind(val label: String, val short: String) {
    Pdf("PDF", "PDF"), Docx("Word", "DOC"), Pptx("Slides", "PPT"), Other("File", "FILE");

    companion object {
        fun of(filename: String): FileKind = when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> Pdf
            "docx", "doc" -> Docx
            "pptx", "ppt" -> Pptx
            else -> Other
        }
    }
}

/** MIME type for opening a downloaded file in another app. */
fun mimeTypeFor(filename: String): String = when (FileKind.of(filename)) {
    FileKind.Pdf -> "application/pdf"
    FileKind.Docx -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    FileKind.Pptx -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    FileKind.Other -> "application/octet-stream"
}

fun formatSize(bytes: Long): String =
    if (bytes > 1 shl 20) String.format(Locale.getDefault(), "%.1f MB", bytes / (1 shl 20).toDouble())
    else "${max(1, (bytes / 1024.0).roundToInt())} KB"

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** The API's timestamps, with or without a time zone, as a short date. */
fun formatDate(iso: String): String = runCatching { OffsetDateTime.parse(iso).toLocalDate().format(dateFormat) }
    .recoverCatching { LocalDateTime.parse(iso).toLocalDate().format(dateFormat) }
    .getOrDefault(iso.take(10))

/** A similarity score (0 to 1) as a percentage. */
fun percent(score: Double): String = "${(score * 100).roundToInt()}%"

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private val academicYear = Regex("""^(\d{4})/(\d{4})$""")

/** Like the API's check: "2025/2026", the second year following the first. */
fun isAcademicYear(text: String): Boolean =
    academicYear.matchEntire(text)?.destructured?.let { (a, b) -> b.toInt() == a.toInt() + 1 } ?: false
