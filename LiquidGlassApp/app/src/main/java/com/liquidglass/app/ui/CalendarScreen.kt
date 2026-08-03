package com.liquidglass.app.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// ── Data Model ──

data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val time: String = "",
    val description: String = "",
    val colorTag: Int = 0,
    val isAllDay: Boolean = false,
    val date: String = "", // ISO format: yyyy-MM-dd
    val reminderTime: Long? = null // 提醒触发的绝对时间戳(ms)，null 表示不提醒
)

// ── Reminder Presets ──
// 提前量(分钟)；null 表示不提醒，0 表示事件时间到点提醒
data class ReminderPreset(val label: String, val offsetMin: Long?)

val ReminderPresets = listOf(
    ReminderPreset("不提醒", null),
    ReminderPreset("事件时间", 0L),
    ReminderPreset("5 分钟前", 5L),
    ReminderPreset("15 分钟前", 15L),
    ReminderPreset("30 分钟前", 30L),
    ReminderPreset("1 小时前", 60L),
    ReminderPreset("1 天前", 24L * 60),
    ReminderPreset("自定义…", -1L) // -1 触发自定义时间选择
)

/** 计算事件基准时间戳(ms)。全天事件取当天 09:00；无时间则回退当天 09:00 */
private fun computeEventBaseMillis(dateStr: String, timeStr: String, isAllDay: Boolean): Long? {
    return try {
        val date = LocalDate.parse(dateStr)
        val (h, m) = if (isAllDay || timeStr.isBlank()) 9 to 0
        else {
            val parts = timeStr.split(":")
            if (parts.size == 2) (parts[0].toIntOrNull() ?: 9) to (parts[1].toIntOrNull() ?: 0) else 9 to 0
        }
        LocalDateTime.of(date, java.time.LocalTime.of(h, m))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) { null }
}

/** 由预设推导提醒时间戳；offsetMin=-1 交由调用方自定义处理 */
private fun reminderFromPreset(preset: ReminderPreset, baseMillis: Long): Long? {
    val off = preset.offsetMin ?: return null // 不提醒
    return baseMillis - off * 60_000L
}

/** 格式化提醒时间用于UI展示 */
private fun formatReminderTime(ts: Long): String {
    return try {
        val ldt = java.time.Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val now = LocalDate.now()
        ldt.format(DateTimeFormatter.ofPattern(
            if (ldt.toLocalDate().year == now.year) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        ))
    } catch (_: Exception) { "" }
}

/**
 * 根据已有事件的 reminderTime 反推预设下标。
 * 匹配不上任意预设则返回 [ReminderPresets.lastIndex]（自定义）；
 * 无提醒返回 0（不提醒）。
 */
private fun matchPresetIndex(event: CalendarEvent?, defaultDate: String): Int {
    if (event == null || event.reminderTime == null) return 0
    val base = computeEventBaseMillis(
        event.date.ifEmpty { defaultDate }, event.time, event.isAllDay
    ) ?: return ReminderPresets.lastIndex
    for (i in 0 until ReminderPresets.lastIndex) { // 跳过末位"自定义"
        val preset = ReminderPresets[i]
        val expected = reminderFromPreset(preset, base)
        if (expected != null && expected == event.reminderTime) return i
    }
    return ReminderPresets.lastIndex
}

val EventColors = listOf(
    FluidCyan,
    FluidPurple,
    FluidPink,
    FluidBlue,
    FluidTeal,
    FluidOrange
)

// ── Persistence ──

private const val PREFS_NAME = "liquid_glass_calendar"
private const val EVENTS_KEY = "calendar_events"

