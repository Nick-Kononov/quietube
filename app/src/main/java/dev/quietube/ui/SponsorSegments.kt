package dev.quietube.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.materialkolor.blend.Blend
import com.materialkolor.hct.Hct
import dev.quietube.data.SegmentCategory
import dev.quietube.data.SegmentMode
import dev.quietube.data.SkipSegment

/**
 * SponsorBlock's familiar category hues, nudged towards the theme's primary and set to one mid tone: its yellow and
 * cyan are otherwise near-white, lost against the time bar's white played track.
 */
internal fun segmentColour(category: SegmentCategory, primary: Color): Color {
    val base = when (category) {
        SegmentCategory.Sponsor -> 0xFF00D400
        SegmentCategory.SelfPromo -> 0xFFFFFF00
        SegmentCategory.Interaction -> 0xFFCC00FF
        SegmentCategory.Intro -> 0xFF00FFFF
        SegmentCategory.Outro -> 0xFF0202ED
        SegmentCategory.Preview -> 0xFF008FD6
        SegmentCategory.Filler -> 0xFF7300FF
    }.toInt()
    val hue = Hct.fromInt(Blend.harmonize(base, primary.toArgb()))
    return Color(Hct.from(hue.hue, maxOf(hue.chroma, 48.0), 60.0).toInt())
}

/** Segment ranges drawn over the time bar's track, leaving a hole where the scrubber is so it stays on top. */
@Composable
internal fun SegmentMarks(segments: List<SkipSegment>, duration: Long, span: TimeBarSpan, position: () -> Long, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxSize()) {
        if (duration <= 0) return@Canvas
        val height = 4.dp.toPx()
        val centre = (span.top + span.bottom) / 2f
        val thumb = Path().apply { addOval(Rect(Offset(scrubX(span, position(), duration), centre), 6.5.dp.toPx())) }
        clipPath(thumb, ClipOp.Difference) {
            segments.forEach { segment ->
                val left = scrubX(span, segment.startMs, duration)
                val right = scrubX(span, segment.endMs, duration)
                if (right - left >= 1f) drawRect(segmentColour(segment.category, primary).copy(alpha = .9f), Offset(left, centre - height / 2f), Size(right - left, height))
            }
        }
    }
}

/** Offered while a show-only segment plays. */
@Composable
internal fun SkipPill(segment: SkipSegment, skip: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(skip, modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
        Box(Modifier.size(10.dp).background(segmentColour(segment.category, MaterialTheme.colorScheme.primary), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Skip ${segment.category.label.lowercase()}")
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
    }
}

internal fun skippedMessage(segment: SkipSegment): String = "Skipped ${segment.category.label.lowercase()}"

/** One category's Skip / Show only / Off choice in Settings. */
@Composable
internal fun SegmentModeRow(category: SegmentCategory, mode: SegmentMode, change: (SegmentMode) -> Unit) {
    ListItem(
        leadingContent = { Box(Modifier.size(12.dp).background(segmentColour(category, MaterialTheme.colorScheme.primary), CircleShape)) },
        content = { Text(category.label) },
        supportingContent = {
            Column {
                Text(category.detail)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SegmentMode.entries.forEachIndexed { index, option ->
                        SegmentedButton(mode == option, { change(option) }, SegmentedButtonDefaults.itemShape(index, SegmentMode.entries.size),
                            Modifier.heightIn(min = 48.dp), label = { Text(when (option) { SegmentMode.Skip -> "Skip"; SegmentMode.Show -> "Show only"; SegmentMode.Off -> "Off" }) })
                    }
                }
            }
        })
}
