package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Maximum share of a settings row's inner width that the trailing value column may occupy
 * when rendered side-by-side with the title and description.
 */
const val SETTINGS_ROW_VALUE_MAX_WIDTH_FRACTION = 0.40f

/**
 * Maximum character length for a value to remain eligible for side-by-side placement under [SettingsValueLayoutMode.AUTO].
 * Longer values automatically stack full-width beneath the description.
 */
const val SETTINGS_ROW_SIDE_BY_SIDE_MAX_CHARS = 14

/**
 * Minimum horizontal width (in dp) guaranteed to the title/description column before
 * automatically switching a settings row to stacked layout.
 */
const val SETTINGS_ROW_MIN_TITLE_WIDTH_DP = 160f

/**
 * Minimum inner row width (in dp) required to keep a [SettingsSwitchRow] side-by-side.
 * Below this threshold (e.g. maximum display size + split screen), the switch stacks below
 * so the title and description never break mid-word.
 */
const val SETTINGS_SWITCH_MIN_SIDE_BY_SIDE_WIDTH_DP = 230f

/**
 * Layout mode controlling whether a [SettingsRow]'s value appears beside the title/subtitle
 * or stacked full-width underneath them.
 */
enum class SettingsValueLayoutMode {
    /**
     * Automatically selects side-by-side for short values that fit within 40% of the row width
     * while leaving generous room for the title/description, and stacks full-width below for
     * long values, multi-segment `" • "` values, or large font/display sizes.
     */
    AUTO,

    /**
     * Always places the value full-width and left-aligned on its own line below the title and description.
     */
    STACKED,

    /**
     * Prefers side-by-side placement (with a strict 40% max-width cap on the value column),
     * falling back to stacked only if the viewport is too narrow to protect the title from mid-word breaks.
     */
    SIDE_BY_SIDE
}

/**
 * Pure decision helper used by [SettingsRow] to determine whether to stack the value
 * beneath the title and description instead of keeping them side-by-side.
 */
fun shouldUseStackedSettingsLayout(
    trailingText: String?,
    layoutMode: SettingsValueLayoutMode,
    availableWidthPx: Int,
    measuredValueWidthPx: Int,
    dropdownWidthPx: Int,
    density: Float
): Boolean {
    if (trailingText.isNullOrBlank()) return false
    if (layoutMode == SettingsValueLayoutMode.STACKED) return true
    if (availableWidthPx <= 0) return false

    val safeDensity = density.coerceAtLeast(0.5f)
    val maxSideBySideValuePx = (availableWidthPx * SETTINGS_ROW_VALUE_MAX_WIDTH_FRACTION).toInt()
    val totalTrailingPx = measuredValueWidthPx + dropdownWidthPx
    val minTitleWidthPx = (SETTINGS_ROW_MIN_TITLE_WIDTH_DP * safeDensity).toInt()
    val gapPx = (16f * safeDensity).toInt()

    if (layoutMode == SettingsValueLayoutMode.SIDE_BY_SIDE) {
        val reservedTrailingPx = minOf(totalTrailingPx, maxSideBySideValuePx)
        return (availableWidthPx - reservedTrailingPx - gapPx) < minTitleWidthPx
    }

    // AUTO mode rules:
    // 1. Multi-segment metadata values (" • " or " · ") always stack for clean readability.
    if (trailingText.contains(" • ") || trailingText.contains(" · ")) {
        return true
    }

    // 2. Values longer than short status/option labels always stack.
    if (trailingText.length > SETTINGS_ROW_SIDE_BY_SIDE_MAX_CHARS) {
        return true
    }

    // 3. If the actually measured text width (accounting for current fontScale and Display Size)
    //    exceeds 40% of the available row width, stack instead of squeezing the title.
    if (totalTrailingPx > maxSideBySideValuePx) {
        return true
    }

    // 4. Always ensure the title/description column retains at least 160dp so words never break mid-word.
    val remainingTitlePx = availableWidthPx - totalTrailingPx - gapPx
    return remainingTitlePx < minTitleWidthPx
}