private fun saveEvents(context: Context, events: List<CalendarEvent>) {
    val jsonArray = JSONArray()
    for (event in events) {
        val obj = JSONObject().apply {
            put("id", event.id)
            put("title", event.title)
            put("time", event.time)
            put("description", event.description)
            put("colorTag", event.colorTag)
            put("isAllDay", event.isAllDay)
            put("date", event.date)
            put("reminderTime", event.reminderTime ?: -1L)
        }
        jsonArray.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(EVENTS_KEY, jsonArray.toString())
        .apply()
}

private fun loadEvents(context: Context): List<CalendarEvent> {
    val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(EVENTS_KEY, null) ?: return emptyList()
    val events = mutableListOf<CalendarEvent>()
    try {
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            events.add(
                CalendarEvent(
                    id = obj.getString("id"),
                    title = obj.optString("title", ""),
                    time = obj.optString("time", ""),
                    description = obj.optString("description", ""),
                    colorTag = obj.optInt("colorTag", 0),
                    isAllDay = obj.optBoolean("isAllDay", false),
                    date = obj.optString("date", ""),
                    reminderTime = obj.optLong("reminderTime", -1L).takeIf { it > 0 }
                )
            )
        }
    } catch (_: Exception) { }
    return events
}

// ── Helper ──

private fun getDaysInMonthGrid(yearMonth: YearMonth): List<LocalDate?> {
    val firstDayOfMonth = yearMonth.atDay(1)
    val lastDayOfMonth = yearMonth.atEndOfMonth()
    val startDayOfWeek = firstDayOfMonth.dayOfWeek // MONDAY=1, SUNDAY=7
    // 日历从周一开始
    val leadingEmpty = (startDayOfWeek.value - 1) % 7
    val days = mutableListOf<LocalDate?>()
    for (i in 0 until leadingEmpty) {
        days.add(null)
    }
    for (day in 1..lastDayOfMonth.dayOfMonth) {
        days.add(yearMonth.atDay(day))
    }
    // 补齐到完整星期
    while (days.size % 7 != 0) {
        days.add(null)
    }
    return days
}

// ── Main Composable ──

