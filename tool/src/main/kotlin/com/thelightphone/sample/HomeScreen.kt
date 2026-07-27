package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
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
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Habit Tracker — milestone 2 (real data model, persistence, tap-to-toggle).
 *
 * Habits now have stable identity ([Habit.id]) and completions are keyed by
 * habit id + epoch day ([HabitState.completions]), rather than "the 7 booleans
 * currently on screen." That's what makes persistence and archive-with-history
 * possible. Everything is stored as one JSON blob in the tool's DataStore
 * (see [HABIT_STATE_KEY]) — plenty for a handful of habits over a year of days,
 * and far simpler than pulling in Room for this size of data.
 */

@Serializable
data class Habit(
    val id: String,
    val name: String,
    /** Epoch day ([LocalDate.toEpochDay]) the habit was created. Informational only —
     *  tracking is not gated on this; a habit can log days before its creation date. */
    val createdAt: Long,
    /** Epoch day the habit was archived, or null if it's active. */
    val archivedAt: Long? = null,
    /** Display order among habits in the same state (active or archived). */
    val order: Int,
)

/** Which day the week grid (and the S M T W T F S header) starts on. Display-only —
 *  completions are keyed by epoch day, so changing this reorders the grid without
 *  touching any stored data. */
@Serializable
enum class WeekStart {
    SUNDAY,
    MONDAY,
    ;

    val dayOfWeek: DayOfWeek
        get() = if (this == SUNDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
}

@Serializable
data class HabitState(
    val habits: List<Habit> = emptyList(),
    /** habitId -> set of epoch days marked complete. */
    val completions: Map<String, Set<Long>> = emptyMap(),
    val weekStart: WeekStart = WeekStart.SUNDAY,
)

private fun dayLetterFor(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.SUNDAY -> "S"
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
}

/** Hard cap on the number of *active* habits that can exist at once. Archived habits don't count. */
private const val MAX_HABITS = 3

private const val ADD_LIMIT_MESSAGE = "3 habits is the limit — archive one to add another."
private const val RESTORE_LIMIT_MESSAGE = "3 habits is the limit — archive one to restore this."

private val HABIT_STATE_KEY = stringPreferencesKey("habit_state_json")

class HabitTrackerViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(HabitState())
    val state: StateFlow<HabitState> = _state.asStateFlow()

    /** True once the initial DataStore read has completed, so the UI can avoid a false
     *  "empty state" flash while the real (possibly non-empty) data is still loading. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _limitMessage = MutableStateFlow<String?>(null)
    val limitMessage: StateFlow<String?> = _limitMessage.asStateFlow()

    /** Whether the grid is in edit mode (entered via the bottom bar's EDIT/DONE toggle).
     *  In edit mode, day cells stop responding to taps and a habit row becomes a single
     *  tap target that opens [HabitDetailScreen]. */
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    fun toggleEditMode() {
        _editMode.value = !_editMode.value
    }

