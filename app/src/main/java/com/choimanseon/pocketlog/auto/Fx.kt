package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * 외화만 온 결제 (TODO #35): recorded right away in won at the day's rate. Rates come from open.er-api.com (free, no key,
 * updated daily; "Rates By Exchange Rate API") and are kept for the day. The card company's own rate and fee differ a little.
 */
object Fx {
    private val amountRe = Regex("""([A-Z]{3})\s*([\d,]+(?:\.\d+)?)""")

    /** "USD 12.99" with rates per 1 USD (KRW 1344.6, JPY 157.7, …) → won and the won price of one unit. */
    fun convert(foreign: String, usdRates: JSONObject): Pair<Long, Double>? {
        val (code, number) = amountRe.find(foreign)?.destructured ?: return null
        val amount = number.replace(",", "").toDoubleOrNull() ?: return null
        val krw = usdRates.optDouble("KRW").takeIf { it > 0 } ?: return null
        val unit = usdRates.optDouble(code).takeIf { it > 0 } ?: return null
        val rate = krw / unit
        return (amount * rate).roundToLong() to rate
    }

    /** Won for one [code], for 직접 입력 in a foreign currency (TODO #40). Null when no rate was ever fetched. */
    suspend fun wonPer(code: String): Double? = rates()?.let { convert("$code 1", it) }?.second

    /** Null when there is no rate at all (never online yet): the payment then waits in 확인 필요 as before. */
    suspend fun toWon(foreign: String?): Pair<Long, Double>? = foreign?.let { f -> rates()?.let { convert(f, it) } }

    private suspend fun rates(): JSONObject? = withContext(Dispatchers.IO) {
        val today = LocalDate.now().toString()
        val cached = app.prefs.fxRates
        if (cached.startsWith("$today|")) return@withContext JSONObject(cached.substringAfter('|'))
        val fresh = runCatching {
            val conn = URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("rates") } finally { conn.disconnect() }
        }.getOrNull()
        if (fresh != null) app.prefs.fxRates = "$today|$fresh"
        fresh ?: cached.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let(::JSONObject) // offline: the last rates known
    }
}
