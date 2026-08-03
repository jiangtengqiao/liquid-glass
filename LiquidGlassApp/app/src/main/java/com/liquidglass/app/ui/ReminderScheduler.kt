package com.liquidglass.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 日历提醒调度器：用 [AlarmManager] 在 [CalendarEvent.reminderTime] 到点时触发 [ReminderReceiver]，
 * 由 Receiver 弹出系统通知。App 进程被杀或重启后 AlarmManager 注册会丢失，故进入日历页时
 * 调用 [rescheduleAll] 重建。
 */
object ReminderScheduler {

    /**
     * 调度单个事件的提醒闹钟。
     * - 无提醒(reminderTime == null)直接返回
     * - 触发时间已过去则跳过(避免立即触发)
     * - Android 12+ 优先用 setExactAndAllowWhileIdle(Doze 下也能准时)，被撤销权限时回退到 inexact
     */
    fun schedule(context: Context, event: CalendarEvent) {
        val triggerAt = event.reminderTime ?: return
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, event, flags = PendingIntent.FLAG_UPDATE_CURRENT)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            // Android 12+ 用户未授予 SCHEDULE_EXACT_ALARM：回退到非精确闹钟，仍能提醒(误差可能几分钟)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 取消某事件的提醒闹钟（删除/编辑/改为不提醒时调用） */
    fun cancel(context: Context, eventId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, eventId, flags = PendingIntent.FLAG_NO_CREATE)
        if (pi != null) alarmManager.cancel(pi)
    }

    /**
     * 重建所有事件的提醒闹钟。App 重启/进程被杀后调用以恢复提醒。
     * 已过期的提醒会被取消并丢弃(不再触发)。
     */
    fun rescheduleAll(context: Context, events: List<CalendarEvent>) {
        events.forEach { e ->
            if (e.reminderTime != null) {
                if (e.reminderTime > System.currentTimeMillis()) {
                    schedule(context, e)
                } else {
                    cancel(context, e.id) // 过期提醒清理掉
                }
            }
        }
    }

    private fun buildPendingIntent(
        context: Context,
        event: CalendarEvent,
        flags: Int
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_ID, event.id)
            putExtra(EXTRA_TITLE, event.title)
            putExtra(EXTRA_TIME, event.time)
            putExtra(EXTRA_DATE, event.date)
        }
        return PendingIntent.getBroadcast(
            context, event.id.hashCode(), intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildPendingIntent(
        context: Context,
        eventId: String,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, eventId.hashCode(), intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val EXTRA_EVENT_ID = "extra_event_id"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_TIME = "extra_time"
    const val EXTRA_DATE = "extra_date"
}
