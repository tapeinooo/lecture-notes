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
    var manualFormat by str("manualFast", Catalog.DEFAULT_MANUAL_FAST)
    var manualPolish by str("manualPolish", Catalog.DEFAULT_MANUAL_POLISH)

    /** 音声を区切る目安の長さ（秒）。この長さを過ぎたら、話の切れ目で区切る */
    var chunkSeconds by int("chunkSec2", 15)

    /**
     * すぐの整形のタイミング。
     * 話に長めの間（2秒）があり、たまった音声が fastMinSec 以上なら整形する。間がなくても fastMaxSec たったら整形する。
     */
    var fastMinSec by int("fastMinSec", 15)
    var fastMaxSec by int("fastMaxSec", 45)

    var formatEnabled by bool("formatEnabled", true)

    /** 頭の良いモデルで仕上げ直す */
    var polishEnabled by bool("polishEnabled", true)

    /** 仕上げを何区間ずつまとめるか */
    var polishBatch by int("polishBatch", 8)

    /** 端末内の音声認識で、その場で文字を出す（クラウドの結果が来たら置き換える） */
    var livePreview by bool("livePreview", true)

    /** Google ドライブへの控え（自分の Google Apps Script に送る） */
    var backupEnabled by bool("backupEnabled", false)
    var backupUrl by str("backupUrl", "")
    var backupToken by str("backupToken", "")
    var backupAudio by bool("backupAudio", true)
    var backupAudioWifiOnly by bool("backupAudioWifiOnly", true)

    /** 一覧で開いている日（null = まだ一度も変えていない） */
    var openDays: Set<String>?
        get() = p.getStringSet("openDays", null)?.toSet()
        set(v) {
            p.edit().putStringSet("openDays", v ?: emptySet()).apply()
        }

    /** 省電力モード：真っ黒の画面・動きを減らす */
    var powerSave: Boolean
        get() = p.getBoolean("powerSave", true)
        set(v) {
            p.edit().putBoolean("powerSave", v).apply()
            Power.save.value = v
        }

    /** アプリの中だけ画面を暗くする */
    var dimScreen: Boolean
        get() = p.getBoolean("dimScreen", false)
        set(v) {
            p.edit().putBoolean("dimScreen", v).apply()
            Power.dim.value = v
        }

    /** 無音がこの分数続いたら自動で録音を止める（0 = 止めない） */
    var autoStopMinutes by int("autoStopMinutes", 20)

    /** マーカーのいまの色（g 緑・y 黄・r 赤） */
    var markerColor by str("markerColor", "g")

    /** 声の変わり目を見つけて、段落を区切る参考にする */
    var voiceChangeHints by bool("voiceChangeHints", true)
}

/** 省電力の状態（画面がすぐ反映できるように） */
object Power {
    val save = kotlinx.coroutines.flow.MutableStateFlow(true)
    val dim = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun init(context: Context) {
        val st = Settings(context)
        save.value = st.powerSave
        dim.value = st.dimScreen
    }
}
