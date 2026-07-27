package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * A plain "are you sure?" gets tapped through reflexively. Naming what's actually lost
 * makes the confirmation informative instead of ceremonial — so this always states the
 * number of days that would be erased.
 *
 * Only meant to be called when [completionCount] > 0. A habit with zero completions has
 * nothing to lose, so callers should skip this entirely and delete immediately instead
 * of asking a question with an obvious answer (see [HabitDetailScreen] and
 * [HabitSettingsScreen] delete call sites).
 */
internal fun deleteConfirmationMessage(habitName: String, completionCount: Int): String {
    val days = if (completionCount == 1) "day" else "days"
    return "Delete “$habitName”? $completionCount $days will be erased."
}

/**
 * Inline (not a navigation-stack screen, not [com.thelightphone.sdk.ui.LightFullscreenModal])
 * two-choice delete confirmation — a fullscreen modal only has room for a message and a
 * single close button, and this needs two: cancel and confirm. Swapping the screen's own
 * content is the simplest way to get a two-choice prompt out of the primitives available.
 * Shared by [HabitDetailScreen] (active habits) and [HabitSettingsScreen] (archived habits)
 * so both use one message format and one look.
 */
@Composable
internal fun HabitDeleteConfirmationContent(message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = LightThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CANCEL", onClick = onCancel),
                LightBarButton.Text(text = "DELETE", onClick = onConfirm),
            ),
        )
    }
}
