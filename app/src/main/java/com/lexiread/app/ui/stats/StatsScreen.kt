package com.lexiread.app.ui.stats

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.app.data.model.ReadingDay
import com.lexiread.app.data.repository.ReadingStats
import com.lexiread.app.domain.goal.ReadingGoal
import com.lexiread.app.ui.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the reading habit actually looks like, which until now the app knew and never said.
 *
 * Every figure here already existed — the streak has been counted since the first version and
 * shown as one number on the library strip. The screen is not new arithmetic, it is the same
 * arithmetic given room: a day against its goal, a week against the weeks before it, half a year
 * as a shape you can see gaps in.
 *
 * Deliberately not a scoreboard. There is no target this screen can call you short of except the
 * one you set yourself, no comparison with anybody else, and no red — a missed day is an empty
 * square, not a failure. The one number allowed to be loud is the streak, because it is the only
 * one that is genuinely at stake today.
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val goalMinutes by settingsViewModel.dailyGoalMinutes.collectAsStateWithLifecycle()
    var showGoalPicker by remember { mutableStateOf(false) }

    // Android 13 put notifications behind a runtime permission, and the congratulation is a
    // notification. Asked for at the moment a goal is set, which is the only moment it makes
    // sense of: the reader has just said they want to be told. Refused, the goal still counts
    // and only the congratulation is missing, so it is never insisted on.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Granted or not, the reading is counted either way. */ }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text  = "Чтение",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text  = summaryLine(stats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.fillMaxSize().padding(padding)
        ) {
            item {
                TodayCard(
                    todaySeconds = stats.todaySeconds,
                    goalMinutes  = goalMinutes,
                    streakDays   = stats.streakDays,
                    onEditGoal   = { showGoalPicker = true }
                )
            }
            item { StreakRow(stats) }
            item { WeekCard(stats.days) }
            item { CalendarCard(stats.days) }
            item { PaceCard(stats) }
        }
    }

    if (showGoalPicker) {
        GoalPickerSheet(
            current   = goalMinutes,
            onPick    = { minutes ->
                settingsViewModel.setDailyGoalMinutes(minutes)
                if (minutes > ReadingGoal.OFF && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = { showGoalPicker = false }
        )
    }
}

// ── Today, against the goal ───────────────────────────────────────────────────

/**
 * The day's ring.
 *
 * A ring rather than a bar because a day's goal is a thing that closes: a bar at 90 % invites
 * the question "of what", where a ring nearly shut is legible from the doorway. Past the goal it
 * keeps counting but stops filling — overshooting is not worth a second lap, and a ring that
 * resets to a sliver at 101 % would read as losing everything.
 */
@Composable
private fun TodayCard(
    todaySeconds: Int,
    goalMinutes: Int,
    streakDays: Int,
    onEditGoal: () -> Unit
) {
    val minutes = todaySeconds / 60
    val goalSet = goalMinutes > ReadingGoal.OFF
    val fraction = if (goalSet) (minutes.toFloat() / goalMinutes).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, label = "goal-ring")
    val done = goalSet && minutes >= goalMinutes

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape  = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
                val track = MaterialTheme.colorScheme.surfaceVariant
                val fill  = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = 10.dp.toPx()
                    val inset = width / 2
                    val arcSize = Size(size.width - width, size.height - width)
                    drawArc(
                        color      = track,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter  = false,
                        topLeft    = androidx.compose.ui.geometry.Offset(inset, inset),
                        size       = arcSize,
                        style      = Stroke(width = width, cap = StrokeCap.Round)
                    )
                    if (animated > 0f) {
                        drawArc(
                            color      = fill,
                            startAngle = -90f,
                            sweepAngle = 360f * animated,
                            useCenter  = false,
                            topLeft    = androidx.compose.ui.geometry.Offset(inset, inset),
                            size       = arcSize,
                            style      = Stroke(width = width, cap = StrokeCap.Round)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text  = if (minutes > 0) minutes.toString() else "0",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text  = "мин",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Сегодня",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        done && streakDays > 1 ->
                            "Цель дня взята. ${streakDays} ${plural(streakDays, "день", "дня", "дней")} подряд."
                        done      -> "Цель дня взята."
                        goalSet   -> "До цели ещё ${goalMinutes - minutes} ${plural(goalMinutes - minutes, "минута", "минуты", "минут")}."
                        else      -> "Цель не поставлена. Учитывается только время."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onEditGoal,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (goalSet) "Цель: $goalMinutes мин в день" else "Поставить цель")
                }
            }
        }
    }
}

// ── Streak, record, total ─────────────────────────────────────────────────────