@Composable
fun CalendarScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var events by remember { mutableStateOf(loadEvents(context)) }
    val today = remember { LocalDate.now() }
    var currentYearMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(today) }
    var showEventDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<CalendarEvent?>(null) }
    var viewMode by remember { mutableStateOf(0) } // 0=日程(选中日期), 1=概览(全部日程)

    // 月份滑动偏移
    var swipeOffset by remember { mutableStateOf(0f) }
    var swipeTarget by remember { mutableStateOf<YearMonth?>(null) }
    val swipeThreshold = 120f

    fun persist(newEvents: List<CalendarEvent>) {
        events = newEvents
        saveEvents(context, newEvents)
    }

    // 进入页面时重新调度所有提醒闹钟（App 重启/进程被杀后 AlarmManager 会丢失，需重建）
    LaunchedEffect(Unit) {
        ReminderScheduler.rescheduleAll(context, events)
    }

    // 按日期分组事件
    val eventsByDate = remember(events) {
        events.groupBy { it.date }
    }

    val selectedDateStr = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val selectedDateEvents = remember(events, selectedDateStr) {
        events.filter { it.date == selectedDateStr }
            .sortedWith(compareBy({ it.isAllDay }, { it.time }))
    }

    // 今日事件
    val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val todayEvents = remember(events, todayStr) {
        events.filter { it.date == todayStr }
            .sortedWith(compareBy({ it.isAllDay }, { it.time }))
    }

    // 本周事件
    val weekStart = today.with(DayOfWeek.MONDAY)
    val weekEnd = weekStart.plusDays(6)
    val weekEvents = remember(events, weekStart, weekEnd) {
        events.filter { e ->
            e.date.isNotEmpty() && e.date >= weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE) &&
                    e.date <= weekEnd.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }.sortedBy { it.date + it.time }
    }

    // 所有即将到来的事件（不含今天）
    val upcomingEvents = remember(events, todayStr) {
        events.filter { it.date > todayStr }
            .sortedBy { it.date + it.time }
    }

    // 日程提醒：未来 7 天内且已设提醒的事件（真实提醒由 AlarmManager 触发，此处仅汇总展示）
    val reminderEvents = remember(events, today) {
        val sevenDaysLater = today.plusDays(7)
        events.filter { e ->
            e.reminderTime != null && e.reminderTime > System.currentTimeMillis() &&
                e.date.isNotEmpty() && e.date in todayStr..sevenDaysLater.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }.sortedBy { it.reminderTime }
    }

    val daysInGrid = remember(currentYearMonth) {
        getDaysInMonthGrid(currentYearMonth)
    }

    // ── UI ──

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "日历日程",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                // 添加事件按钮
                FilledTonalButton(
                    onClick = {
                        editingEvent = null
                        showEventDialog = true
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AccentPrimary.copy(alpha = 0.15f),
                        contentColor = AccentPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Year/Month Navigation ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            swipeTarget = currentYearMonth.minusMonths(1)
                            swipeOffset = -1000f
                            kotlinx.coroutines.delay(200)
                            currentYearMonth = currentYearMonth.minusMonths(1)
                            swipeTarget = null
                            swipeOffset = 0f
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, "上月", tint = appTextSecondary(), modifier = Modifier.size(20.dp))
                }

                // Year dropdown
                var showYearPicker by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showYearPicker = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${currentYearMonth.year}年",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = appTextPrimary()
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${currentYearMonth.monthValue}月",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPrimary
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            null,
                            tint = appTextTertiary(),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showYearPicker,
                        onDismissRequest = { showYearPicker = false }
                    ) {
                        val currentYear = today.year
                        for (y in (currentYear - 5)..(currentYear + 5)) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${y}年",
                                        color = if (y == currentYearMonth.year) AccentPrimary else TextPrimary
                                    )
                                },
                                onClick = {
                                    currentYearMonth = YearMonth.of(y, currentYearMonth.monthValue)
                                    showYearPicker = false
                                },
                                leadingIcon = if (y == currentYearMonth.year) {
                                    { Icon(Icons.Default.Check, null, tint = AccentPrimary, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            swipeTarget = currentYearMonth.plusMonths(1)
                            swipeOffset = 1000f
                            kotlinx.coroutines.delay(200)
                            currentYearMonth = currentYearMonth.plusMonths(1)
                            swipeTarget = null
                            swipeOffset = 0f
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ChevronRight, "下月", tint = appTextSecondary(), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Week Day Headers ──
            Row(modifier = Modifier.fillMaxWidth()) {
                val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
                for (day in weekDays) {
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = if (day == "六" || day == "日") AccentWarning.copy(alpha = 0.6f) else TextTertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Calendar Grid (with swipe) ──
            Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                val displayMonth = swipeTarget ?: currentYearMonth
                val displayDays = if (swipeTarget != null) getDaysInMonthGrid(swipeTarget!!) else daysInGrid

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                        .pointerInput(currentYearMonth) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (swipeOffset > swipeThreshold) {
                                        scope.launch {
                                            swipeTarget = currentYearMonth.minusMonths(1)
                                            swipeOffset = 1000f
                                            kotlinx.coroutines.delay(50)
                                            currentYearMonth = currentYearMonth.minusMonths(1)
                                            selectedDate = currentYearMonth.atDay(1)
                                            swipeTarget = null
                                            swipeOffset = 0f
                                        }
                                    } else if (swipeOffset < -swipeThreshold) {
                                        scope.launch {
                                            swipeTarget = currentYearMonth.plusMonths(1)
                                            swipeOffset = -1000f
                                            kotlinx.coroutines.delay(50)
                                            currentYearMonth = currentYearMonth.plusMonths(1)
                                            selectedDate = currentYearMonth.atDay(1)
                                            swipeTarget = null
                                            swipeOffset = 0f
                                        }
                                    } else {
                                        scope.launch {
                                            swipeTarget = null
                                            swipeOffset = 0f
                                        }
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    swipeOffset = (swipeOffset + dragAmount).coerceIn(-1000f, 1000f)
                                }
                            )
                        }
                ) {
                    val rows = displayDays.chunked(7)
                    for (row in rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (day in row) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (day != null) {
                                        val isToday = day == today
                                        val isSelected = day == selectedDate
                                        val dateStr = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        val dayEvents = eventsByDate[dateStr] ?: emptyList()
                                        val isWeekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY

                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .then(
                                                    if (isSelected) {
                                                        Modifier.glassSurface(cornerRadius = 12.dp, glassAlpha = 0.25f)
                                                    } else if (isToday) {
                                                        Modifier
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .background(AccentPrimary.copy(alpha = 0.25f))
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .clickable { selectedDate = day },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = day.dayOfMonth.toString(),
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = when {
                                                        isSelected -> AccentPrimary
                                                        isToday -> Color.White
                                                        isWeekend -> AccentWarning.copy(alpha = 0.7f)
                                                        day.monthValue != currentYearMonth.monthValue -> TextTertiary
                                                        else -> TextPrimary
                                                    }
                                                )
                                                // 事件小圆点
                                                if (dayEvents.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        val uniqueColors = dayEvents.map { it.colorTag }.distinct().take(3)
                                                        for (cIdx in uniqueColors) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(4.dp)
                                                                    .clip(CircleShape)
                                                                    .background(EventColors[cIdx % EventColors.size])
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── View Mode Tabs ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                TabChip(
                    text = "日程",
                    selected = viewMode == 0,
                    onClick = { viewMode = 0 },
                    modifier = Modifier.weight(1f)
                )
                TabChip(
                    text = "概览",
                    selected = viewMode == 1,
                    onClick = { viewMode = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Content Area ──
            when (viewMode) {
                0 -> {
                    // 选中日期的日程
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 ${selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = appTextSecondary(),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        if (selectedDateEvents.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.08f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("暂无日程", fontSize = 13.sp, color = appTextTertiary())
                                }
                            }
                        } else {
                            items(selectedDateEvents, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = {
                                        editingEvent = event
                                        showEventDialog = true
                                    },
                                    onLongPress = { showDeleteConfirm = event }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(60.dp)) }
                    }
                }
                1 -> {
                    // 概览：今日 + 本周 + 即将到来
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 提醒
                        if (reminderEvents.isNotEmpty()) {
                            item {
                                Text(
                                    "⏰ 最近提醒",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentWarning,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(reminderEvents.take(3), key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = {
                                        editingEvent = event
                                        showEventDialog = true
                                    },
                                    onLongPress = { showDeleteConfirm = event }
                                )
                            }
                        }

                        // 今日
                        item {
                            Text(
                                "今日",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentPrimary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        if (todayEvents.isEmpty()) {
                            item {
                                Text("暂无今日日程", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(start = 4.dp))
                            }
                        } else {
                            items(todayEvents, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = {
                                        editingEvent = event
                                        showEventDialog = true
                                    },
                                    onLongPress = { showDeleteConfirm = event }
                                )
                            }
                        }

                        // 本周
                        item {
                            Text(
                                "本周",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = FluidTeal,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        if (weekEvents.isEmpty()) {
                            item {
                                Text("暂无本周日程", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(start = 4.dp))
                            }
                        } else {
                            items(weekEvents, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = {
                                        editingEvent = event
                                        showEventDialog = true
                                    },
                                    onLongPress = { showDeleteConfirm = event }
                                )
                            }
                        }

                        // 即将到来
                        item {
                            Text(
                                "即将到来",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = FluidPurple,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        if (upcomingEvents.isEmpty()) {
                            item {
                                Text("暂无未来日程", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(start = 4.dp))
                            }
                        } else {
                            items(upcomingEvents.take(20), key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = {
                                        editingEvent = event
                                        showEventDialog = true
                                    },
                                    onLongPress = { showDeleteConfirm = event }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(60.dp)) }
                    }
                }
            }
        }
    }

    // ── Event Add/Edit Dialog ──
    if (showEventDialog) {
        EventDialog(
            existingEvent = editingEvent,
            defaultDate = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            onSave = { title, time, desc, colorTag, isAllDay, date, reminderTime ->
                if (editingEvent != null) {
                    val idx = events.indexOfFirst { it.id == editingEvent!!.id }
                    if (idx >= 0) {
                        // 先取消旧闹钟，再用新数据调度
                        ReminderScheduler.cancel(context, editingEvent!!.id)
                        val updated = events[idx].copy(
                            title = title,
                            time = time,
                            description = desc,
                            colorTag = colorTag,
                            isAllDay = isAllDay,
                            date = date,
                            reminderTime = reminderTime
                        )
                        persist(events.toMutableList().also { it[idx] = updated })
                        if (updated.reminderTime != null) ReminderScheduler.schedule(context, updated)
                    }
                } else {
                    val newEvent = CalendarEvent(
                        title = title,
                        time = time,
                        description = desc,
                        colorTag = colorTag,
                        isAllDay = isAllDay,
                        date = date,
                        reminderTime = reminderTime
                    )
                    persist(events + newEvent)
                    if (newEvent.reminderTime != null) ReminderScheduler.schedule(context, newEvent)
                }
                showEventDialog = false
                editingEvent = null
            },
            onDismiss = {
                showEventDialog = false
                editingEvent = null
            }
        )
    }

    // ── Delete Confirmation ──
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = appBgColor2(),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            icon = {
                Icon(Icons.Default.Delete, null, tint = AccentDanger, modifier = Modifier.size(32.dp))
            },
            title = { Text("删除日程") },
            text = {
                Text("确定要删除「${showDeleteConfirm!!.title}」吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ReminderScheduler.cancel(context, showDeleteConfirm!!.id)
                        persist(events.filter { it.id != showDeleteConfirm!!.id })
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentDanger)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

// ── Tab Chip ──

@Composable
private fun TabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .then(
                if (selected) {
                    Modifier
                        .glassSurface(cornerRadius = 11.dp, glassAlpha = 0.18f)
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TextPrimary else TextTertiary
        )
    }
}

// ── Event Card ──

@Composable
private fun EventCard(
    event: CalendarEvent,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val accentColor = EventColors[event.colorTag % EventColors.size]
    val dateLabel = try {
        val localDate = LocalDate.parse(event.date)
        "${localDate.monthValue}月${localDate.dayOfMonth}日 ${localDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINESE)}"
    } catch (_: Exception) {
        event.date
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 颜色标记
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor.copy(alpha = 0.8f))
        )
        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.isAllDay) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(FluidTeal.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("全天", fontSize = 10.sp, color = FluidTeal)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (event.time.isNotEmpty()) {
                    Text(
                        event.time,
                        fontSize = 12.sp,
                        color = appTextTertiary()
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    event.title.ifEmpty { "无标题" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (event.reminderTime != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = "已设提醒",
                        tint = AccentWarning,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            if (event.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    event.description,
                    fontSize = 12.sp,
                    color = appTextSecondary(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 日期标签
        Text(
            dateLabel,
            fontSize = 10.sp,
            color = appTextTertiary()
        )
    }
}

// ── Event Dialog ──

@Composable
private fun EventDialog(
    existingEvent: CalendarEvent?,
    defaultDate: String,
    onSave: (title: String, time: String, description: String, colorTag: Int, isAllDay: Boolean, date: String, reminderTime: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(existingEvent?.id) { mutableStateOf(existingEvent?.title ?: "") }
    var time by remember(existingEvent?.id) { mutableStateOf(existingEvent?.time ?: "") }
    var description by remember(existingEvent?.id) { mutableStateOf(existingEvent?.description ?: "") }
    var colorTag by remember(existingEvent?.id) { mutableStateOf(existingEvent?.colorTag ?: 0) }
    var isAllDay by remember(existingEvent?.id) { mutableStateOf(existingEvent?.isAllDay ?: false) }
    var dateStr by remember(existingEvent?.id) { mutableStateOf(existingEvent?.date?.ifEmpty { defaultDate } ?: defaultDate) }
    val isEditing = existingEvent != null

    // ── 提醒状态 ──
    // reminderTime: 当前提醒触发时间戳(ms)；null = 不提醒
    // reminderPresetIdx: 选中的预设下标；末位(自定义)用 ReminderPresets.lastIndex 表示
    var reminderTime by remember(existingEvent?.id) { mutableStateOf(existingEvent?.reminderTime) }
    var reminderPresetIdx by remember(existingEvent?.id) {
        mutableStateOf(matchPresetIndex(existingEvent, defaultDate))
    }
    var showCustomReminderPicker by remember { mutableStateOf(false) }

    // 日期选择器
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        DatePickerDialog(
            currentDate = try { LocalDate.parse(dateStr) } catch (_: Exception) { LocalDate.now() },
            onDateSelected = { date ->
                dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
        return
    }

    // 自定义提醒时间选择器
    if (showCustomReminderPicker) {
        val initial = reminderTime?.let {
            try { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
            catch (_: Exception) { null }
        } ?: LocalDateTime.now()
        ReminderDateTimePicker(
            initial = initial,
            onConfirmed = { ldt ->
                reminderTime = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                reminderPresetIdx = ReminderPresets.lastIndex // 标记为自定义
                showCustomReminderPicker = false
            },
            onDismiss = { showCustomReminderPicker = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appBgColor2(),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(if (isEditing) "编辑日程" else "新建日程", fontWeight = FontWeight.Medium)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 50) title = it },
                    placeholder = { Text("标题", color = appTextTertiary()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = appTextPrimary()),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GlassBorder,
                        unfocusedBorderColor = GlassBorder.copy(alpha = 0.3f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = AccentPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Date
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = appTextPrimary()),
                    placeholder = { Text("日期 (yyyy-MM-dd)", color = appTextTertiary()) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GlassBorder,
                        unfocusedBorderColor = GlassBorder.copy(alpha = 0.3f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = AccentPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Icon(Icons.Default.CalendarToday, null, tint = appTextTertiary(), modifier = Modifier.size(18.dp))
                    }
                )

                // Time
                if (!isAllDay) {
                    OutlinedTextField(
                        value = time,
                        onValueChange = { if (it.length <= 10) time = it },
                        placeholder = { Text("时间 (如 14:30)", color = appTextTertiary()) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = appTextPrimary()),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GlassBorder,
                            unfocusedBorderColor = GlassBorder.copy(alpha = 0.3f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = AccentPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // All-day toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("全天事件", fontSize = 13.sp, color = appTextSecondary())
                    Switch(
                        checked = isAllDay,
                        onCheckedChange = { isAllDay = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FluidTeal,
                            checkedTrackColor = FluidTeal.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = GlassBorder.copy(alpha = 0.2f)
                        )
                    )
                }

                // ── 提醒选择 ──
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.NotificationsActive, null,
                            tint = AccentWarning, modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("提醒", fontSize = 13.sp, color = appTextSecondary())
                        Spacer(modifier = Modifier.weight(1f))
                        // 当前提醒时间预览
                        val rt = reminderTime
                        if (rt != null) {
                            Text(
                                formatReminderTime(rt),
                                fontSize = 11.sp, color = FluidCyan
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReminderPresets.forEachIndexed { idx, preset ->
                            val selected = idx == reminderPresetIdx
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .then(
                                        if (selected) Modifier.background(AccentPrimary.copy(alpha = 0.18f))
                                        else Modifier.background(GlassBorder.copy(alpha = 0.12f))
                                    )
                                    .clickable {
                                        if (preset.offsetMin == -1L) {
                                            // 自定义：打开时间选择器
                                            showCustomReminderPicker = true
                                        } else {
                                            val base = computeEventBaseMillis(dateStr, time, isAllDay)
                                            if (base == null) {
                                                reminderTime = null
                                                reminderPresetIdx = 0
                                            } else {
                                                reminderTime = reminderFromPreset(preset, base)
                                                reminderPresetIdx = idx
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    preset.label,
                                    fontSize = 11.sp,
                                    color = if (selected) AccentPrimary else appTextSecondary(),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 200) description = it },
                    placeholder = { Text("描述（可选）", color = appTextTertiary()) },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    maxLines = 3,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = appTextPrimary()),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GlassBorder,
                        unfocusedBorderColor = GlassBorder.copy(alpha = 0.3f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = AccentPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Color tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("颜色标签", fontSize = 13.sp, color = appTextSecondary())
                    for (i in EventColors.indices) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(EventColors[i].copy(alpha = 0.5f))
                                .then(
                                    if (colorTag == i) {
                                        Modifier.border(2.dp, EventColors[i], CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { colorTag = i }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title.trim(), time.trim(), description.trim(), colorTag, isAllDay, dateStr.trim(), reminderTime)
                    }
                },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = AccentPrimary,
                    disabledContentColor = TextTertiary
                )
            ) {
                Text(if (isEditing) "更新" else "创建")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("取消")
            }
        }
    )
}

// ── Date Picker Dialog ──

@Composable
private fun DatePickerDialog(
    currentDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var pickerYearMonth by remember { mutableStateOf(YearMonth.from(currentDate)) }
    var tempSelected by remember { mutableStateOf(currentDate) }
    val today = remember { LocalDate.now() }
    val days = remember(pickerYearMonth) { getDaysInMonthGrid(pickerYearMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appBgColor2(),
        titleContentColor = TextPrimary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { pickerYearMonth = pickerYearMonth.minusMonths(1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, "上月", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
                }
                Text(
                    "${pickerYearMonth.year}年${pickerYearMonth.monthValue}月",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextPrimary()
                )
                IconButton(
                    onClick = { pickerYearMonth = pickerYearMonth.plusMonths(1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ChevronRight, "下月", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
                    for (d in weekDays) {
                        Text(
                            text = d,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp,
                            color = appTextTertiary()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val rows = days.chunked(7)
                for (row in rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (day in row) {
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (day != null) {
                                    val isToday = day == today
                                    val isSel = day == tempSelected
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .then(
                                                if (isSel) Modifier.background(AccentPrimary.copy(alpha = 0.3f))
                                                else Modifier
                                            )
                                            .clickable { tempSelected = day },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = day.dayOfMonth.toString(),
                                            fontSize = 13.sp,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) AccentPrimary else if (isToday) FluidTeal else TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDateSelected(tempSelected) },
                colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("取消")
            }
        }
    )
}

// ── Reminder Date+Time Picker ──
// 用于"自定义"提醒时间：选日期 + 时:分，返回 LocalDateTime

@Composable
private fun ReminderDateTimePicker(
    initial: LocalDateTime,
    onConfirmed: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    var pickerYearMonth by remember { mutableStateOf(YearMonth.from(initial.toLocalDate())) }
    var tempDate by remember { mutableStateOf(initial.toLocalDate()) }
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }
    val today = remember { LocalDate.now() }
    val days = remember(pickerYearMonth) { getDaysInMonthGrid(pickerYearMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appBgColor2(),
        titleContentColor = TextPrimary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { pickerYearMonth = pickerYearMonth.minusMonths(1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, "上月", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
                }
                Text(
                    "${pickerYearMonth.year}年${pickerYearMonth.monthValue}月",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = appTextPrimary()
                )
                IconButton(
                    onClick = { pickerYearMonth = pickerYearMonth.plusMonths(1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ChevronRight, "下月", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                        Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = appTextTertiary())
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                days.chunked(7).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { day ->
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val isToday = day == today
                                    val isSel = day == tempDate
                                    Box(
                                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                            .then(if (isSel) Modifier.background(AccentPrimary.copy(alpha = 0.3f)) else Modifier)
                                            .clickable { tempDate = day },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            day.dayOfMonth.toString(),
                                            fontSize = 12.sp,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) AccentPrimary else if (isToday) FluidTeal else TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 时:分 选择（步进器）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TimeStepper(
                        value = hour, range = 0..23, label = "时",
                        onValueChange = { hour = it }
                    )
                    Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = appTextPrimary(),
                        modifier = Modifier.padding(horizontal = 8.dp))
                    TimeStepper(
                        value = minute, range = 0..59, label = "分",
                        onValueChange = { minute = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmed(LocalDateTime.of(tempDate, java.time.LocalTime.of(hour, minute))) },
                colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) { Text("取消") }
        }
    )
}

@Composable
private fun TimeStepper(
    value: Int,
    range: IntRange,
    label: String,
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onValueChange(if (value <= range.first) range.last else value - 1) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, "减少", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
            Text(String.format("%02d", value), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
            Text(label, fontSize = 10.sp, color = appTextTertiary())
        }
        IconButton(
            onClick = { onValueChange(if (value >= range.last) range.first else value + 1) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, "增加", tint = appTextSecondary(), modifier = Modifier.size(18.dp))
        }
    }
}