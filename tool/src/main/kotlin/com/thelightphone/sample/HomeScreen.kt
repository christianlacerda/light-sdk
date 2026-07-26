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
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Habit Tracker — step 3 (add-a-habit flow, in memory only).
 *
 * Builds on the milestone 1 static grid: habits now live in a view model and can be
 * added via the `+` button in the bottom bar. Still no persistence (habits vanish on
 * relaunch — that's a later milestone), no tap-to-toggle, no settings, no archive/delete.
 */

data class Habit(val name: String, val completed: List<Boolean>)

private val DAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")

/** Hard cap on the number of habits that can exist at once. */
private const val MAX_HABITS = 3

private const val LIMIT_MESSAGE = "3 habits is the limit — archive one to add another."

/**
 * Empty in-memory habit history for a freshly-added habit: no days are marked done yet.
 */
private fun freshHabit(name: String) = Habit(name, List(7) { false })

class HabitTrackerViewModel : LightViewModel<Unit>() {

    // Seeded with 2 (not 3) habits on purpose: it leaves the `+` button usable on first
    // launch so both the add flow and the 3-habit-limit flow are reachable without a
    // delete/archive UI, which is out of scope for this milestone.
    private val _habits = MutableStateFlow(
        listOf(
            Habit("Exercise", listOf(true, true, false, true, false, false, false)),
            Habit("Read", listOf(true, false, true, true, true, false, false)),
        ),
    )
    val habits: StateFlow<List<Habit>> = _habits.asStateFlow()

    private val _limitMessage = MutableStateFlow<String?>(null)
    val limitMessage: StateFlow<String?> = _limitMessage.asStateFlow()

    private val canAddHabit: Boolean
        get() = _habits.value.size < MAX_HABITS

    /**
     * Called when `+` is tapped. Returns true if the caller should navigate to the
     * naming screen; if the cap is already hit, it surfaces the limit message instead
     * and returns false.
     */
    fun requestAdd(): Boolean {
        if (!canAddHabit) {
            _limitMessage.value = LIMIT_MESSAGE
            return false
        }
        return true
    }

    fun addHabit(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canAddHabit) return
        _habits.value = _habits.value + freshHabit(trimmed)
    }

    fun dismissLimitMessage() {
        _limitMessage.value = null
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HabitTrackerViewModel>(sealedActivity) {

    override val viewModelClass: Class<HabitTrackerViewModel>
        get() = HabitTrackerViewModel::class.java

    override fun createViewModel() = HabitTrackerViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val habits by viewModel.habits.collectAsState()
        val limitMessage by viewModel.limitMessage.collectAsState()

        LightTheme(colors = themeColors) {
            HabitTrackerScreen(
                habits = habits,
                limitMessage = limitMessage,
                onAddTapped = {
                    if (viewModel.requestAdd()) {
                        navigateTo(::AddHabitScreen) { name ->
                            viewModel.addHabit(name)
                        }
                    }
                },
                onDismissLimitMessage = viewModel::dismissLimitMessage,
            )
        }
    }
}

@Composable
private fun HabitTrackerScreen(
    habits: List<Habit>,
    limitMessage: String?,
    onAddTapped: () -> Unit,
    onDismissLimitMessage: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    val today = remember { LocalDate.now() }
    val weekStart = remember(today) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    }
    val todayIndex = remember(today, weekStart) {
        ChronoUnit.DAYS.between(weekStart, today).toInt()
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
                center = LightTopBarCenter.Text(weekRangeLabel(weekStart)),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ARROW_RIGHT,
                    contentDescription = "Next week",
                    onClick = {},
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                // Distribute the day-letter row + habit blocks evenly across whatever
                // vertical space is left between the top and bottom bars, rather than a
                // fixed gap. The grid math guarantees up to MAX_HABITS all fit without
                // scrolling; this just uses the slack instead of leaving it as dead
                // space at the end.
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                DayLetterRow(todayIndex = todayIndex)

                habits.forEach { habit ->
                    HabitBlock(habit = habit, todayIndex = todayIndex)
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        contentDescription = "Settings",
                        onClick = {},
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

@Composable
private fun DayLetterRow(todayIndex: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        DAY_LETTERS.forEachIndexed { index, letter ->
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

@Composable
private fun HabitBlock(habit: Habit, todayIndex: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = habit.name,
            variant = LightTextVariant.Detail,
            // The grid allots exactly one line per habit name; a name that's somehow
            // longer than HABIT_NAME_MAX_LENGTH (shouldn't happen — the naming screen
            // enforces the cap while typing) degrades to an ellipsis instead of
            // wrapping and breaking the SpaceEvenly layout below it.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

        Row(modifier = Modifier.fillMaxWidth()) {
            habit.completed.forEachIndexed { index, done ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    DayCheckbox(filled = done, isToday = index == todayIndex)
                }
            }
        }
    }
}

/**
 * A single day's check box. Filled = done, outlined = not done — both are neutral;
 * there's no "streak broken" styling for a miss. Today gets a separate outer ring
 * with a visible gap from the box itself, so the emphasis reads in monochrome even
 * when the box is filled (a same-color thicker border on a filled square would be
 * invisible against its own fill).
 */
@Composable
private fun DayCheckbox(filled: Boolean, isToday: Boolean) {
    val colors = LightThemeTokens.colors
    val cellSize = 2.3f.gridUnitsAsDp()
    val haloSize = cellSize + 10.dp

    Box(
        modifier = Modifier.size(haloSize),
        contentAlignment = Alignment.Center,
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .size(haloSize)
                    .border(width = 1.5.dp, color = colors.content),
            )
        }
        Box(
            modifier = Modifier
                .size(cellSize)
                .border(width = 1.dp, color = colors.content)
                .background(if (filled) colors.content else Color.Transparent),
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
