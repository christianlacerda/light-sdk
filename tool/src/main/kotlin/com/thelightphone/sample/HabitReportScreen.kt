package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBarButtonDefaults
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Months shown at once. Six is what lets a bar stay [DAY_CELL_UNITS] wide — at twelve the
 *  columns fall to ~1.9u and the bars stop matching the day cells they are meant to echo. */
private const val REPORT_MONTHS = 6

/**
 * Height of the plot area.
 *
 * Budget: 31u total, less 3u of top bar and 5u of bottom bar (4u plus its 1u margin) leaves
 * 23u for three blocks and the band below the bar. A block is its name, this plot, a baseline
 * and a month axis, so the plot is what gives way when the Close button takes its 5u.
 */
private const val CHART_HEIGHT_UNITS = 4.3f

/** Gap between a habit's name and the top of its plot. At the old 0.3u a bar at full height
 *  came within a hair of the name above it and the two read as one clump. */
private const val NAME_TO_PLOT_UNITS = 0.8f

/**
 * Floor height for a month with at least one completion.
 *
 * One day in a 31-day month is ~3% of the plot, which at a true scale is a 2dp sliver that
 * reads as a thicker baseline — indistinguishable from a month where nothing happened. Four dp
 * overstates a single day slightly but keeps "once" visibly different from "never", and
 * confusing some with none is the worse of the two lies on a screen that exists to be read at
 * a glance.
 */
private val MIN_VISIBLE_BAR = 4.dp

private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM")

/** [LightTopBar]'s own metrics, copied so [ReportPagingBar] lines up with every other
 *  screen's bar. Kept private here rather than shared — they mirror SDK internals, and the
 *  only honest way to keep them in step is to notice if the SDK's bar ever moves. */
private const val PAGING_BAR_HEIGHT_UNITS = 3f
private const val PAGING_BAR_PADDING_UNITS = 1f

/** Visible enough to hold its position and stay recognisable as a chevron, faint enough
 *  that it never reads as the live control sitting opposite it. */
private const val DISABLED_CHEVRON_ALPHA = 0.3f

/**
 * Monthly trend report, reached from REPORT in [HomeScreen]'s bottom bar.
 *
 * One line per habit, a vertical bar per month, no numbers anywhere. The question it answers
 * is "more or less than before", not "how many" — counting invites the scoreboard reading the
 * tool deliberately avoids (see the no-streaks decision). Bar *height* carries the whole
 * signal, and the shape of the bars is what identifies the screen, so it carries no title.
 *
 * Active habits only. An archived habit's history is preserved and still reachable — unarchive
 * it from [HomeScreen]'s edit mode and it reappears here — but showing archived lines meant a
 * scrollable screen, and the scrollbar cost horizontal room and made a screen whose whole job
 * is legibility look busy.
 *
 * Shares the one [HabitTrackerViewModel] rather than loading its own copy — the state is
 * already live and there is nothing to persist here. This screen is read-only.
 */
class HabitReportScreen(
    sealedActivity: SealedLightActivity,
    private val viewModel: HabitTrackerViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun willShow() {
        // A SimpleLightScreen never reaches LightViewModel.onScreenShow, so without this a
        // session left open across midnight would bucket today into yesterday's month.
        viewModel.refreshToday()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val today by viewModel.today.collectAsState()

        // View state, not tool state: which six-month window is on screen has nothing to
        // persist and no bearing on the home grid, so it lives here rather than in the shared
        // view model. 0 = window ending this month, negative = older windows.
        var windowOffset by remember { mutableIntStateOf(0) }

        val habits = state.habits.filter { it.archivedAt == null }.sortedBy { it.order }

        val currentMonth = remember(today) { YearMonth.from(today) }
        // Floored on the habits actually drawn. Including archived ones here would let `‹`
        // walk back through months whose only habit renders nowhere on this screen — six
        // empty columns with no way to tell why.
        val earliestMonth = remember(habits, currentMonth) {
            earliestHabitMonth(habits, currentMonth)
        }
        val minOffset = remember(earliestMonth, currentMonth) {
            minWindowOffset(earliestMonth, currentMonth)
        }

        // Clamped on read rather than only on tap: archiving the oldest habit while parked in
        // an old window shrinks the record under our feet, and an offset past the new floor
        // would render six empty columns.
        val clampedOffset = windowOffset.coerceIn(minOffset, 0)
        val window = remember(currentMonth, clampedOffset) {
            monthWindow(currentMonth, clampedOffset)
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Range in the middle, paging either side. Floored at the first recorded
                // month and never forward past the current window, so the report cannot show
                // a month that hasn't happened. The way out is Close at the bottom, which
                // leaves both bar slots free for navigation.
                ReportPagingBar(
                    label = windowLabel(window),
                    canGoEarlier = clampedOffset > minOffset,
                    canGoLater = clampedOffset < 0,
                    onEarlier = { windowOffset = clampedOffset - 1 },
                    onLater = { windowOffset = clampedOffset + 1 },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                ) {
                    if (!loaded) return@Column

                    if (habits.isEmpty()) {
                        EmptyReport(hasArchived = state.habits.any { it.archivedAt != null })
                        return@Column
                    }

                    // Same 1.2u the home screen puts below its top bar.
                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    habits.forEach { habit ->
                        HabitTrendBlock(
                            bars = monthBars(habit, state, window, today),
                            name = habit.name,
                            currentMonth = currentMonth,
                        )
                    }
                }

                // Text rather than a glyph: `‹` is the paging control on this screen, so the
                // way out has to be unmistakably not that. Sits in the bar the home screen
                // already uses for its controls, and a lone item centres itself there.
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "CLOSE", onClick = { goBack() }),
                    ),
                )
            }
        }
    }
}

