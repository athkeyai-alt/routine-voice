package com.athkeya.routine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Smart parsing: Claude API diye. Background thread e call korbe (MainActivity te Dispatchers.IO).
// Anthropic API key lagbe (console.anthropic.com), eta Claude.ai subscription theke alada, pay-per-use.
object Smart {
    fun parse(text: String, apiKey: String): List<ParsedItem> {
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val prompt = "Current date and time: $now (Bangladesh). The user speaks Bangla, Banglish or English " +
            "and is telling their routine tasks. Return ONLY a JSON array, no other text. " +
            "Each item: {\"title\": short task name in the user's own language, " +
            "\"time\": \"yyyy-MM-dd HH:mm\" in 24-hour format, " +
            "\"daily\": true only if the user says every day / roj / protidin, else false}. " +
            "\"kal\" means tomorrow, \"aj\" means today, \"porshu\" means day after tomorrow. " +
            "If am/pm is unclear, choose the most sensible time for that activity. " +
            "User text: $text"

        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 1000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw Exception("API error $code")

        val txt = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
        val arr = JSONArray(txt.replace("```json", "").replace("```", "").trim())
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ParsedItem(o.getString("title"), fmt.parse(o.getString("time"))!!.time, o.optBoolean("daily", false))
        }
    }
}
