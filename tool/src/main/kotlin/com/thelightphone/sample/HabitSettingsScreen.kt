package com.thelightphone.sample

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Habit management screen, reached via the settings gear in [HomeScreen]'s bottom bar.
 *
 * Takes the same [HabitTrackerViewModel] instance the home screen uses (constructor
 * injection, same pattern as `AuthenticatorCodeScreen` sharing a repository) rather than
 * creating its own — [SimpleLightScreen] isn't a [com.thelightphone.sdk.LightScreen], so
 * it has no ViewModelStore of its own, and there's no reason to re-read DataStore into a
 * second, independent copy of the same state when one is already loaded and live.
 *
 * Archive is reversible and lives here as a single tap. Delete is only reachable from the
 * archived list and always goes through an inline confirmation step — there is
 * deliberately no delete action anywhere on the main grid.
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

        val activeHabits = state.habits.filter { it.archivedAt == null }.sortedBy { it.order }
        val archivedHabits = state.habits.filter { it.archivedAt != null }.sortedBy { it.order }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                val toDelete = pendingDelete
                if (toDelete != null) {
                    DeleteConfirmationContent(
                        habitName = toDelete.name,
                        onCancel = { pendingDelete = null },
                        onConfirm = {
                            viewModel.deleteHabit(toDelete.id)
                            pendingDelete = null
                        },
                    )
                } else {
                    HabitSettingsContent(
                        activeHabits = activeHabits,
                        archivedHabits = archivedHabits,
                        onBack = { goBack() },
                        onArchive = viewModel::archiveHabit,
                        onUnarchive = { habitId -> viewModel.unarchiveHabit(habitId) },
                        onRequestDelete = { habit -> pendingDelete = habit },
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
    activeHabits: List<Habit>,
    archivedHabits: List<Habit>,
    onBack: () -> Unit,
    onArchive: (String) -> Unit,
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
            center = LightTopBarCenter.Text("Habits"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            SectionHeader(text = "ACTIVE")
            if (activeHabits.isEmpty()) {
                EmptySectionRow(text = "No active habits.")
            } else {
                activeHabits.forEach { habit ->
                    ActiveHabitRow(
                        habit = habit,
                        onArchive = { onArchive(habit.id) },
                    )
                }
            }

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

@Composable
private fun ActiveHabitRow(habit: Habit, onArchive: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = habit.name,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowAction(text = "ARCHIVE", onClick = onArchive)
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

/**
 * Inline (not a navigation-stack screen, not [LightFullscreenModal]) delete confirmation —
 * a fullscreen modal only has room for a message and a single close button, and this needs
 * two: cancel and confirm. Swapping the screen's own content is the simplest way to get a
 * two-choice prompt out of the primitives available.
 */
@Composable
private fun DeleteConfirmationContent(habitName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
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
                text = "Delete “$habitName”? Its history can't be recovered.",
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
