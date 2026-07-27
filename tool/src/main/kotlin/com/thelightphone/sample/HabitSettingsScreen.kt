package com.thelightphone.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Settings screen, reached via the gear in [HomeScreen]'s bottom bar.
 *
 * Habit management itself (rename/archive/delete of *active* habits) lives on the
 * per-habit screen reached from the grid's edit mode ([HabitDetailScreen]) — one
 * management surface, not two. What's left here is what doesn't belong on a per-habit
 * screen: unarchiving (needs *a* home, and there's no more "active habits" list here to
 * put it next to) and the one real preference the tool has, week start day.
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
        val limitMessage by viewModel.limitMessage.collectAsState()
        var pendingDelete by remember { mutableStateOf<Habit?>(null) }

        val archivedHabits = state.habits.filter { it.archivedAt != null }.sortedBy { it.order }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                val toDelete = pendingDelete
                if (toDelete != null) {
                    val completionCount = state.completions[toDelete.id]?.size ?: 0
                    HabitDeleteConfirmationContent(
                        message = deleteConfirmationMessage(toDelete.name, completionCount),
                        onCancel = { pendingDelete = null },
                        onConfirm = {
                            viewModel.deleteHabit(toDelete.id)
                            pendingDelete = null
                        },
                    )
                } else {
                    HabitSettingsContent(
                        weekStart = state.weekStart,
                        archivedHabits = archivedHabits,
                        onBack = { goBack() },
                        onSetWeekStart = viewModel::setWeekStart,
                        onUnarchive = { habitId -> viewModel.unarchiveHabit(habitId) },
                        onRequestDelete = { habit ->
                            val completionCount = state.completions[habit.id]?.size ?: 0
                            if (completionCount == 0) {
                                // Nothing would be lost — asking is pure ceremony.
                                viewModel.deleteHabit(habit.id)
                            } else {
                                pendingDelete = habit
                            }
                        },
                    )

                    limitMessage?.let { message ->
                        LightFullscreenModal(
                            message = message,
                            onClose = viewModel::dismissLimitMessage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitSettingsContent(
    weekStart: WeekStart,
    archivedHabits: List<Habit>,
    onBack: () -> Unit,
    onSetWeekStart: (WeekStart) -> Unit,
    onUnarchive: (String) -> Unit,
    onRequestDelete: (Habit) -> Unit,
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

        LightScrollView(
            modifier = Modifier
                .weight(1f)
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

            Spacer(modifier = Modifier.height(1.5f.gridUnitsAsDp()))

            SectionHeader(text = "ARCHIVED")
            if (archivedHabits.isEmpty()) {
                EmptySectionRow(text = "No archived habits.")
            } else {
                archivedHabits.forEach { habit ->
                    ArchivedHabitRow(
                        habit = habit,
                        onUnarchive = { onUnarchive(habit.id) },
                        onDelete = { onRequestDelete(habit) },
                    )
                }
            }
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

@Composable
private fun EmptySectionRow(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
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

@Composable
private fun ArchivedHabitRow(habit: Habit, onUnarchive: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = habit.name,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowAction(text = "UNARCHIVE", onClick = onUnarchive)
        Box(modifier = Modifier.padding(start = 1f.gridUnitsAsDp())) {
            RowAction(text = "DELETE", onClick = onDelete)
        }
    }
}

@Composable
private fun RowAction(text: String, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        underline = true,
        modifier = Modifier.lightClickable(onClick = onClick),
    )
}
