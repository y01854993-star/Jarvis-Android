package com.jarvis.tablet

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Bus {
    @Volatile var sink: ((String) -> Unit)? = null
    @Volatile var lastStatus: String? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun emit(obj: JSONObject) {
        val s = obj.toString()
        if (obj.optString("type") == "status") lastStatus = s
        sink?.invoke(s)
    }

    fun log(text: String) {
        val line = "[${fmt.format(Date())}] $text"
        android.util.Log.d("Jarvis", line)
        emit(JSONObject().put("type", "log").put("text", line))
    }
}