    fun exitEditMode() {
        _editMode.value = false
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = runCatching {
                val prefs = dataStore.data.first()
                prefs[HABIT_STATE_KEY]?.let { json.decodeFromString<HabitState>(it) }
            }.getOrNull() ?: HabitState()
            withContext(Dispatchers.Main) {
                _state.value = stored
                _loaded.value = true
            }
        }
    }

    val activeHabits: List<Habit>
        get() = _state.value.habits.filter { it.archivedAt == null }.sortedBy { it.order }

    val archivedHabits: List<Habit>
        get() = _state.value.habits.filter { it.archivedAt != null }.sortedBy { it.order }

    private val canAddHabit: Boolean
        get() = activeHabits.size < MAX_HABITS

    /**
     * Called when `+` is tapped. Returns true if the caller should navigate to the
     * naming screen; if the cap is already hit, it surfaces the limit message instead
     * and returns false.
     */
    fun requestAdd(): Boolean {
        if (!canAddHabit) {
            _limitMessage.value = ADD_LIMIT_MESSAGE
            return false
        }
        return true
    }

    fun addHabit(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canAddHabit) return
        val today = LocalDate.now().toEpochDay()
        val habit = Habit(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAt = today,
            archivedAt = null,
            order = nextOrder(),
        )
        updateAndPersist { it.copy(habits = it.habits + habit) }
    }

    /**
     * Tapping a day cell toggles that habit's completion for that date. Fires the
     * DataStore write immediately (see [updateAndPersist]) — the real usage pattern is
     * tap-then-pocket, so a debounced or exit-time write would silently lose taps.
     */
    fun toggleCompletion(habitId: String, epochDay: Long) {
        if (epochDay > LocalDate.now().toEpochDay()) return // defense in depth; UI shouldn't call this for future days
        updateAndPersist { state ->
            val current = state.completions[habitId] ?: emptySet()
            val updated = if (epochDay in current) current - epochDay else current + epochDay
            state.copy(completions = state.completions + (habitId to updated))
        }
    }

    /** Archive is not delete — completion history is untouched, the habit just stops
     *  appearing in the grid and frees up an active slot. */
    fun archiveHabit(habitId: String) {
        val today = LocalDate.now().toEpochDay()
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(archivedAt = today) else it
                },
            )
        }
    }

    /** Returns true if the habit was unarchived; false (with the limit message surfaced)
     *  if 3 habits are already active. */
    fun unarchiveHabit(habitId: String): Boolean {
        if (!canAddHabit) {
            _limitMessage.value = RESTORE_LIMIT_MESSAGE
            return false
        }
        val order = nextOrder()
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(archivedAt = null, order = order) else it
                },
            )
        }
        return true
    }

    /** Permanently removes a habit and its completion history. Only reachable from the
     *  archived list, behind a confirmation step in the UI. */
    fun deleteHabit(habitId: String) {
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.filterNot { it.id == habitId },
                completions = state.completions - habitId,
            )
        }
    }

    fun dismissLimitMessage() {
        _limitMessage.value = null
    }

    /** Renaming has no effect on history or archive state — it's a pure label change,
     *  reachable from [HabitDetailScreen]'s Rename action. No-op on a blank name. */
    fun renameHabit(habitId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(name = trimmed) else it
                },
            )
        }
    }

    /** Display-only preference — reorders the grid's day-letter header and week grouping.
     *  Completions are keyed by epoch day, so no data migration is needed. */
    fun setWeekStart(weekStart: WeekStart) {
        updateAndPersist { it.copy(weekStart = weekStart) }
    }

    fun completionCount(habitId: String): Int = _state.value.completions[habitId]?.size ?: 0

    private fun nextOrder(): Int = (_state.value.habits.maxOfOrNull { it.order } ?: -1) + 1

    /**
     * Updates in-memory state synchronously (so the UI reflects the tap immediately)
     * and fires the DataStore write right away on IO — not debounced, not batched, not
     * deferred to screen exit — since a habit tracker's worst bug is a tap that silently
     * didn't save because the app got backgrounded a moment later.
     */
    private fun updateAndPersist(transform: (HabitState) -> HabitState) {
        val newState = transform(_state.value)
        _state.value = newState
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[HABIT_STATE_KEY] = json.encodeToString(newState)
                }
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HabitTrackerViewModel>(sealedActivity) {

    override val viewModelClass: Class<HabitTrackerViewModel>
        get() = HabitTrackerViewModel::class.java

    override fun createViewModel() = HabitTrackerViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val limitMessage by viewModel.limitMessage.collectAsState()
        val editMode by viewModel.editMode.collectAsState()
        val activeHabits = remember(state) { state.habits.filter { it.archivedAt == null }.sortedBy { it.order } }

        LightTheme(colors = themeColors) {
            HabitTrackerScreen(
                habits = activeHabits,
                completions = state.completions,
                weekStart = state.weekStart,
                loaded = loaded,
                limitMessage = limitMessage,
                editMode = editMode,
                onAddTapped = {
                    if (viewModel.requestAdd()) {
                        navigateTo(screenFactory = { AddHabitScreen(it) }) { name ->
                            viewModel.addHabit(name)
                        }
                    }
                },
                onSettingsTapped = {
                    navigateTo(screenFactory = { HabitSettingsScreen(it, viewModel) })
                },
                onToggle = viewModel::toggleCompletion,
                onToggleEditMode = viewModel::toggleEditMode,
                onHabitRowTapped = { habitId ->
                    navigateTo(screenFactory = { HabitDetailScreen(it, viewModel, habitId) })
                },
                onDismissLimitMessage = viewModel::dismissLimitMessage,
            )
        }
    }
}

