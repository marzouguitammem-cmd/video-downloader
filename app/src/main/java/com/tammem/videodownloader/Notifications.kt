package com.tammem.videodownloader

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

object Notifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_RESULT = "results"
    const val ID_PROGRESS = 1
    const val ID_RESULT = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun progress(context: Context, s: DlState.Running): Notification {
        val determinate = s.stage == Stage.DOWNLOADING && s.percent >= 0f
        val text = if (determinate) {
            stageText(context, s) + "  " + String.format(Locale.US, "%.0f%%", s.percent)
        } else {
            stageText(context, s)
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(s.title ?: context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setProgress(100, if (determinate) s.percent.toInt() else 0, !determinate)
            .build()
    }

    fun result(context: Context, s: DlState): Notification? {
        val (title, text) = when (s) {
            is DlState.Done -> context.getString(R.string.done_title) to s.fileName
            is DlState.Failed -> context.getString(R.string.error_title) to s.message
            else -> return null
        }
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications turned off: the download still works.
        }
    }

    fun stageText(context: Context, s: DlState.Running): String = when (s.stage) {
        Stage.WAITING_ENGINE -> context.getString(R.string.stage_engine)
        Stage.FETCHING_INFO -> context.getString(R.string.stage_info)
        Stage.DOWNLOADING -> if (s.part >= 2) {
            context.getString(R.string.stage_downloading_part, s.part)
        } else {
            context.getString(R.string.stage_downloading)
        }
        Stage.PROCESSING -> context.getString(R.string.stage_processing)
        Stage.SAVING -> context.getString(R.string.stage_saving)
    }
}
