package com.athkeya.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ---------- Data + Storage ----------

data class RoutineTask(val id: Int, val title: String, val timeMillis: Long, val daily: Boolean = false)
data class ParsedItem(val title: String, val time: Long, val daily: Boolean)

object Store {
    private const val PREF = "routine"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREF, 0)

    fun load(ctx: Context): MutableList<RoutineTask> {
        val s = prefs(ctx).getString("tasks", "[]") ?: "[]"
        val arr = JSONArray(s)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RoutineTask(o.getInt("id"), o.getString("title"), o.getLong("t"), o.optBoolean("r", false))
        }
    }

    fun save(ctx: Context, list: List<RoutineTask>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("t", it.timeMillis).put("r", it.daily))
        }
        prefs(ctx).edit().putString("tasks", arr.toString()).apply()
    }
}

// ---------- Rule-based parser (free, offline) ----------
// "kal sokal 8 tay uthbo, 19 tay meeting, roj 10 tay gym"
// Time ambiguity: sokal/raat/am/pm na bolle 1-6 ke PM dhora hoy. Safe: 24-hour bolo.

object Parser {
    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private val pmWords = listOf("bikel", "bikal", "dupur", "raat", "rat", "shondha", "sondha", "বিকাল", "বিকেল", "দুপুর", "রাত", "সন্ধ্যা")
    private val amWords = listOf("sokal", "shokal", "bhor", "সকাল", "ভোর")
    private val timeRe = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?""")
    private val splitRe = Regex("""[,।\n;]|\b(?:then|tarpor|tarpore)\b|তারপর""")
    private val dailyRe = Regex("""\b(?:roj|protidin|daily|everyday|every\s*day)\b|রোজ|প্রতিদিন""")
    private val noiseRe = Regex("""\b(kal|kalke|aj|ajke|porshu|tay|ta|te|er|shomoy|somoy|amar|ache|hobe)\b|কাল|আজ|আজকে|পরশু|টায়|টা|এর""")

    private fun normalize(s: String): String = buildString {
        s.lowercase().forEach { c ->
            val i = BN_DIGITS.indexOf(c)
            append(if (i >= 0) ('0' + i) else c)
        }
    }

    fun parse(raw: String): List<ParsedItem> {
        val text = normalize(raw)
        val dayOffset = when {
            Regex("porshu|পরশু").containsMatchIn(text) -> 2
            Regex("""\baj\b|\bajke\b|আজ""").containsMatchIn(text) -> 0
            else -> 1
        }
        val out = mutableListOf<ParsedItem>()
        text.split(splitRe).forEach { seg ->
            val m = timeRe.find(seg) ?: return@forEach
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3]
            if (h > 24 || min > 59) return@forEach

            val isPm = ap == "pm" || pmWords.any { seg.contains(it) }
            val isAm = ap == "am" || amWords.any { seg.contains(it) }
            if (h <= 12) {
                if (isPm && h < 12) h += 12
                else if (isAm && h == 12) h = 0
                else if (!isPm && !isAm && h in 1..6) h += 12
            }
            if (h == 24) h = 0

            val daily = dailyRe.containsMatchIn(seg)
            var title = seg.replace(m.value, " ")
            (pmWords + amWords).forEach { title = title.replace(it, " ") }
            title = dailyRe.replace(title, " ")
            title = noiseRe.replace(title, " ").replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) title = "Kaj"

            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, if (daily) 0 else dayOffset)
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            var time = cal.timeInMillis
            if (daily && time <= System.currentTimeMillis()) time += Alarms.DAY
            out.add(ParsedItem(title, time, daily))
        }
        return out
    }
}

// ---------- Alarm scheduling ----------

object Alarms {
    const val SUMMARY_ID = 9001
    const val DAY = 86_400_000L

    fun nextOccurrence(timeMillis: Long): Long {
        var t = timeMillis
        val now = System.currentTimeMillis()
        while (t <= now) t += DAY
        return t
    }

    private fun pending(ctx: Context, t: RoutineTask): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", t.id)
            .putExtra("title", t.title)
        return PendingIntent.getBroadcast(
            ctx, t.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, t: RoutineTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // setAlarmClock: Doze mode e-o exact time e bajhe
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t.timeMillis, show), pending(ctx, t))
    }

    fun cancel(ctx: Context, t: RoutineTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, t))
    }

    // Morning summary: roj ekta fixed time e "aj tomar N ta kaj ache" bolbe
    fun scheduleSummary(ctx: Context) {
        val p = Store.prefs(ctx)
        if (!p.getBoolean("sum_on", false)) return
        val m = p.getInt("sum_min", 450)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        schedule(ctx, RoutineTask(SUMMARY_ID, "__summary__", nextOccurrence(cal.timeInMillis)))
    }

    fun cancelSummary(ctx: Context) = cancel(ctx, RoutineTask(SUMMARY_ID, "__summary__", 0))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val title = intent.getStringExtra("title") ?: "Kaj"

        if (id == Alarms.SUMMARY_ID) {
            Alarms.scheduleSummary(ctx) // porer diner jonno abar
        } else {
            val list = Store.load(ctx)
            val t = list.find { it.id == id }
            if (t != null && t.daily) { // roj repeat: porer diner alarm
                val next = t.copy(timeMillis = Alarms.nextOccurrence(t.timeMillis))
                Store.save(ctx, list.map { if (it.id == id) next else it })
                Alarms.schedule(ctx, next)
            }
        }

        val svc = Intent(ctx, AlarmService::class.java).putExtra("id", id).putExtra("title", title)
        ContextCompat.startForegroundService(ctx, svc)
    }
}

// Phone restart hole sob alarm abar boshabe
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val now = System.currentTimeMillis()
        val list = Store.load(ctx).map {
            if (it.daily && it.timeMillis <= now) it.copy(timeMillis = Alarms.nextOccurrence(it.timeMillis)) else it
        }
        Store.save(ctx, list)
        list.filter { it.timeMillis > now }.forEach { Alarms.schedule(ctx, it) }
        Alarms.scheduleSummary(ctx)
    }
}
