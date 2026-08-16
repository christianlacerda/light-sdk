package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
internal fun LocalDate.snappedToWeekStart(weekStart: WeekStart): LocalDate =
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

/** Width of a day checkbox, in grid units. Also the width of a month bar in
 *  [HabitReportScreen], so the report's columns read as the same instrument as the
 *  week strip rather than a chart bolted on. */
internal const val DAY_CELL_UNITS = 2.3f

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

/** How far the day-letter header fades in edit mode, where it heads columns that aren't
 *  drawn. Present-but-quiet: enough to keep the screen recognisable, not enough to read
 *  as an active label. Note the letters are already [LightText] `lighten`-ed apart from
 *  today's, so this multiplies a contrast that isn't full to begin with. */
private const val EDIT_MODE_DAY_LETTER_ALPHA = 0.5f

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
     *  In edit mode each habit's day strip is replaced in place by its three management
     *  actions — Rename, Archive, Delete — so managing a habit costs no extra screen. */
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
     *  reachable from a habit row's Rename action in edit mode. No-op on a blank name. */
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

    /** Re-reads the clock. [onScreenShow] covers the home screen, but a [SimpleLightScreen]
     *  sharing this view model (settings, report) never triggers it, so a session left open
     *  across midnight would draw those screens against yesterday's date. */
    fun refreshToday() {
        _today.value = LocalDate.now()
    }

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
        var pendingDelete by remember { mutableStateOf<Habit?>(null) }

        LightTheme(colors = themeColors) {
            val toDelete = pendingDelete
            if (toDelete != null) {
                // Delete now sits one tap from Rename on the habit row itself, so it always
                // asks — see HabitDeleteConfirmationContent. The prompt replaces this
                // screen's content rather than pushing a screen, so answering it returns
                // straight to the grid still in edit mode.
                HabitDeleteConfirmationContent(
                    message = deleteConfirmationMessage(
                        habitName = toDelete.name,
                        completionCount = state.completions[toDelete.id]?.size ?: 0,
                    ),
                    onCancel = { pendingDelete = null },
                    onConfirm = {
                        viewModel.deleteHabit(toDelete.id)
                        pendingDelete = null
                    },
                )
            } else {
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
                    onReportTapped = {
                        navigateTo(screenFactory = { HabitReportScreen(it, viewModel) })
                    },
                    onToggle = viewModel::toggleCompletion,
                    onToggleEditMode = viewModel::toggleEditMode,
                    onRenameHabit = { habit ->
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
                    onArchiveHabit = { habit -> viewModel.archiveHabit(habit.id) },
                    onDeleteHabit = { habit -> pendingDelete = habit },
                    onDismissLimitMessage = viewModel::dismissLimitMessage,
                    onPreviousWeek = viewModel::goToPreviousWeek,
                    onNextWeek = viewModel::goToNextWeek,
                )
            }
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
    onReportTapped: () -> Unit,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onToggleEditMode: () -> Unit,
    onRenameHabit: (Habit) -> Unit,
    onArchiveHabit: (Habit) -> Unit,
    onDeleteHabit: (Habit) -> Unit,
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
                        .padding(horizontal = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp()),
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

                    DayLetterRow(
                        weekStart = displayedWeekStart,
                        todayIndex = todayIndex,
                        markToday = !editMode,
                        // Faded, not removed, while editing. The letters head seven day
                        // columns that edit mode replaces, so at full strength they'd be
                        // labelling nothing — but keeping them faintly visible holds the
                        // screen recognisably the same screen rather than swapping it for
                        // a different one. Removing the row outright would also pull every
                        // habit up by its height, which is the jolt this layout avoids.
                        modifier = Modifier.alpha(if (editMode) EDIT_MODE_DAY_LETTER_ALPHA else 1f),
                    )

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
                            onRename = { onRenameHabit(habit) },
                            onArchive = { onArchiveHabit(habit) },
                            onDelete = { onDeleteHabit(habit) },
                        )
                    }
                }
            }

            // Icon + text + icon in 3 slots hits LightBottomBar's mixed layout
            // (SpaceBetween) — matches the Notes tool idiom. This is the ceiling: a text
            // item caps the bar at 3 items, so there's no room for a 4th. The gear holds
            // the left slot in both modes so only the two changing controls move.
            //
            // What sits in the other two slots is a bet on frequency at rest. Ticking a
            // day needs no button at all, so past the grid the recurring thing is looking
            // at the trend; adding and editing are setup, done once and then rarely.
            // Report therefore takes the centre, and editing steps back to a pencil.
            //
            // `+` only appears while editing. Adding and renaming/archiving/deleting are
            // one job — managing habits — and splitting them across two slots left `+`
            // on the resting screen doing nothing: at MAX_HABITS `requestAdd` can only
            // raise a modal explaining itself. Inside edit mode that modal at least lands
            // somewhere it can be acted on, with ARCHIVE already on every row.
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        contentDescription = "Settings",
                        onClick = onSettingsTapped,
                    ),
                    if (editMode) {
                        LightBarButton.Text(text = "DONE", onClick = onToggleEditMode)
                    } else {
                        LightBarButton.Text(text = "REPORT", onClick = onReportTapped)
                    },
                    if (editMode) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "Add habit",
                            onClick = onAddTapped,
                        )
                    } else {
                        LightBarButton.LightIcon(
                            icon = LightIcons.PENCIL,
                            contentDescription = "Edit habits",
                            onClick = onToggleEditMode,
                        )
                    },
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
            modifier = Modifier.padding(horizontal = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp()),
        )
    }
}