/**
 * Formats a multi-segment value containing `" • "` or `" · "` separators so that when it exceeds
 * [maxLineWidthPx], line breaks happen cleanly at the separator boundaries instead of splitting
 * individual tokens (such as `"11.8 MP"` or `"5.3mm"`) across lines.
 */
fun wrapValueAtSeparators(
    text: String,
    maxLineWidthPx: Int,
    measureWidthPx: (String) -> Int
): String {
    val separator = when {
        text.contains(" • ") -> " • "
        text.contains(" · ") -> " · "
        else -> return text
    }
    if (maxLineWidthPx <= 0 || measureWidthPx(text) <= maxLineWidthPx) {
        return text
    }

    val segments = text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.size <= 1) return text

    val lines = mutableListOf<String>()
    var currentLine = segments.first()

    for (index in 1 until segments.size) {
        val segment = segments[index]
        val candidate = "$currentLine$separator$segment"
        if (measureWidthPx(candidate) <= maxLineWidthPx) {
            currentLine = candidate
        } else {
            lines.add(currentLine)
            currentLine = segment
        }
    }
    lines.add(currentLine)
    return lines.joinToString("\n")
}

/**
 * Google Pixel-inspired rounded settings card container.
 * Groups related settings items into a clean, elevated surface.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

/**
 * Subtle divider between rows inside a [SettingsCard].
 */
@Composable
fun SettingsCardDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 20.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