@Composable
private fun StreakRow(stats: ReadingStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .height(IntrinsicSize.Min)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cell(
            value     = if (stats.streakDays > 0) stats.streakDays.toString() else "—",
            unit      = if (stats.streakDays > 0) plural(stats.streakDays, "день", "дня", "дней") else null,
            label     = "подряд",
            highlight = stats.streakDays > 0,
            modifier  = Modifier.weight(1f)
        )
        VerticalHairline()
        Cell(
            value    = if (stats.bestStreakDays > 0) stats.bestStreakDays.toString() else "—",
            unit     = if (stats.bestStreakDays > 0) plural(stats.bestStreakDays, "день", "дня", "дней") else null,
            label    = "рекорд",
            modifier = Modifier.weight(1f)
        )
        VerticalHairline()
        val total = splitDuration(stats.totalSeconds)
        Cell(value = total.first, unit = total.second, label = "всего", modifier = Modifier.weight(1f))
    }
}

// ── The week ──────────────────────────────────────────────────────────────────

/**
 * Seven columns, today on the right.
 *
 * Scaled to the tallest of the seven rather than to the goal: the point of this block is the
 * shape of the week — which days are the reading days — and against a goal of an hour a good
 * twenty-minute day would be a stub indistinguishable from a bad one.
 */
@Composable
private fun WeekCard(days: List<ReadingDay>) {
    val byDate = remember(days) { days.associateBy { it.date } }
    val keys   = remember(days) { lastDayKeys(7).reversed() }
    val minutes = keys.map { (byDate[it]?.seconds ?: 0) / 60 }
    val peak = (minutes.maxOrNull() ?: 0).coerceAtLeast(1)

    SectionCard(title = "Неделя") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            keys.forEachIndexed { index, key ->
                val value = minutes[index]
                val isToday = index == keys.lastIndex
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text  = if (value > 0) value.toString() else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    // A day with nothing on it still gets a sliver, so the row reads as seven
                    // days one of which is empty rather than as four days. The track is a fixed
                    // height and the bar fills a fraction of it — a bar that sized the column
                    // itself would push the weekday label off the bottom on a big day.
                    val heightFraction = if (value <= 0) 0.03f else (value.toFloat() / peak).coerceAtLeast(0.08f)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(heightFraction)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        value <= 0 -> MaterialTheme.colorScheme.surfaceVariant
                                        isToday    -> MaterialTheme.colorScheme.primary
                                        else       -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    }
                                )
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = weekdayLetter(key),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ── Half a year ───────────────────────────────────────────────────────────────

/**
 * A square per day, a column per week, the last [CALENDAR_WEEKS] of them.
 *
 * Half a year and not a full one because a year at this width is a three-pixel square, and the
 * thing worth seeing here is a gap — the fortnight in March when the reading stopped — which
 * needs a square big enough to be missing.
 */
