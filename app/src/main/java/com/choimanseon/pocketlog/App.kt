package com.choimanseon.pocketlog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.choimanseon.pocketlog.data.PocketDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Manual DI: everything long-lived hangs off the Application. */
class App : Application() {
    lateinit var db: PocketDb
    lateinit var prefs: Prefs
    val dao get() = db.dao()

    /** Work that must finish even if the screen that started it goes away (saves, AI calls). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = PocketDb.open(this)
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(Notify.CH_SAVED, "자동 기록 알림", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(Notify.CH_BUDGET, "예산 알림", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        scope.launch { dao.seedIfEmpty() }
    }

    companion object {
        lateinit var instance: App
    }
}

val app get() = App.instance

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("pocketlog", Context.MODE_PRIVATE)

    /** Bumps on every change so Compose can observe settings with one collectAsState. */
    val version = MutableStateFlow(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version.value++ }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    var monthStartDay by int("monthStartDay", 1)
    var weekStart by int("weekStart", 1) // java.time.DayOfWeek value, 1 = Monday
    var theme by string("theme", "system") // system | light | dark
    var autoInput by bool("autoInput", true)
    var notifyOnSave by bool("notifyOnSave", true)
    var autoCategory by bool("autoCategory", true)
    var myName by string("myName", "") // 내 이름: transfers to/from this name are my own accounts, not recorded
    var aiConsent by bool("aiConsent", false)
    var pinHash by string("pinHash", "")
    var pinSalt by string("pinSalt", "")
    var biometric by bool("biometric", false)
    var budgetAlert by string("budgetAlert", "") // "<period start>:<percent>" last alert sent

    private fun int(key: String, def: Int) = pref({ sp.getInt(key, def) }, { sp.edit().putInt(key, it).apply() })
    private fun bool(key: String, def: Boolean) = pref({ sp.getBoolean(key, def) }, { sp.edit().putBoolean(key, it).apply() })
    private fun string(key: String, def: String) = pref({ sp.getString(key, def) ?: def }, { sp.edit().putString(key, it).apply() })

    private fun <T> pref(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = get()
        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = set(value)
    }
}
