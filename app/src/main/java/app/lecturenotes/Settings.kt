package app.lecturenotes

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** 文字起こしの方法 */
enum class Mode(val label: String, val help: String) {
    AUTO("自動", "Groq → Gemini → 端末内の順に、使えるものを選びます。端末内で起こした分は、ネットが戻ったら起こし直します。"),
    GROQ("Groq（Whisper）", "Groq だけを使います。使えない間は送信待ちになります。"),
    GEMINI("Gemini", "Gemini だけを使います。使えない間は送信待ちになります。"),
    LOCAL("端末内", "ネットを使いません（Android 13 以上）。精度はふつうです。"),
}

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = p.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            p.edit().putString(key, value).apply()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = p.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            p.edit().putInt(key, value).apply()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = p.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(key, value).apply()
        }
    }

    var mode: Mode
        get() = runCatching { Mode.valueOf(p.getString("mode", Mode.AUTO.name)!!) }.getOrDefault(Mode.AUTO)
        set(v) {
            p.edit().putString("mode", v.name).apply()
        }

    var groqKey by str("groqKey", "")
    var geminiKey by str("geminiKey", "")

    /** モデルを自動で選ぶ（一覧を取ってきて役割ごとに順番を付ける） */
    var autoModels by bool("autoModels", true)

    /** 自動で選ばないときの順番（1行に「groq:モデル名」か「gemini:モデル名」） */
    var manualStt by str("manualStt", Catalog.DEFAULT_MANUAL_STT)
    var manualFormat by str("manualFormat", Catalog.DEFAULT_MANUAL_FORMAT)

    /** 音声を区切る長さ（秒） */
    var chunkSeconds by int("chunkSeconds", 30)

    /** 何区間ずつまとめて整形するか */
    var formatBatch by int("formatBatch", 4)

    var formatEnabled by bool("formatEnabled", true)
}