/**
 * Reusable, accessibility-safe settings row component.
 *
 * Automatically chooses between:
 * - **Side-by-side layout** for short values (e.g. `"Active"`, `"v1.0.0"`, `"4:3"`), with the trailing
 *   value column strictly capped at 40% of the row width so the title and description never squeeze
 *   or break mid-word.
 * - **Stacked layout** for long values, `" • "` metadata strings, [SettingsValueLayoutMode.STACKED],
 *   or large font/display sizes: title on the first line, description underneath at full width,
 *   and the value on its own full-width left-aligned line below in a slightly smaller secondary style
 *   that wraps cleanly at `" • "` separators.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingText: String? = null,
    showDropdownIndicator: Boolean = false,
    showNavigationArrow: Boolean = false,
    testTag: String? = null,
    layoutMode: SettingsValueLayoutMode = SettingsValueLayoutMode.AUTO,
    useSecondaryValueStyle: Boolean = false,
    clickable: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    val tagModifier = if (testTag != null) modifier.testTag(testTag) else modifier
    val interactionModifier = when {
        onLongClick != null -> Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
        clickable -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }

    val titleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.Medium,
        lineBreak = LineBreak.Heading,
        hyphens = Hyphens.None
    )
    val subtitleStyle = MaterialTheme.typography.bodyMedium.copy(
        lineBreak = LineBreak.Paragraph,
        hyphens = Hyphens.None
    )
    val sideBySideValueStyle = MaterialTheme.typography.bodySmall.copy(
        fontWeight = FontWeight.SemiBold,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None
    )
    val stackedValueStyle = MaterialTheme.typography.bodySmall.copy(
        fontWeight = FontWeight.Medium,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None
    )

    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val subtitleColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
    val valueColor = when {
        !enabled -> MaterialTheme.colorScheme.outline
        useSecondaryValueStyle -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val dropdownTint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline

    Surface(
        color = Color.Transparent,
        modifier = tagModifier
            .fillMaxWidth()
            .then(interactionModifier)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val availableWidthPx = constraints.maxWidth
            val hasTrailingIcon = showDropdownIndicator || showNavigationArrow
            val dropdownWidthPx = if (hasTrailingIcon) {
                with(density) { 24.dp.roundToPx() }
            } else {
                0
            }

            val measuredSideBySideValueWidthPx = remember(
                trailingText,
                sideBySideValueStyle,
                density.density,
                density.fontScale
            ) {
                if (trailingText.isNullOrBlank()) {
                    0
                } else {
                    textMeasurer.measure(
                        text = trailingText,
                        style = sideBySideValueStyle,
                        maxLines = 1,
                        softWrap = false
                    ).size.width
                }
            }

            val useStacked = shouldUseStackedSettingsLayout(
                trailingText = trailingText,
                layoutMode = layoutMode,
                availableWidthPx = availableWidthPx,
                measuredValueWidthPx = measuredSideBySideValueWidthPx,
                dropdownWidthPx = dropdownWidthPx,
                density = density.density
            )

            if (useStacked) {
                val formattedValueText = remember(
                    trailingText,
                    availableWidthPx,
                    stackedValueStyle,
                    density.density,
                    density.fontScale
                ) {
                    if (trailingText.isNullOrBlank()) {
                        ""
                    } else {
                        wrapValueAtSeparators(
                            text = trailingText,
                            maxLineWidthPx = availableWidthPx
                        ) { candidate ->
                            textMeasurer.measure(
                                text = candidate,
                                style = stackedValueStyle,
                                maxLines = 1,
                                softWrap = false
                            ).size.width
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (testTag != null) Modifier.testTag("${testTag}_stacked_layout") else Modifier),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = title,
                            style = titleStyle,
                            color = titleColor,
                            softWrap = true,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (hasTrailingIcon) Modifier.padding(end = 8.dp) else Modifier)
                                .then(if (testTag != null) Modifier.testTag("${testTag}_title") else Modifier)
                        )
                        if (showNavigationArrow) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = dropdownTint,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (showDropdownIndicator) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = dropdownTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = subtitleStyle,
                            color = subtitleColor,
                            softWrap = true,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (testTag != null) Modifier.testTag("${testTag}_subtitle") else Modifier)
                        )
                    }

                    if (!trailingText.isNullOrBlank()) {
                        Text(
                            text = formattedValueText,
                            style = stackedValueStyle,
                            color = valueColor,
                            softWrap = true,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                                .semantics {
                                    if (formattedValueText != trailingText) {
                                        contentDescription = trailingText
                                    }
                                }
                                .then(if (testTag != null) Modifier.testTag("${testTag}_value") else Modifier)
                        )
                    }
                }
            } else {
                val maxSideBySideValueWidthDp = maxWidth * SETTINGS_ROW_VALUE_MAX_WIDTH_FRACTION

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (testTag != null) Modifier.testTag("${testTag}_side_by_side_layout") else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = titleStyle,
                            color = titleColor,
                            softWrap = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (testTag != null) Modifier.testTag("${testTag}_title") else Modifier)
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = subtitleStyle,
                                color = subtitleColor,
                                softWrap = true,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (testTag != null) Modifier.testTag("${testTag}_subtitle") else Modifier)
                            )
                        }
                    }

                    if (trailingText != null || hasTrailingIcon) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = maxSideBySideValueWidthDp)
                                .then(if (testTag != null) Modifier.testTag("${testTag}_value_container") else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (trailingText != null) {
                                Text(
                                    text = trailingText,
                                    style = sideBySideValueStyle,
                                    color = valueColor,
                                    softWrap = true,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .then(if (testTag != null) Modifier.testTag("${testTag}_value") else Modifier)
                                )
                            }
                            if (showNavigationArrow) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = dropdownTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else if (showDropdownIndicator) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = dropdownTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean, accessibility-safe settings item row with an on/off Material 3 Switch.
 * Automatically stacks the switch beneath the description only on extremely narrow viewports
 * (such as maximum display size + split screen) so titles never break mid-word.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null
) {
    val tagModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    val titleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.Medium,
        lineBreak = LineBreak.Heading,
        hyphens = Hyphens.None
    )
    val subtitleStyle = MaterialTheme.typography.bodyMedium.copy(
        lineBreak = LineBreak.Paragraph,
        hyphens = Hyphens.None
    )

    Surface(
        color = Color.Transparent,
        modifier = tagModifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val stackSwitch = maxWidth < SETTINGS_SWITCH_MIN_SIDE_BY_SIDE_WIDTH_DP.dp

            if (stackSwitch) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = subtitleStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        enabled = enabled
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = titleStyle,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = subtitleStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        enabled = enabled
                    )
                }
            }
        }
    }
}
