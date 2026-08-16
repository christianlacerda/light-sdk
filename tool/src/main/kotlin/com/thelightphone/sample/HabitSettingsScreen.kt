package com.thelightphone.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Preferences, reached via the gear in [HomeScreen]'s resting bottom bar.
 *
 * Everything about the *habits* — add, rename, archive, unarchive, delete — lives on the
 * habit rows in [HomeScreen]'s edit mode, including archived ones, which are listed below
 * the active habits there. What's left here is what that split leaves behind: preferences,
 * of which the tool has exactly one.
 *
 * A one-option settings screen is thin, and deliberately so. The alternative was keeping
 * content management here to pad it out, which is what made the report feel misfiled when
 * it lived behind this gear. The screen earns its place by holding the only thing that is
 * genuinely a preference, not by being full.
 *
 * Takes the same [HabitTrackerViewModel] instance the home screen uses (constructor
 * injection, same pattern as `AuthenticatorCodeScreen` sharing a repository) rather than
 * creating its own — [SimpleLightScreen] isn't a [com.thelightphone.sdk.LightScreen], so
 * it has no ViewModelStore of its own, and there's no reason to re-read DataStore into a
 * second, independent copy of the same state when one is already loaded and live.
 */
class HabitSettingsScreen(
    sealedActivity: SealedLightActivity,
    private val viewModel: HabitTrackerViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            HabitSettingsContent(
                weekStart = state.weekStart,
                onBack = { goBack() },
                onSetWeekStart = viewModel::setWeekStart,
            )
        }
    }
}

@Composable
private fun HabitSettingsContent(
    weekStart: WeekStart,
    onBack: () -> Unit,
    onSetWeekStart: (WeekStart) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        // No scroll container: one section of two rows cannot overflow, and a scrollbar
        // gutter on a screen this short only advertises content that isn't there.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            SectionHeader(text = "WEEK STARTS ON")
            WeekStartRow(
                label = "Sunday",
                selected = weekStart == WeekStart.SUNDAY,
                onClick = { onSetWeekStart(WeekStart.SUNDAY) },
            )
            WeekStartRow(
                label = "Monday",
                selected = weekStart == WeekStart.MONDAY,
                onClick = { onSetWeekStart(WeekStart.MONDAY) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}

/** One radio-style row per option; whichever matches the current preference shows a
 *  filled selection mark. Tapping either always resolves to a definite state (no
 *  "deselect" case) since exactly one of Sunday/Monday is always in effect. */
@Composable
private fun WeekStartRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            size = 1.6f,
            modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
        )
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}