/**
 * One month's column for one habit.
 *
 * [fraction] is the share of *trackable* days completed, not of calendar days: a habit created
 * on the 20th is only answerable for the days it actually existed. Dividing by the full month
 * instead would draw a permanent dip at the start of a habit's life that says nothing about
 * the person.
 */
private data class MonthBar(
    val month: YearMonth,
    val fraction: Float,
    /** False when the habit did not exist at all that month — an empty column, not a zero. */
    val trackable: Boolean,
    /** The month still running. Drawn hollow, and scaled by days elapsed (see [monthBars]). */
    val inProgress: Boolean,
)

private fun monthWindow(currentMonth: YearMonth, offset: Int): List<YearMonth> {
    val anchor = currentMonth.plusMonths(offset.toLong() * REPORT_MONTHS)
    return (REPORT_MONTHS - 1 downTo 0).map { anchor.minusMonths(it.toLong()) }
}

private fun earliestHabitMonth(habits: List<Habit>, fallback: YearMonth): YearMonth =
    habits.minOfOrNull { it.createdAt }
        ?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
        ?: fallback

/**
 * Most negative window offset that still shows the first recorded month.
 *
 * Derived from where the record starts rather than fixed, so `‹` stops at the edge of real
 * data instead of walking back through empty windows. Solving `windowStart <= earliest` for
 * the offset gives `floor((REPORT_MONTHS - 1 - span) / REPORT_MONTHS)`.
 */
private fun minWindowOffset(earliest: YearMonth, current: YearMonth): Int {
    val span = ChronoUnit.MONTHS.between(earliest, current).toInt()
    return Math.floorDiv(REPORT_MONTHS - 1 - span, REPORT_MONTHS).coerceAtMost(0)
}

private fun monthBars(
    habit: Habit,
    state: HabitState,
    window: List<YearMonth>,
    today: LocalDate,
): List<MonthBar> {
    val completions = state.completions[habit.id].orEmpty()
    val currentMonth = YearMonth.from(today)

    // Completions may legitimately predate createdAt within the creation week — the home grid
    // gates tracking at week granularity so day one is usable. Snapping the lower bound the
    // same way keeps those days inside the denominator instead of pushing a fraction over 1.
    val firstTrackable = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(state.weekStart)

    return window.map { month ->
        val from = maxOf(month.atDay(1), firstTrackable)
        // An in-progress month is measured against the days elapsed so far, so mid-August
        // compares fairly with a finished July instead of reading as a collapse all month.
        val to = minOf(month.atEndOfMonth(), today)

        if (to < from) {
            MonthBar(month, fraction = 0f, trackable = false, inProgress = month == currentMonth)
        } else {
            val possible = ChronoUnit.DAYS.between(from, to).toInt() + 1
            val done = completions.count { it in from.toEpochDay()..to.toEpochDay() }
            MonthBar(
                month = month,
                fraction = (done.toFloat() / possible).coerceIn(0f, 1f),
                trackable = true,
                inProgress = month == currentMonth,
            )
        }
    }
}

private fun windowLabel(window: List<YearMonth>): String {
    val first = window.first()
    val last = window.last()
    val firstLabel = first.format(MONTH_LABEL_FORMAT).uppercase()
    val lastLabel = last.format(MONTH_LABEL_FORMAT).uppercase()
    // Year shown once when the window sits inside one year, on both ends when it straddles —
    // six months back from January is a different year, and an unqualified "AUG" there would
    // be read as this year's.
    return if (first.year == last.year) {
        "$firstLabel – $lastLabel ${last.year}"
    } else {
        "$firstLabel ${first.year} – $lastLabel ${last.year}"
    }
}

