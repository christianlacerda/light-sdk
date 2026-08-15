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
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Epoch day ([LocalDate.toEpochDay]) the habit was created. Gates tracking at week
     *  granularity, not the exact day — a habit can log days before its creation date as
     *  long as they fall in the same week, so day-one still works (create Wednesday, tick
     *  Monday and Tuesday of that week). */
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

/** The Sunday/Monday (per [WeekStart]) on or before this date — the shared "which week
 *  is this day in" formula used for the grid header, offset clamping, and creation-week
 *  gating, so those three don't drift against each other. */
private fun LocalDate.snappedToWeekStart(weekStart: WeekStart): LocalDate =
    with(TemporalAdjusters.previousOrSame(weekStart.dayOfWeek))

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

/** Width of a day checkbox, in grid units. */
private const val DAY_CELL_UNITS = 2.3f

/** Grid units of horizontal padding either side of the grid content (see [HabitTrackerScreen]). */
private const val CONTENT_SIDE_PADDING_UNITS = 2f

/**
 * Android's minimum touch target. Deliberately a raw dp rather than a grid unit: it is an
 * ergonomic floor tied to fingertip size, so it must stay 48dp however the 27x31 design grid
 * happens to map onto the device. A day cell's column is (27 - 2*2)/7 = 3.29u, about 50dp on
 * an LP3, so this fits with roughly a dp of clearance either side and adjacent cells never
 * overlap.
 */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * How far a day checkbox sits inside its column edge. The seven columns split the content
 * width evenly and each cell is centred in its column, so the last cell's right edge stops
 * short of the content's right edge by this much. Anything meant to line up with the strip —
 * the edit pencil — needs the same inset, or it visibly overhangs.
 */
private const val DAY_CELL_EDGE_INSET_UNITS =
    ((LightGrid.WIDTH - 2 * CONTENT_SIDE_PADDING_UNITS) / 7f - DAY_CELL_UNITS) / 2f

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

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /** 0 = current week, negative = weeks in the past. Never positive — an offset, not
     *  an anchor date, so it self-corrects when [_today] refreshes across midnight rather
     *  than pinning the grid to a date that's since become "the future." */
    private val _weekOffset = MutableStateFlow(0)
    val weekOffset: StateFlow<Int> = _weekOffset.asStateFlow()

    val canGoBack: StateFlow<Boolean> = combine(_weekOffset, _state, _today) { offset, _, _ ->
        offset > minWeekOffset()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canGoForward: StateFlow<Boolean> = _weekOffset
        .map { it < 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        _today.value = LocalDate.now()
        clampWeekOffset()
    }

    // Deliberately NO onAppPause reset. "Relaunching lands on the current week" already
    // happens for free — the ViewModel dies with the process — whereas onAppPause fires
    // on every screen-off too (LightActivity.onPause). Resetting there meant a backfill
    // interrupted by a screen timeout silently returned to the current week, and since
    // the day strip is geometrically identical between weeks, the next tap would write to
    // today's cell instead of the intended one.

    // No onBackPressed override. LightViewModel.onBackPressed() is unreachable for a root
    // screen: the back dispatcher calls LightActivity.goBack() directly, which pops the
    // stack and finishes, and only LightScreen.goBack(result) consults the view model —
    // nothing calls that here. An override would read as working week-reset behaviour while
    // doing nothing. Intercepting back would need an SDK change, which this fork doesn't make.

    /** Week the record starts: earliest createdAt among ACTIVE habits only. Archived
     *  habits are excluded even though their history is real, because they render in no
     *  week — flooring on them made `‹` walk back through weeks whose grid was empty or
     *  fully dimmed, and left both chevrons live on the "No habits yet" empty state. The
     *  reachable range should promise exactly as much history as the screen can show, so
     *  unarchiving a habit correctly extends the floor back again. */
    private fun earliestWeekStart(): LocalDate =
        (_state.value.habits.filter { it.archivedAt == null }.minOfOrNull { it.createdAt }
            ?.let { LocalDate.ofEpochDay(it) } ?: _today.value)
            .snappedToWeekStart(_state.value.weekStart)

    private fun currentWeekStart(): LocalDate = _today.value.snappedToWeekStart(_state.value.weekStart)

    private fun minWeekOffset(): Int =
        (-ChronoUnit.WEEKS.between(earliestWeekStart(), currentWeekStart())).toInt().coerceAtMost(0)

    private fun clampWeekOffset() {
        _weekOffset.value = _weekOffset.value.coerceIn(minWeekOffset(), 0)
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value - 1).coerceAtLeast(minWeekOffset())
    }

    fun goToNextWeek() {
        _weekOffset.value = (_weekOffset.value + 1).coerceAtMost(0)
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
        val habit = _state.value.habits.find { it.id == habitId } ?: return
        val startWeekEpoch = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(_state.value.weekStart).toEpochDay()
        if (epochDay < startWeekEpoch) return // defense in depth; UI shouldn't call this before the habit's creation week
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
        clampWeekOffset()
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
        val today by viewModel.today.collectAsState()
        val weekOffset by viewModel.weekOffset.collectAsState()
        val canGoBack by viewModel.canGoBack.collectAsState()
        val canGoForward by viewModel.canGoForward.collectAsState()
        val activeHabits = remember(state) { state.habits.filter { it.archivedAt == null }.sortedBy { it.order } }

        LightTheme(colors = themeColors) {
            HabitTrackerScreen(
                habits = activeHabits,
                completions = state.completions,
                weekStart = state.weekStart,
                today = today,
                weekOffset = weekOffset,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
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
                onPreviousWeek = viewModel::goToPreviousWeek,
                onNextWeek = viewModel::goToNextWeek,
            )
        }
    }
}