@Composable
private fun CalendarCard(days: List<ReadingDay>) {
    val byDate = remember(days) { days.associateBy { it.date } }
    val grid   = remember(days) { calendarKeys() }
    val peak   = remember(days) {
        (days.maxOfOrNull { it.seconds } ?: 0).coerceAtLeast(600)
    }

    SectionCard(title = "Последние полгода") {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            grid.forEach { week ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    week.forEach { key ->
                        val seconds = if (key == null) null else byDate[key]?.seconds ?: 0
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(calendarColor(seconds, peak))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "меньше",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            listOf(0, peak / 4, peak / 2, peak).forEach { seconds ->
                Box(
                    modifier = Modifier
                        .padding(end = 3.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(calendarColor(seconds, peak))
                )
            }
            Spacer(Modifier.width(3.dp))
            Text(
                text  = "больше",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun calendarColor(seconds: Int?, peak: Int): Color = when {
    // Beyond today, or before the app was installed: nothing happened here and nothing
    // didn't happen either.
    seconds == null -> Color.Transparent
    seconds <= 0    -> MaterialTheme.colorScheme.surfaceVariant
    else -> {
        // Four steps, not a gradient: neighbouring days differ by minutes, and a continuous
        // scale makes that look like noise instead of a habit.
        val share = seconds.toFloat() / peak
        val alpha = when {
            share < 0.25f -> 0.3f
            share < 0.5f  -> 0.5f
            share < 0.75f -> 0.75f
            else          -> 1f
        }
        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
}

// ── Pace ──────────────────────────────────────────────────────────────────────

@Composable
private fun PaceCard(stats: ReadingStats) {
    val words = stats.charsPerMinute / 6   // ~6 characters to a Russian word, spaces included

    SectionCard(title = "Темп") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = stats.charsPerMinute.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text     = "знаков в минуту",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (stats.paceMeasured) {
                "Примерно $words ${plural(words, "слово", "слова", "слов")} в минуту. Замерено по вашему " +
                    "чтению за последние недели. Из этого берётся «осталось» на виджете и на полке."
            } else {
                "Пока это средняя скорость, а не ваша: замер начнётся, когда наберётся несколько " +
                    "часов чтения. До тех пор оставшееся время оценивается приблизительно."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── The goal picker ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalPickerSheet(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialMinutes = current.takeIf { it > ReadingGoal.OFF }
        ?.coerceAtMost(ReadingGoal.MAX_MINUTES) ?: ReadingGoal.DEFAULT_MINUTES
    val time = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )
    val selectedMinutes = time.hour * 60 + time.minute
    val valid = selectedMinutes in 1..ReadingGoal.MAX_MINUTES
    // The clock and the two actions form one choice. Opening at the half height
    // hides the actions on most phones and makes the sheet look unfinished.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text  = "Цель дня",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            TimePicker(state = time)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (valid) goalDurationLabel(selectedMinutes)
                       else "Выберите время от 1 минуты до 4 часов",
                style = MaterialTheme.typography.labelLarge,
                color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onPick(ReadingGoal.OFF); onDismiss() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Без цели")
                }
                Button(
                    onClick = { onPick(selectedMinutes); onDismiss() },
                    enabled = valid,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Сохранить") }
            }
        }
    }
}

private fun goalDurationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest мин в день"
        rest == 0 -> "$hours ч в день"
        else -> "$hours ч $rest мин в день"
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun VerticalHairline() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun Cell(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (highlight) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (unit != null) {
                Text(
                    text     = unit,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text      = label,
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Dates and words ───────────────────────────────────────────────────────────

private const val CALENDAR_WEEKS = 26

private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** The last [count] date keys, oldest first, ending today. */
private fun lastDayKeys(count: Int): List<String> {
    val cal = Calendar.getInstance()
    return (0 until count).map {
        val key = dayFormat.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        key
    }
}

/**
 * The calendar laid out the way it is drawn: a list of weeks, each of seven day keys running
 * Monday to Sunday, with null for the days after today.
 *
 * Built by walking forward from the Monday [CALENDAR_WEEKS] weeks back rather than by slicing a
 * flat list of dates, because the grid has to start on a Monday for the rows to mean anything —
 * a column that begins on a Thursday puts the same weekday on a different row every week.
 */
private fun calendarKeys(): List<List<String?>> {
    val today = Calendar.getInstance()
    val todayKey = dayFormat.format(today.time)

    // Calendar counts Sunday as 1; this turns it into "days since Monday".
    val sinceMonday = (today.get(Calendar.DAY_OF_WEEK) + 5) % 7

    val cursor = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -sinceMonday - (CALENDAR_WEEKS - 1) * 7)
    }

    var passedToday = false
    return (0 until CALENDAR_WEEKS).map {
        (0 until 7).map {
            val key = dayFormat.format(cursor.time)
            cursor.add(Calendar.DAY_OF_YEAR, 1)
            when {
                passedToday -> null
                key == todayKey -> { passedToday = true; key }
                else -> key
            }
        }
    }
}

private fun weekdayLetter(dateKey: String): String {
    val parsed = try {
        dayFormat.parse(dateKey)
    } catch (e: Exception) {
        null
    } ?: return ""
    val cal = Calendar.getInstance().apply { time = parsed }
    return when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> "пн"
        Calendar.TUESDAY   -> "вт"
        Calendar.WEDNESDAY -> "ср"
        Calendar.THURSDAY  -> "чт"
        Calendar.FRIDAY    -> "пт"
        Calendar.SATURDAY  -> "сб"
        else               -> "вс"
    }
}

private fun summaryLine(stats: ReadingStats): String {
    if (stats.totalDays <= 0) return "Здесь появится всё, что вы прочитаете"
    val hours = stats.totalSeconds / 3600f
    val time = if (hours >= 1f) "${hours.roundToInt()} ${plural(hours.roundToInt(), "час", "часа", "часов")}"
               else "${stats.totalSeconds / 60} ${plural(stats.totalSeconds / 60, "минута", "минуты", "минут")}"
    return "$time за ${stats.totalDays} ${plural(stats.totalDays, "день", "дня", "дней")}"
}

/** Splits a duration into the number and its unit so they can be styled separately. */
private fun splitDuration(seconds: Int): Pair<String, String?> {
    val minutes = seconds / 60
    return when {
        minutes <= 0 -> "—" to null
        minutes < 60 -> minutes.toString() to "мин"
        else         -> {
            val hours = minutes / 60
            val rest  = minutes % 60
            if (rest == 0) hours.toString() to "ч" else "$hours,${rest * 10 / 60}" to "ч"
        }
    }
}

/** Russian numeral agreement: 1 день, 2 дня, 5 дней. */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1       -> one
        2, 3, 4 -> few
        else    -> many
    }
}