@Composable
private fun HabitTrackerScreen(
    habits: List<Habit>,
    completions: Map<String, Set<Long>>,
    weekStart: WeekStart,
    loaded: Boolean,
    limitMessage: String?,
    editMode: Boolean,
    onAddTapped: () -> Unit,
    onSettingsTapped: () -> Unit,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onToggleEditMode: () -> Unit,
    onHabitRowTapped: (habitId: String) -> Unit,
    onDismissLimitMessage: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    val today = remember { LocalDate.now() }
    val todayEpoch = remember(today) { today.toEpochDay() }
    val gridWeekStart = remember(today, weekStart) {
        today.with(TemporalAdjusters.previousOrSame(weekStart.dayOfWeek))
    }
    val todayIndex = remember(today, gridWeekStart) {
        ChronoUnit.DAYS.between(gridWeekStart, today).toInt()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    contentDescription = "Previous week",
                    onClick = {},
                ),
                // Edit mode gets its own unambiguous label here instead of the week
                // range — this is the first thing a glance at the screen lands on, and
                // it doesn't depend on noticing the bottom bar's DONE label or the
                // per-row borders below.
                center = LightTopBarCenter.Text(if (editMode) "Editing" else weekRangeLabel(gridWeekStart)),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ARROW_RIGHT,
                    contentDescription = "Next week",
                    onClick = {},
                ),
            )

            if (!loaded) {
                Spacer(modifier = Modifier.weight(1f))
            } else if (habits.isEmpty()) {
                EmptyHabitsContent(modifier = Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    // Fixed top-anchored rhythm: the day-letter row always sits directly
                    // under the top bar, and habit blocks stack below it with a constant
                    // gap. (Previously Arrangement.SpaceEvenly redistributed the leftover
                    // vertical space across every gap, which looked fine at 3 habits but
                    // left the day-letter row floating with ~300px of dead space above it
                    // at 1-2 habits — any unused space now just collects at the bottom,
                    // which reads as normal rather than broken.)
                    verticalArrangement = Arrangement.Top,
                ) {
                    DayLetterRow(weekStart = gridWeekStart, todayIndex = todayIndex)

                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    habits.forEachIndexed { index, habit ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))
                        }
                        HabitBlock(
                            habit = habit,
                            completedDays = completions[habit.id] ?: emptySet(),
                            weekStart = gridWeekStart,
                            todayIndex = todayIndex,
                            todayEpoch = todayEpoch,
                            editMode = editMode,
                            onToggle = onToggle,
                            onRowTap = { onHabitRowTapped(habit.id) },
                        )
                    }
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        contentDescription = "Settings",
                        onClick = onSettingsTapped,
                    ),
                    // Icon + text + icon in 3 slots hits LightBottomBar's mixed layout
                    // (SpaceBetween) — matches the Notes tool idiom. This is the ceiling:
                    // a text item caps the bar at 3 items, so there's no room for a 4th.
                    LightBarButton.Text(
                        text = if (editMode) "DONE" else "EDIT",
                        onClick = onToggleEditMode,
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        contentDescription = "Add habit",
                        onClick = onAddTapped,
                    ),
                ),
            )
        }

        // Transient, dismissible explanation of the 3-habit cap. `+` still responds to
        // a tap at the cap — it just explains itself instead of silently doing nothing.
        limitMessage?.let { message ->
            LightFullscreenModal(
                message = message,
                onClose = onDismissLimitMessage,
            )
        }
    }
}

/**
 * Calm first-run / all-habits-archived state. No loud call to action — just an
 * explanation, in the same typography as the rest of the tool, that tapping `+`
 * is what to do next.
 */
@Composable
private fun EmptyHabitsContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "No habits yet. Tap + to add one.",
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun DayLetterRow(weekStart: LocalDate, todayIndex: Int) {
    val letters = remember(weekStart) {
        (0..6).map { dayLetterFor(weekStart.plusDays(it.toLong()).dayOfWeek) }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        letters.forEachIndexed { index, letter ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = letter,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = index != todayIndex,
                    underline = index == todayIndex,
                )
            }
        }
    }
}