@Composable
private fun HabitTrackerScreen(
    habits: List<Habit>,
    completions: Map<String, Set<Long>>,
    weekStart: WeekStart,
    today: LocalDate,
    weekOffset: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loaded: Boolean,
    limitMessage: String?,
    editMode: Boolean,
    onAddTapped: () -> Unit,
    onSettingsTapped: () -> Unit,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onToggleEditMode: () -> Unit,
    onHabitRowTapped: (habitId: String) -> Unit,
    onDismissLimitMessage: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    val todayEpoch = remember(today) { today.toEpochDay() }
    val displayedWeekStart = remember(today, weekStart, weekOffset) {
        today.snappedToWeekStart(weekStart).plusWeeks(weekOffset.toLong())
    }
    // Only the current week (offset 0) can contain today; a past week's index range
    // (7..13 relative to its own start) would otherwise happen to fall outside 0..6 and
    // just look right by accident rather than by an explicit check.
    val todayIndex = remember(weekOffset, displayedWeekStart, today) {
        if (weekOffset == 0) ChronoUnit.DAYS.between(displayedWeekStart, today).toInt() else -1
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            LightTopBar(
                // Hidden rather than dimmed when unavailable: a spacer keeps the bar's
                // geometry identical (see LightBarButtonView's null branch), and a chevron
                // that's simply absent when there's nowhere to go means no control on this
                // screen ever does nothing when tapped — the presence of `›` alone tells
                // you you're not on the current week. Also off in edit mode, which doesn't
                // navigate weeks at all.
                leftButton = if (canGoBack && !editMode) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        contentDescription = "Previous week",
                        onClick = onPreviousWeek,
                    )
                } else {
                    null
                },
                // Edit mode gets its own unambiguous label here instead of the week
                // range — this is the first thing a glance at the screen lands on, and
                // it doesn't depend on noticing the bottom bar's DONE label or the
                // per-row borders below.
                center = LightTopBarCenter.Text(if (editMode) "Editing" else weekRangeLabel(displayedWeekStart, today)),
                rightButton = if (canGoForward && !editMode) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.ARROW_RIGHT,
                        contentDescription = "Next week",
                        onClick = onNextWeek,
                    )
                } else {
                    null
                },
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
                    // Fixed top-anchored rhythm: the day-letter row sits a constant gap
                    // below the top bar, and habit blocks stack below it with the same
                    // gap. (Previously Arrangement.SpaceEvenly redistributed the leftover
                    // vertical space across every gap, which looked fine at 3 habits but
                    // left the day-letter row floating with ~300px of dead space above it
                    // at 1-2 habits — any unused space now just collects at the bottom,
                    // which reads as normal rather than broken.)
                    verticalArrangement = Arrangement.Top,
                ) {
                    // Same 1.2u used below the letters, so the row reads as its own band
                    // rather than as part of the top bar it was previously flush against.
                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    DayLetterRow(weekStart = displayedWeekStart, todayIndex = todayIndex)

                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    habits.forEachIndexed { index, habit ->
                        if (index > 0) {
                            if (editMode) {
                                // The chevron says a row opens something; this says where one
                                // tap target ends and the next begins. Splitting the usual gap
                                // around the line keeps the vertical rhythm identical in both
                                // modes, so toggling edit doesn't shift the rows.
                                Spacer(modifier = Modifier.height(0.6f.verticalGridUnitsAsDp()))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(LightThemeTokens.colors.contentSecondary),
                                )
                                Spacer(modifier = Modifier.height(0.6f.verticalGridUnitsAsDp()))
                            } else {
                                Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))
                            }
                        }
                        HabitBlock(
                            habit = habit,
                            completedDays = completions[habit.id] ?: emptySet(),
                            weekStart = displayedWeekStart,
                            todayIndex = todayIndex,
                            todayEpoch = todayEpoch,
                            startWeekEpoch = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(weekStart).toEpochDay(),
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
 * Editing: the entire row — name and all 7 cells — becomes one tap target that opens
 * [HabitDetailScreen], marked by a chevron in the name row rather than a box drawn
 * around the row. Deliberately no per-row delete/action icon: a habit
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
    startWeekEpoch: Long,
    editMode: Boolean,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onRowTap: () -> Unit,
) {
    val rowContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = habit.name,
                    variant = LightTextVariant.Detail,
                    // The grid allots exactly one line per habit name; a name that's somehow
                    // longer than HABIT_NAME_MAX_LENGTH (shouldn't happen — the naming screen
                    // enforces the cap while typing) degrades to an ellipsis instead of
                    // wrapping and breaking the layout below it.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Editing: a chevron in the name row's otherwise-empty right side. It says
                // "this opens something", which is what a row tap actually does — a box
                // drawn around the row would only say "these things are grouped".
                if (editMode) {
                    // A pencil rather than a chevron: a chevron carries a convention of
                    // "row navigation, vertically centred", and centring it against the
                    // full row means putting it beside the strip — which would cut the
                    // strip below the 350dp that keeps day cells at 50dp. A pencil marks
                    // the row as editable without implying that geometry.
                    //
                    // Sized to the name's text line, not larger: a taller glyph stretches
                    // this Row and pushes the day strip down, which shifts every habit's
                    // position the moment edit mode is entered. Kept on the right so the
                    // name stays left-aligned with the first day cell in both modes.
                    LightIcon(
                        icon = LightIcons.PENCIL,
                        size = 0.8f,
                        contentDescription = null,
                        modifier = Modifier.padding(end = DAY_CELL_EDGE_INSET_UNITS.gridUnitsAsDp()),
                    )
                }
            }

            Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayIndex in 0..6) {
                    val epochDay = weekStart.plusDays(dayIndex.toLong()).toEpochDay()
                    val inRange = epochDay in startWeekEpoch..todayEpoch
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        DayCheckbox(
                            filled = epochDay in completedDays,
                            isToday = dayIndex == todayIndex,
                            isFuture = !inRange,
                            editMode = editMode,
                            onToggle = if (editMode || !inRange) null else { { onToggle(habit.id, epochDay) } },
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
                // No extra vertical padding: the row content (name + strip) is already
                // ~80dp tall, well past the 48dp target minimum, and padding here used to
                // exist only to sit inside the old border. Without it the rows land at the
                // same y in both modes, so toggling edit doesn't visibly jolt the grid.
                .lightClickable(
                    onClickLabel = "Edit ${habit.name}",
                    onClick = onRowTap,
                ),
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
 * Days outside a habit's trackable range — after today, or before the week its habit was
 * created in — render with a secondary (dimmer) border and don't respond to taps: clearly
 * not-yet-available (future) or not-yet-existing (pre-creation) rather than just
 * "unchecked." In edit mode every cell gets that same muted treatment regardless of date
 * (dimmer border, dimmer fill, no today ring, no click) so the strip visibly reads as
 * inert while editing rather than looking like a still-live grid a tap might silently
 * toggle.
 */
@Composable
private fun DayCheckbox(filled: Boolean, isToday: Boolean, isFuture: Boolean, editMode: Boolean, onToggle: (() -> Unit)?) {
    val colors = LightThemeTokens.colors
    val cellSize = DAY_CELL_UNITS.gridUnitsAsDp()
    val haloSize = cellSize + 10.dp
    // Hit area only. The halo stays at its drawn size so raising the target doesn't inflate
    // the today ring along with it — 45dp of drawn halo, 48dp of tappable box around it.
    val touchSize = maxOf(haloSize, MIN_TOUCH_TARGET)
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
            .size(touchSize)
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
private val MONTH_DAY_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMM d yyyy")
private val DAY_ONLY_FORMAT = DateTimeFormatter.ofPattern("d")

private fun weekRangeLabel(weekStart: LocalDate, today: LocalDate): String {
    val weekEnd = weekStart.plusDays(6)
    val endLabel = if (weekStart.month == weekEnd.month) {
        weekEnd.format(DAY_ONLY_FORMAT)
    } else {
        weekEnd.format(MONTH_DAY_FORMAT)
    }
    // A week viewed months into the past can straddle a year boundary a plain "MMM d"
    // start would silently misrepresent — spell out the year only when it differs from
    // the year currently on screen elsewhere, not on every past week.
    val startLabel = if (weekStart.year != today.year) {
        weekStart.format(MONTH_DAY_YEAR_FORMAT)
    } else {
        weekStart.format(MONTH_DAY_FORMAT)
    }
    return "$startLabel–$endLabel"
}
