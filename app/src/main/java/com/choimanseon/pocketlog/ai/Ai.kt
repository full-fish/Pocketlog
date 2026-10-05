package com.choimanseon.pocketlog.ai

import android.util.Base64
import com.choimanseon.pocketlog.BuildConfig
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.CardParser
import com.choimanseon.pocketlog.auto.MsgKind
import com.choimanseon.pocketlog.auto.Parsed
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.tops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class AiError(val code: Int, message: String) : Exception(message)

/**
 * Talks to our Cloudflare Worker (server/), never to OpenAI directly: the API key must not ship in the APK.
 * Request/response shapes are defined in server/src/index.ts.
 */
object Ai {
    /**
     * 설정 → AI 모델. Measured on synthetic notifications, merchant names and order screenshots (TODO.md #3, #32):
     * all four got categories and tags right, so they differ in price and speed. A phone screenshot is about 4,100 tokens in.
     */
    val models = listOf(
        "gpt-6-luna" to "GPT-6 Luna · 가장 저렴 (기본)\n스샷 1장 약 1원 · 약 7초",
        "gpt-6.1-sol" to "GPT-6.1 Sol · 균형\n스샷 1장 약 19원 · 약 13초",
        "gpt-5.5" to "GPT-5.5 · 빠름\n스샷 1장 약 50원 · 약 6초",
        "gpt-6-astra" to "GPT-6 Astra · 가장 똑똑함\n스샷 1장 약 90원 · 약 9초",
    )

    val configured get() = BuildConfig.AI_PROXY_URL.isNotBlank()
    fun usable() = configured && app.prefs.aiConsent

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int, model: String = app.prefs.aiModel): JSONObject {
        val conn = URL(BuildConfig.AI_PROXY_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-app-token", BuildConfig.AI_APP_TOKEN)
            conn.outputStream.use { it.write(body.put("model", model).toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiError(code, runCatching { JSONObject(text).optString("error") }.getOrNull() ?: text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Spending categories with their own tags: screenshots and card statements are purchases. 공통 태그 are left out,
     * because who it was with or why can't be read from a merchant or an order.
     */
    private fun categoriesJson(categories: List<Category>) = JSONArray(categories.tops(TxType.EXPENSE).filter { !it.hidden }.map { c ->
        val tags = categories.filter { it.parentId == c.id && !it.hidden }
        JSONObject().put("id", c.id.toString()).put("name", c.name)
            .put("tags", JSONArray(tags.map { JSONObject().put("id", it.id.toString()).put("name", it.name) }))
    })

    /** The AI's category and tag ids, kept only if the category is a spending one and each tag belongs to it. */
    fun pickFrom(categoryId: String?, tagIds: JSONArray?, categories: List<Category>): Pick? {
        val c = categoryId?.toLongOrNull()?.takeIf { id -> categories.any { it.id == id && it.parentId == null && it.type == TxType.EXPENSE } } ?: return null
        val tags = List(tagIds?.length() ?: 0) { tagIds!!.optString(it).toLongOrNull() }.filterNotNull()
        return Pick(c, tags.filter { t -> categories.any { it.id == t && it.parentId == c } }.distinct())
    }

    /** Hide what the model doesn't need: masked names, long digit runs (account / card numbers), balances. */
    fun mask(text: String) = text
        .replace(Regex("""[가-힣]{1,2}\*{1,2}[가-힣]{0,2}님?"""), "고객")
        .replace(Regex("""잔액\s*:?\s*[\d,]+\s*원?"""), "잔액 ***")
        .replace(Regex("""\d[\d*-]{5,}\d"""), "****")

    suspend fun scan(images: List<ByteArray>, categories: List<Category>, rules: List<Rule>): JSONObject = withContext(Dispatchers.IO) {
        val catName = categories.associate { it.id.toString() to it.name }
        val body = JSONObject()
            .put("today", LocalDate.now().toString())
            .put("images", JSONArray(images.map { JSONObject().put("media_type", "image/jpeg").put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }))
            .put("categories", categoriesJson(categories))
            .put("hints", JSONArray(rules.take(30).mapNotNull { r ->
                Pick.decode(r.value)?.let { p -> catName[p.category.toString()]?.plus(p.tags.mapNotNull { catName[it.toString()] }.joinToString("") { " #$it" }) }
                    ?.let { "${r.pattern} → $it" }
            }))
        post("/scan", body, 180_000)
    }

    suspend fun parseMessage(title: String, body: String, postTime: Long): Parsed? = withContext(Dispatchers.IO) {
        if (!usable()) return@withContext null
        val r = runCatching { post("/parse", JSONObject().put("text", mask("$title\n$body")), 60_000) }.getOrNull() ?: return@withContext null
        if (!r.optBoolean("is_transaction")) return@withContext null
        val issuer = CardParser.issuerKey(r.optNullString("issuer"))
        Parsed(
            kind = when (r.optString("kind")) {
                "cancel" -> MsgKind.CANCEL
                "withdraw" -> MsgKind.WITHDRAW
                "deposit" -> MsgKind.DEPOSIT
                else -> MsgKind.SPEND
            },
            amount = r.optNullLong("amount_krw"),
            foreign = r.optNullString("foreign_amount"),
            merchant = r.optString("merchant"),
            installment = r.optInt("installment_months"),
            at = r.optNullLong("month")?.let { mo ->
                CardParser.timeFrom(mo.toInt(), r.optInt("day", 1), r.optInt("hour", 12), r.optInt("minute"), postTime)
            },
            issuer = issuer,
            payKind = if (issuer == null) null else when (r.optString("pay_kind")) {
                "check" -> PayKind.CHECK
                "bank" -> PayKind.BANK
                "pay_money" -> PayKind.PAY_MONEY
                else -> PayKind.CREDIT
            },
            last4 = r.optNullString("card_last4"),
            balance = null,
            isCharge = r.optBoolean("is_top_up"),
        )
    }

    /** AI 월간 리포트: the AI's text about [facts] (see MonthlyReport), with its own fixed [model]. */
    suspend fun report(facts: JSONObject, model: String): JSONObject = withContext(Dispatchers.IO) {
        post("/report", JSONObject().put("facts", facts), 180_000, model).getJSONObject("result")
    }

    /** merchant → category and tags. [categories] is the whole list (tags included). */
    suspend fun categorize(merchants: List<String>, categories: List<Category>): Map<String, Pick> = withContext(Dispatchers.IO) {
        val r = post("/categorize", JSONObject().put("merchants", JSONArray(merchants)).put("categories", categoriesJson(categories)), 60_000)
        val out = HashMap<String, Pick>()
        val arr = r.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            pickFrom(o.optNullString("category_id"), o.optJSONArray("tag_ids"), categories)?.let { out[o.optString("merchant")] = it }
        }
        out
    }
}

fun JSONObject.optNullString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONObject.optNullLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
