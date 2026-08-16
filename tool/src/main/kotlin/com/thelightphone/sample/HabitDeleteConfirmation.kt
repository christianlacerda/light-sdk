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
 * makes the confirmation informative instead of ceremonial — so whenever there is history
 * to lose, this states how many days go with it.
 *
 * A habit with no completions loses nothing, and padding the question with "0 days will be
 * erased" would only make it read like boilerplate. It still asks, though: on the grid,
 * DELETE sits one tap away from RENAME, close enough that a mis-tap on a habit you just
 * created and mistyped is a realistic way to lose it on a device with no undo.
 */
internal fun deleteConfirmationMessage(habitName: String, completionCount: Int): String {
    if (completionCount == 0) return "Delete “$habitName”?"
    val days = if (completionCount == 1) "day" else "days"
    return "Delete “$habitName”? $completionCount $days will be erased."
}

/**
 * Inline (not a navigation-stack screen, not [com.thelightphone.sdk.ui.LightFullscreenModal])
 * two-choice delete confirmation — a fullscreen modal only has room for a message and a
 * single close button, and this needs two: cancel and confirm. Swapping the screen's own
 * content is the simplest way to get a two-choice prompt out of the primitives available.
 * Used by [HomeScreen], which is where every delete now starts — active and archived
 * habits alike are deleted from the grid's edit mode.
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
