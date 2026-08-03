package com.liquidglass.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.liquidglass.app.MainActivity
import com.liquidglass.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 接收 AlarmManager 到点的广播，弹出日历日程提醒通知。
 * 点击通知打开 MainActivity（落地日历页由首页默认即可，此处仅拉起应用）。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(ReminderScheduler.EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)?.ifEmpty { null } ?: "日程提醒"
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME) ?: ""
        val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE) ?: ""

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 通知渠道（O+ 必须先建渠道）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "日程提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "日历日程到期提醒"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        // 点击通知打开应用
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, eventId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = buildTimeString(date, time)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calendar_reminder)
            .setContentTitle(title)
            .setContentText(if (timeText.isNotEmpty()) "时间：$timeText" else "到点了，别错过")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n时间：${timeText.ifEmpty { "未设置" }}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        nm.notify(eventId.hashCode(), notification)
    }

    private fun buildTimeString(date: String, time: String): String {
        val datePart = if (date.isNotEmpty()) {
            try {
                val d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
                "${d.monthValue}月${d.dayOfMonth}日"
            } catch (_: Exception) { date }
        } else ""
        return listOf(datePart, time).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        const val CHANNEL_ID = "calendar_reminder"
    }
}
