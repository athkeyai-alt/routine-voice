package com.athkeya.routine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class AlarmService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var ttsReady = false
    private var message = ""
    private var banglaMsg = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", 0) ?: 0
        val title = intent?.getStringExtra("title") ?: "Kaj"

        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "SNOOZE" -> {
                // 10 minute por abar bajbe
                val t = RoutineTask(Random.nextInt(10_000, Int.MAX_VALUE), title, System.currentTimeMillis() + 10 * 60_000L)
                Alarms.schedule(this, t)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        cleanup()
        val isSummary = id == Alarms.SUMMARY_ID
        message = if (isSummary) buildSummary() else {
            if (title.any { it in '\u0980'..'\u09FF' }) "এখন আপনার কাজের সময়: $title" else "Time for: $title"
        }
        banglaMsg = message.any { it in '\u0980'..'\u09FF' }

        startForegroundNow(title, id, isSummary)

        // 1) Exact time e prothome alarm sound
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { }

        // 2) Voice engine ready kora (parallel e)
        tts = TextToSpeech(this, this)

        // 3) Sound er por voice (summary te 3 sec, normal alarm e 6 sec)
        handler.postDelayed({
            try { player?.stop() } catch (_: Exception) {}
            speak()
        }, if (isSummary) 3000L else 6000L)

        // Safety: 60 sec por service bondho
        handler.postDelayed({ stopSelf() }, 60000)
        return START_NOT_STICKY
    }

    private fun buildSummary(): String {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = Store.load(this)
            .filter { it.timeMillis in start until (start + Alarms.DAY) }
            .sortedBy { it.timeMillis }
        val bn = today.any { t -> t.title.any { it in '\u0980'..'\u09FF' } }
        if (today.isEmpty()) return if (bn) "সুপ্রভাত। আজ আপনার কোনো কাজ নেই।" else "Good morning. You have no tasks today."
        val f = SimpleDateFormat("h:mm a", Locale.US)
        val list = today.joinToString(". ") { "${f.format(Date(it.timeMillis))}, ${it.title}" }
        return if (bn) "সুপ্রভাত। আজ আপনার ${today.size} টি কাজ আছে। $list"
        else "Good morning. Today you have ${today.size} tasks. $list"
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val loc = if (banglaMsg) Locale("bn", "BD") else Locale.US
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.US)
        }
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { stopSelf() }
            override fun onDone(id: String?) { if (id == "last") stopSelf() }
        })
        ttsReady = true
    }

    private fun speak() {
        val t = tts
        if (!ttsReady || t == null) { stopSelf(); return }
        t.speak(message, TextToSpeech.QUEUE_ADD, null, "first")
        t.speak(message, TextToSpeech.QUEUE_ADD, null, "last") // 2 bar bolbe
    }

    private fun startForegroundNow(title: String, id: Int, isSummary: Boolean) {
        val chId = "routine_alarm"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(chId, "Routine alarms", NotificationManager.IMPORTANCE_HIGH)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AlarmService::class.java).setAction("STOP"), flags)
        val snooze = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmService::class.java).setAction("SNOOZE").putExtra("title", title).putExtra("id", id),
            flags
        )
        val b = NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isSummary) "Aj er schedule" else "Ekhon: $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
        if (!isSummary) b.addAction(0, "Snooze 10 min", snooze)
        val n: Notification = b.build()
        ServiceCompat.startForeground(this, 42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
