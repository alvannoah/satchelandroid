package com.example.satchel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// The web frontend's palette (satchelfrontend/src/styles.css), so both clients look alike.

internal object Light {
    val background = Color(0xFFFFFFFF)
    val surface = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFF0F4F9)
    val surface3 = Color(0xFFE9EEF6)
    val text = Color(0xFF1F1F1F)
    val muted = Color(0xFF5E6368)
    val border = Color(0xFFDDE1E6)
    val accent = Color(0xFF0B57D0)
    val onAccent = Color(0xFFFFFFFF)
    val accentSoft = Color(0xFFD3E3FD)
    val navActive = Color(0xFFC2E7FF)
    val onNavActive = Color(0xFF001D35)
    val danger = Color(0xFFB3261E)
    val dangerBg = Color(0xFFF9DEDC)
}

internal object Dark {
    val background = Color(0xFF131314)
    val surface = Color(0xFF1E1F20)
    val surface2 = Color(0xFF282A2C)
    val surface3 = Color(0xFF333537)
    val text = Color(0xFFE3E3E3)
    val muted = Color(0xFFA8ABAF)
    val border = Color(0xFF3C4043)
    val accent = Color(0xFFA8C7FA)
    val onAccent = Color(0xFF062E6F)
    val accentSoft = Color(0xFF0842A0)
    val navActive = Color(0xFF004A77)
    val onNavActive = Color(0xFFC2E7FF)
    val danger = Color(0xFFF2B8B5)
    val dangerBg = Color(0xFF601410)
}

/** Colours Material has no slot for: official/community material, warnings, file kinds. */
@Immutable
data class SatchelColors(
    val official: Color,
    val officialBg: Color,
    val community: Color,
    val communityBg: Color,
    val warn: Color,
    val warnBg: Color,
    val ok: Color,
    val okBg: Color,
    val pdf: Color,
    val docx: Color,
    val pptx: Color,
    val otherFile: Color,
)

internal val LightExtra = SatchelColors(
    official = Color(0xFF146C2E), officialBg = Color(0xFFC4EED0),
    community = Color(0xFF8A5300), communityBg = Color(0xFFFFE0B2),
    warn = Color(0xFF7A5900), warnBg = Color(0xFFFEF7E0),
    ok = Color(0xFF146C2E), okBg = Color(0xFFE6F4EA),
    pdf = Color(0xFFD93025), docx = Color(0xFF1A73E8), pptx = Color(0xFFE37400), otherFile = Color(0xFF5E6368),
)

internal val DarkExtra = SatchelColors(
    official = Color(0xFF6DD58C), officialBg = Color(0xFF0F5223),
    community = Color(0xFFFFB951), communityBg = Color(0xFF5B3A00),
    warn = Color(0xFFF6D868), warnBg = Color(0xFF3D3000),
    ok = Color(0xFF6DD58C), okBg = Color(0xFF0F5223),
    pdf = Color(0xFFF28B82), docx = Color(0xFF8AB4F8), pptx = Color(0xFFFDD663), otherFile = Color(0xFFA8ABAF),
)
