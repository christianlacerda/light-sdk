package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * A habit name gets exactly one line in the week grid (see `HabitBlock` in
 * HomeScreen.kt, Detail text variant, ~23/27 grid-width columns available after
 * the screen's horizontal padding). Measured on-device rather than guessed:
 * with realistic mixed-case, space-separated names the line held up to ~49-51
 * characters before crowding the right edge and ~57 before Compose ellipsized
 * it; an adversarial all-caps string (e.g. 30x 'M', the widest glyph in the
 * font) ellipsized well before that, around 29 characters, because glyph width
 * varies per character and this is a character-count cap, not a pixel-width
 * one. 40 sits with comfortable margin under the realistic-text measurement
 * while staying well clear of the worst-case width. `HabitBlock` also sets
 * maxLines = 1 with TextOverflow.Ellipsis as a hard backstop, so even a name
 * that's unexpectedly wide degrades to an ellipsis instead of wrapping and
 * breaking the grid's SpaceEvenly layout.
 */
const val HABIT_NAME_MAX_LENGTH = 40

/**
 * Full-screen habit-naming flow, reached via `navigateTo` from [HomeScreen] — either from
 * the bottom bar's `+` (add) or from a habit row's Rename action in edit mode (same
 * keyboard/validation flow, just pre-filled with the existing name and different
 * title/submit copy).
 *
 * A separate screen (rather than a modal) because naming needs the LP3 keyboard,
 * which is itself a full-screen affair (top bar + input + embedded keyboard +
 * bottom bar) - [com.thelightphone.sdk.ui.LightFullscreenModal] has no slot for
 * a keyboard or a text field, so there's no meaningful "modal" version of this.
 * See the milestone 3 report for the full modal-vs-screen writeup.
 *
 * Returns the trimmed name via `goBack(name)` on submit, or no result on cancel
 * (back button/gesture) - the SDK's back-stack only invokes the caller's result
 * callback when a non-null result was set, so a plain `goBack()` is enough to
 * signal "nothing changed."
 */
class AddHabitScreen(
    sealedActivity: SealedLightActivity,
    private val initialName: String = "",
    private val screenTitle: String = "Name Habit",
    private val submitLabel: String = "ADD",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialName)
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        // Truncate as-you-type rather than only validating on submit, so the field
        // never shows you typing past the point the grid can actually display.
        LaunchedEffect(textState) {
            snapshotFlow { textState.text.toString() }.collect { current ->
                if (current.length > HABIT_NAME_MAX_LENGTH) {
                    textState.edit {
                        delete(HABIT_NAME_MAX_LENGTH, current.length)
                        selection = TextRange(HABIT_NAME_MAX_LENGTH)
                    }
                }
            }
        }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = screenTitle,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                singleLine = true,
                submitLabel = submitLabel,
                onSubmit = { text ->
                    val trimmed = text.toString().trim()
                    // Reject empty/whitespace-only names by simply not returning -
                    // there's no error-message slot in LightTextInputEditor, so this
                    // is a silent no-op rather than a validation message. See report.
                    if (trimmed.isNotEmpty()) {
                        goBack(trimmed)
                    }
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