/**
 * @param markToday whether today's letter is picked out from the rest. Off in edit mode:
 *   the underline points down at today's cell, and edit mode doesn't draw the cells, so
 *   marking a column that isn't there is the one part of the faded header that actively
 *   misleads rather than just lingering.
 */
@Composable
private fun DayLetterRow(
    weekStart: LocalDate,
    todayIndex: Int,
    markToday: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val letters = remember(weekStart) {
        (0..6).map { dayLetterFor(weekStart.plusDays(it.toLong()).dayOfWeek) }
    }
    Row(modifier = modifier.fillMaxWidth()) {
        letters.forEachIndexed { index, letter ->
            val isToday = markToday && index == todayIndex
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = letter,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = !isToday,
                    underline = isToday,
                )
            }
        }
    }
}

/**
 * Not editing: the habit's name over its 7-day strip, cells individually tappable.
 *
 * Editing: the same name in the same place, with the strip swapped in place for the
 * habit's three management actions. The strip is inert in edit mode anyway — dimmed,
 * unclickable, present only to hold the layout — so the row's lower half is spent on
 * decoration. Putting Rename/Archive/Delete there instead costs no extra screen and no
 * extra tap, and every habit's actions are visible at once.
 *
 * The action row is deliberately [MIN_TOUCH_TARGET] tall, the same height the day strip
 * occupies, so a row's total height is identical in both modes and toggling edit doesn't
 * shift the grid vertically.
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
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
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
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

        if (editMode) {
            HabitActionRow(
                habitName = habit.name,
                onRename = onRename,
                onArchive = onArchive,
                onDelete = onDelete,
            )
        } else {
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
                            onToggle = if (inRange) { { onToggle(habit.id, epochDay) } } else null,
                        )
                    }
                }
            }
        }
    }
}

/** Gap between two adjacent actions. Wide enough that DELETE is a deliberate reach from
 *  ARCHIVE rather than the next thing along, which matters more here than saving width —
 *  there's plenty of it going spare once the three are clustered. */
private const val HABIT_ACTION_GAP_UNITS = 1.5f

/**
 * A habit's three management actions, occupying the band the day strip would otherwise
 * fill. Clustered against the right edge rather than spread across the full width: the
 * habit name owns the left of the row, so ending the actions flush with the content's
 * right edge (where the name's own line ends) keeps one clean vertical edge down the
 * screen instead of three columns of text starting at unrelated places.
 *
 * Underlined [LightTextVariant.Detail] matches the inline row actions already used for
 * archived habits in [HabitSettingsScreen] — same idiom, one place to change it — and
 * keeps the actions from out-shouting the habit name, which is set at the same size.
 *
 * The row is pinned to [MIN_TOUCH_TARGET], which is also what the day strip occupied, so
 * a habit's total height doesn't change when edit mode toggles. Each action fills that
 * full height, so its target is the band around the word, not just the glyphs.
 */
@Composable
private fun HabitActionRow(
    habitName: String,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MIN_TOUCH_TARGET),
        horizontalArrangement = Arrangement.spacedBy(
            space = HABIT_ACTION_GAP_UNITS.gridUnitsAsDp(),
            alignment = Alignment.End,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HabitAction("RENAME", "Rename $habitName", onRename)
        HabitAction("ARCHIVE", "Archive $habitName", onArchive)
        HabitAction("DELETE", "Delete $habitName", onDelete)
    }
}

@Composable
private fun HabitAction(label: String, clickLabel: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .lightClickable(onClickLabel = clickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            underline = true,
            maxLines = 1,
        )
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
 * "unchecked."
 *
 * Edit mode needs no muted variant of this: the strip isn't drawn at all while editing
 * (see [HabitBlock]), so there is no inert grid on screen for a tap to look live against.
 */
@Composable
private fun DayCheckbox(filled: Boolean, isToday: Boolean, isFuture: Boolean, onToggle: (() -> Unit)?) {
    val colors = LightThemeTokens.colors
    val cellSize = DAY_CELL_UNITS.gridUnitsAsDp()
    val haloSize = cellSize + 10.dp
    // Hit area only. The halo stays at its drawn size so raising the target doesn't inflate
    // the today ring along with it — 45dp of drawn halo, 48dp of tappable box around it.
    val touchSize = maxOf(haloSize, MIN_TOUCH_TARGET)
    val borderColor = if (isFuture) colors.contentSecondary else colors.content
    val fillColor = if (filled) colors.content else Color.Transparent
    val showTodayRing = isToday

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