/**
 * The report's own top bar, hand-built to [LightTopBar]'s metrics rather than using it.
 *
 * Both chevrons are always drawn, dimmed when there's nowhere to go. That costs a custom
 * bar because [LightBarButton] has no disabled state — its buttons are either present at
 * full strength or absent — and absence is the one thing that can't happen here. A lone
 * `‹` in the top-left slot is the universal back affordance; on a screen that leaves by a
 * Close button at the bottom, showing it only when paging is available would read as "go
 * back" and mean "go back six months". Keeping both anchored means a chevron's position
 * always says what it does and only its weight says whether it can.
 *
 * Everything here mirrors [LightTopBar]: 3u tall, 1u side padding, [LightTextVariant.Fine]
 * for the label, [LightBarButtonDefaults.ICON_SIZE_UNITS] icons — so it sits at the same
 * height and reads as the same furniture as every other screen's bar.
 */
@Composable
private fun ReportPagingBar(
    label: String,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAGING_BAR_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(horizontal = PAGING_BAR_PADDING_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagingChevron(
            icon = LightIcons.BACK,
            description = "Earlier months",
            enabled = canGoEarlier,
            onClick = onEarlier,
        )
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PagingChevron(
            icon = LightIcons.ARROW_RIGHT,
            description = "Later months",
            enabled = canGoLater,
            onClick = onLater,
        )
    }
}

/** Dimmed *and* unclickable when disabled — a chevron that looks live and does nothing on
 *  tap is worse than one that never invited the tap. The content description drops with the
 *  click so a screen reader doesn't announce an action that isn't there. */
@Composable
private fun PagingChevron(
    icon: LightIconConfiguration,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    LightIcon(
        icon = icon,
        size = LightBarButtonDefaults.ICON_SIZE_UNITS,
        contentDescription = if (enabled) description else null,
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_CHEVRON_ALPHA)
            .then(
                if (enabled) {
                    Modifier.lightClickable(onClickLabel = description, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun HabitTrendBlock(bars: List<MonthBar>, name: String, currentMonth: YearMonth) {
    Column(modifier = Modifier.padding(bottom = 0.6f.verticalGridUnitsAsDp())) {
        LightText(
            text = name,
            // Detail, matching the habit name above each week strip on the home screen. The
            // two screens name the same thing and should weigh the same doing it.
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(NAME_TO_PLOT_UNITS.verticalGridUnitsAsDp()))

        val chartHeight = CHART_HEIGHT_UNITS.verticalGridUnitsAsDp()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    MonthBarView(bar = bar, chartHeight = chartHeight)
                }
            }
        }

        // Baseline. Without it a month with no completions and empty space look the same, and
        // the bars have nothing to sit on.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )

        Spacer(modifier = Modifier.height(0.25f.verticalGridUnitsAsDp()))

        // Axis under its own line rather than one shared row up top: each chart then carries
        // its own labels in the same columns as its bars, so reading a bar never means
        // tracking back up past two other habits to find out which month it is.
        MonthLabelRow(bars = bars, currentMonth = currentMonth)
    }
}

/** Three-letter month names, not the single letters the day strip uses. S/M/T/W/T/F/S is a
 *  convention people already read; J/F/M/A/M/J is not, and three of those letters are
 *  ambiguous. There is room for the longer form at six columns. */
@Composable
private fun MonthLabelRow(bars: List<MonthBar>, currentMonth: YearMonth) {
    Row(modifier = Modifier.fillMaxWidth()) {
        bars.forEach { bar ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = bar.month.format(MONTH_LABEL_FORMAT),
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = bar.month != currentMonth,
                    underline = bar.month == currentMonth,
                )
            }
        }
    }
}

@Composable
private fun MonthBarView(bar: MonthBar, chartHeight: Dp) {
    if (!bar.trackable) return

    val color = LightThemeTokens.colors.content
    val barHeight = (chartHeight * bar.fraction).coerceAtLeast(
        if (bar.fraction > 0f) MIN_VISIBLE_BAR else 0.dp,
    )
    if (barHeight <= 0.dp) return

    Box(
        modifier = Modifier
            .width(DAY_CELL_UNITS.gridUnitsAsDp())
            .height(barHeight)
            .then(
                // Hollow while the month is still running, solid once it's closed — the same
                // outline-vs-fill language the day cells already use for done/not-done. Without
                // it, every visit in the first days of a month would show a cliff that isn't real.
                if (bar.inProgress) Modifier.border(1.dp, color) else Modifier.background(color),
            ),
    )
}

@Composable
private fun EmptyReport(hasArchived: Boolean) {
    // Two different empties. "Nothing tracked yet" and "everything tracked is archived, and
    // this screen doesn't draw archived lines" look identical on screen, but only the second
    // has something the reader can do about it. Saying the same thing for both would leave a
    // person with real history staring at a screen that looks broken.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(
            text = if (hasArchived) {
                "No active habits. Unarchive one to see its trend."
            } else {
                "Nothing to report yet."
            },
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}
