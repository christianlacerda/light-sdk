package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
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
 * Per-habit screen, reached by tapping a habit row while [HomeScreen]'s grid is in edit
 * mode. Holds the three actions a single habit row can't hold itself without turning
 * into icon clutter: Rename, Archive, Delete — see the milestone report for why edit
 * mode routes here instead of putting affordances directly on the row.
 *
 * Shares the live [HabitTrackerViewModel] instance rather than creating its own (same
 * pattern [HabitSettingsScreen] already uses) — there's no reason to re-read DataStore
 * into a second, independent copy of state that's already loaded.
 */
class HabitDetailScreen(
    sealedActivity: SealedLightActivity,
    private val viewModel: HabitTrackerViewModel,
    private val habitId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        var pendingDelete by remember { mutableStateOf(false) }

        val habit = state.habits.firstOrNull { it.id == habitId }

        LightTheme(colors = themeColors) {
            if (habit == null) {
                // Defensive fallback: this screen is the only place that removes or
                // archives the habit it's showing, and it always goes back immediately
                // after, so this shouldn't be reachable in practice — but bail cleanly
                // rather than crash if it somehow is.
                LaunchedEffect(Unit) { goBack() }
            } else if (pendingDelete) {
                val completionCount = state.completions[habit.id]?.size ?: 0
                HabitDeleteConfirmationContent(
                    message = deleteConfirmationMessage(habit.name, completionCount),
                    onCancel = { pendingDelete = false },
                    onConfirm = {
                        viewModel.deleteHabit(habit.id)
                        pendingDelete = false
                        goBack()
                    },
                )
            } else {
                HabitDetailContent(
                    habit = habit,
                    onBack = { goBack() },
                    onRename = {
                        navigateTo(screenFactory = {
                            AddHabitScreen(
                                it,
                                initialName = habit.name,
                                screenTitle = "Rename Habit",
                                submitLabel = "SAVE",
                            )
                        }) { newName ->
                            viewModel.renameHabit(habit.id, newName)
                        }
                    },
                    onArchive = {
                        viewModel.archiveHabit(habit.id)
                        goBack()
                    },
                    onDeleteRequested = {
                        val completionCount = state.completions[habit.id]?.size ?: 0
                        if (completionCount == 0) {
                            // Nothing would be lost — asking is pure ceremony.
                            viewModel.deleteHabit(habit.id)
                            goBack()
                        } else {
                            pendingDelete = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HabitDetailContent(
    habit: Habit,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(habit.name),
            modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp()),
        )

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            DetailActionRow(text = "Rename", onClick = onRename)
            DetailActionRow(text = "Archive", onClick = onArchive)
            DetailActionRow(text = "Delete", onClick = onDeleteRequested)
        }
    }
}

@Composable
private fun DetailActionRow(text: String, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
    )
}