/**
 * Not editing: a plain row, cells individually tappable to toggle completion.
 *
 * Editing: the entire row — name and all 7 cells — becomes one bordered tap target
 * that opens [HabitDetailScreen]. Deliberately no per-row delete/action icon: a habit
 * row is already compound (name + a 7-cell strip), so a second affordance crammed into
 * a ~30dp-tall row would be cramped and easy to mis-tap. One large, forgiving target
 * per row instead — cells stop responding to taps individually (see [DayCheckbox]),
 * so a tap anywhere in the row, cells included, falls through to this box.
 */
@Composable
private fun HabitBlock(
    habit: Habit,
    completedDays: Set<Long>,
    weekStart: LocalDate,
    todayIndex: Int,
    todayEpoch: Long,
    editMode: Boolean,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onRowTap: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    val rowContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = habit.name,
                variant = LightTextVariant.Detail,
                // The grid allots exactly one line per habit name; a name that's somehow
                // longer than HABIT_NAME_MAX_LENGTH (shouldn't happen — the naming screen
                // enforces the cap while typing) degrades to an ellipsis instead of
                // wrapping and breaking the layout below it.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayIndex in 0..6) {
                    val epochDay = weekStart.plusDays(dayIndex.toLong()).toEpochDay()
                    val isFuture = epochDay > todayEpoch
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        DayCheckbox(
                            filled = epochDay in completedDays,
                            isToday = dayIndex == todayIndex,
                            isFuture = isFuture,
                            editMode = editMode,
                            onToggle = if (editMode || isFuture) null else { { onToggle(habit.id, epochDay) } },
                        )
                    }
                }
            }
        }
    }

    if (editMode) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = colors.content)
                .lightClickable(
                    onClickLabel = "Edit ${habit.name}",
                    onClick = onRowTap,
                )
                .padding(horizontal = 0.6f.gridUnitsAsDp(), vertical = 0.4f.verticalGridUnitsAsDp()),
        ) {
            rowContent()
        }
    } else {
        rowContent()
    }
}

/**
 * A single day's check box. Filled = done, outlined = not done — both are neutral;
 * there's no "streak broken" styling for a miss. Today gets a separate outer ring
 * with a visible gap from the box itself, so the emphasis reads in monochrome even
 * when the box is filled (a same-color thicker border on a filled square would be
 * invisible against its own fill).
 *
 * Future days render with a secondary (dimmer) border and don't respond to taps —
 * clearly not-yet-available rather than just "unchecked." In edit mode every cell gets
 * that same muted treatment regardless of date (dimmer border, dimmer fill, no today
 * ring, no click) so the strip visibly reads as inert while editing rather than looking
 * like a still-live grid a tap might silently toggle.
 */
@Composable
private fun DayCheckbox(filled: Boolean, isToday: Boolean, isFuture: Boolean, editMode: Boolean, onToggle: (() -> Unit)?) {
    val colors = LightThemeTokens.colors
    val cellSize = 2.3f.gridUnitsAsDp()
    val haloSize = cellSize + 10.dp
    val dimmed = isFuture || editMode
    val borderColor = if (dimmed) colors.contentSecondary else colors.content
    val fillColor = when {
        !filled -> Color.Transparent
        editMode -> colors.contentSecondary
        else -> colors.content
    }
    val showTodayRing = isToday && !editMode

    Box(
        modifier = Modifier
            .size(haloSize)
            .let { base ->
                if (onToggle != null) {
                    base.lightClickable(
                        onClickLabel = "Toggle completion",
                        onClick = onToggle,
                    )
                } else {
                    base
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showTodayRing) {
            Box(
                modifier = Modifier
                    .size(haloSize)
                    .border(width = 1.5.dp, color = colors.content),
            )
        }
        Box(
            modifier = Modifier
                .size(cellSize)
                .border(width = 1.dp, color = borderColor)
                .background(fillColor),
        )
    }
}

private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d")
private val DAY_ONLY_FORMAT = DateTimeFormatter.ofPattern("d")

private fun weekRangeLabel(weekStart: LocalDate): String {
    val weekEnd = weekStart.plusDays(6)
    val endLabel = if (weekStart.month == weekEnd.month) {
        weekEnd.format(DAY_ONLY_FORMAT)
    } else {
        weekEnd.format(MONTH_DAY_FORMAT)
    }
    return "${weekStart.format(MONTH_DAY_FORMAT)}–$endLabel"
}
