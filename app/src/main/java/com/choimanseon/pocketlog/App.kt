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
    private var widgetJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = PocketDb.open(this)
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(Notify.CH_SAVED, "자동 기록 알림", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(Notify.CH_BUDGET, "예산 알림", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(Notify.CH_SCAN, "스크린샷 분석 알림", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(Notify.CH_REPORT, "월간 리포트", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        val startedAt = System.currentTimeMillis()
        scope.launch {
            dao.seedIfEmpty()
            dao.failStaleScans(startedAt, "앱이 꺼져서 분석이 멈췄어요")
            com.choimanseon.pocketlog.ai.Scan.tidy()
            Daily.run()
        }
        Daily.schedule(this)
        // the widget follows every change to transactions and budgets, a moment after a burst of writes
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("Tx", "Budget")) {
            override fun onInvalidated(tables: Set<String>) {
                widgetJob?.cancel()
                widgetJob = scope.launch { kotlinx.coroutines.delay(1_500); com.choimanseon.pocketlog.ui.PocketWidget.refresh(this@App) }
            }
        })
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
    var aiModel by string("aiModel", "gpt-6-luna") // one of Ai.models; the Worker only accepts those
    var pinHash by string("pinHash", "")
    var pinSalt by string("pinSalt", "")
    var biometric by bool("biometric", false)
    var monthlyReport by bool("monthlyReport", true) // AI 월간 리포트, needs aiConsent too
    var fxRates by string("fxRates", "") // "2026-10-05|{rates per USD}", see auto/Fx.kt
    var budgetAlert by string("budgetAlert", "") // last alert per budget period, see AutoInput.checkBudget
    var budgetLinked by bool("budgetLinked", false) // 주 · 월 · 연 통일: one amount sets the other two (TODO #37)
    var budgetHidden by string("budgetHidden", "") // periods left off 홈 · 자산 and alerts, e.g. "WEEK,YEAR"
    var favoriteSort by string("favoriteSort", "custom") // custom | name | nameDesc | newest | oldest (TODO #47)
    var driveAuto by bool("driveAuto", false) // 매일 자동 백업 to Google Drive: off until signed in and its password is set
    var driveSecret by string("driveSecret", "") // that password, sealed by Vault
    var driveLast by string("driveLast", "") // last Drive backup, ISO local date-time
    var onboarded by bool("onboarded", false)
    var devMenu by bool("devMenu", false) // 개발자 메뉴 (더미 데이터): tap 버전 ten times to show or hide it

    fun shows(b: com.choimanseon.pocketlog.data.Budget) = b.period.name !in budgetHidden.split(',')

    private fun int(key: String, def: Int) = pref({ sp.getInt(key, def) }, { sp.edit().putInt(key, it).apply() })
    private fun bool(key: String, def: Boolean) = pref({ sp.getBoolean(key, def) }, { sp.edit().putBoolean(key, it).apply() })
    private fun string(key: String, def: String) = pref({ sp.getString(key, def) ?: def }, { sp.edit().putString(key, it).apply() })

    private fun <T> pref(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = get()
        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = set(value)
    }
}
