package app.lecturenotes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** 録音を自動で止めたときなどの知らせ（画面の帯と通知） */
object Notice {
    private const val CHANNEL = "notice"

    /** 画面に出す知らせ（閉じるまで残す） */
    val message = MutableStateFlow<String?>(null)

    fun show(context: Context, text: String) {
        message.value = text
        Processor.status.value = text
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "お知らせ", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                2,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("録音を止めました")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
