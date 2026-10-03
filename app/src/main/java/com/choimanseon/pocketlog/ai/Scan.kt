package com.choimanseon.pocketlog.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.Categorizer
import com.choimanseon.pocketlog.auto.similar
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ScanItem(val name: String, val quantity: Int, val amount: Long, val categoryId: Long?)

data class ScanOrder(
    val date: LocalDate?,
    val time: LocalTime?,
    val merchant: String,
    val total: Long,
    val currency: String,
    val paymentHint: String?,
    val status: String, // paid | canceled | refunded | partially_refunded
    val items: List<ScanItem>,
    val shippingFee: Long,
    val discount: Long,
    val confidence: Double,
) {
    /** Items + shipping − discount should equal the total; if not, the review screen warns. */
    val mismatch get() = items.isNotEmpty() && items.sumOf { it.amount } + shippingFee - discount != total
    val active get() = status == "paid" || status == "partially_refunded"
}

data class ScanResult(val sourceApp: String, val orders: List<ScanOrder>, val warnings: List<String>)

/** What the user decided for one order on the review screen. */
data class OrderChoice(
    val include: Boolean,
    val payId: Long?,
    val categories: List<Long?>, // per item
    val total: Long,
    val mergeInto: Tx?,          // existing transaction (e.g. the card SMS) to attach the items to
)

val sourceNames = mapOf(
    "coupang" to "쿠팡", "naver" to "네이버", "kurly" to "컬리", "baemin" to "배달의민족", "yogiyo" to "요기요",
    "musinsa" to "무신사", "11st" to "11번가", "gmarket" to "G마켓", "toss" to "토스", "kakaopay" to "카카오페이",
    "naverpay" to "네이버페이", "bank" to "은행", "card" to "카드", "receipt" to "영수증",
)

object Scan {
    private const val MAX_EDGE = 2576 // longest edge the model reads at full resolution
    private const val MAX_TILES = 8
    private val zone get() = ZoneId.systemDefault()

    private fun dir() = File(app.filesDir, "scans").apply { mkdirs() }
    fun imageFiles(job: ScanJob) = (0 until job.imageCount).map { File(dir(), "${job.id}_$it.jpg") }

    /** Copies the images right away (shared URIs can expire), then calls the AI in the app scope. */
    suspend fun start(context: Context, uris: List<Uri>): Long = withContext(Dispatchers.IO) {
        val tiles = uris.flatMap { runCatching { tiles(context, it) }.getOrDefault(emptyList()) }.take(MAX_TILES)
        require(tiles.isNotEmpty()) { "이미지를 읽을 수 없어요" }
        val hash = MessageDigest.getInstance("SHA-256").run { tiles.forEach { update(it) }; digest() }.joinToString("") { "%02x".format(it) }
        app.dao.scanByHash(hash)?.let { return@withContext it.id }
        val id = app.dao.insert(ScanJob(imageHash = hash, imageCount = tiles.size, status = ScanStatus.RUNNING))
        tiles.forEachIndexed { i, bytes -> File(dir(), "${id}_$i.jpg").writeBytes(bytes) }
        app.scope.launch { run(id) }
        id
    }

    suspend fun run(id: Long) {
        val dao = app.dao
        val job = dao.scanJobOnce(id) ?: return
        dao.update(job.copy(status = ScanStatus.RUNNING, error = null))
        val result = runCatching {
            if (!Ai.configured) throw AiError(0, "AI 서버가 아직 설정되지 않았어요 (server/README.md)")
            Ai.scan(imageFiles(job).map { it.readBytes() }, dao.categoriesOnce(), dao.rulesOnce(RuleKind.CATEGORY))
        }
        dao.update(
            result.fold(
                onSuccess = { job.copy(status = ScanStatus.DONE, resultJson = (it.optJSONObject("result") ?: it).toString()) },
                onFailure = { job.copy(status = ScanStatus.FAILED, error = friendly(it)) },
            )
        )
    }

    private fun friendly(e: Throwable): String = when {
        e is AiError && e.code == 0 -> e.message.orEmpty()
        e is AiError && e.code == 429 -> "요청이 많아요. 잠시 후 다시 시도해 주세요"
        e is AiError && e.code == 422 -> "이 이미지는 분석할 수 없어요"
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "인터넷 연결을 확인해 주세요"
        e is java.net.SocketTimeoutException -> "분석이 오래 걸려요. 다시 시도해 주세요"
        else -> "분석에 실패했어요 (${e.message?.take(80)})"
    }

    /** Long screenshots become 1:2 tiles with 10% overlap; shrinking the whole image would make the text unreadable. */
    private fun tiles(context: Context, uri: Uri): List<ByteArray> {
        val decoder = context.contentResolver.openInputStream(uri)?.use {
            if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it, false)
        } ?: return emptyList()
        try {
            val w = decoder.width
            val h = decoder.height
            val tileH = if (h <= w * 2.2) h else w * 2
            val out = mutableListOf<ByteArray>()
            var top = 0
            while (out.size < MAX_TILES) {
                val bottom = minOf(h, top + tileH)
                var sample = 1
                while (maxOf(w, bottom - top) / (sample * 2) >= MAX_EDGE) sample *= 2
                var bmp = decoder.decodeRegion(Rect(0, top, w, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
                val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
                if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                out += ByteArrayOutputStream().use { s -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, s); s.toByteArray() }
                bmp.recycle()
                if (bottom >= h) break
                top += (tileH * 0.9).toInt()
            }
            return out
        } finally {
            decoder.recycle()
        }
    }

    fun parse(json: String): ScanResult {
        val o = JSONObject(json)
        val orders = o.optJSONArray("transactions")
        return ScanResult(
            sourceApp = o.optString("source_app", "other"),
            warnings = List(o.optJSONArray("warnings")?.length() ?: 0) { o.getJSONArray("warnings").getString(it) },
            orders = List(orders?.length() ?: 0) { i ->
                val t = orders!!.getJSONObject(i)
                val items = t.optJSONArray("items")
                ScanOrder(
                    date = t.optNullString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    time = t.optNullString("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                    merchant = t.optString("merchant"),
                    total = t.optLong("total_amount"),
                    currency = t.optString("currency", "KRW"),
                    paymentHint = t.optNullString("payment_hint"),
                    status = t.optString("status", "paid"),
                    items = List(items?.length() ?: 0) { j ->
                        val it = items!!.getJSONObject(j)
                        ScanItem(it.optString("name"), it.optInt("quantity", 1), it.optLong("amount"), it.optNullString("category_id")?.toLongOrNull())
                    },
                    shippingFee = t.optLong("shipping_fee"),
                    discount = t.optLong("discount"),
                    confidence = t.optDouble("confidence", 1.0),
                )
            },
        )
    }

    fun suggestPay(order: ScanOrder, sourceApp: String, pays: List<PayMethod>, defaults: List<Rule>): Long? {
        order.paymentHint?.let { hint ->
            pays.firstOrNull { p ->
                similar(p.name, hint) || p.aliases.split(',').any { it.isNotBlank() && similar(it, hint) } ||
                    (p.issuer.isNotEmpty() && hint.contains(p.issuer))
            }?.let { return it.id }
        }
        return defaults.firstOrNull { it.pattern == sourceApp }?.value?.toLongOrNull()?.takeIf { id -> pays.any { it.id == id } }
    }

    /** An existing transaction for the same order, e.g. the card SMS for a Coupang purchase. */
    suspend fun duplicateOf(order: ScanOrder): Tx? {
        val date = order.date ?: return null
        val from = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return app.dao.txAround(from, to).firstOrNull { it.type == TxType.EXPENSE && it.amount == order.total && similar(it.merchant, order.merchant) }
    }

    private fun splitsFor(order: ScanOrder, cats: List<Long?>, total: Long): List<TxSplit> {
        val items = order.items.mapIndexed { i, it -> TxSplit(txId = 0, name = it.name, quantity = it.quantity, amount = it.amount, categoryId = cats.getOrNull(i)) }
        val diff = total - items.sumOf { it.amount }
        if (diff == 0L || items.isEmpty()) return items
        val main = items.maxByOrNull { it.amount }?.categoryId
        return items + TxSplit(txId = 0, name = if (order.shippingFee > 0 || order.discount > 0) "배송비·할인" else "기타", amount = diff, categoryId = main)
    }

    suspend fun save(job: ScanJob, result: ScanResult, choices: List<OrderChoice>, separateItems: Boolean) {
        val dao = app.dao
        val sourceName = sourceNames[result.sourceApp] ?: "스크린샷"
        result.orders.zip(choices).forEach { (order, c) ->
            if (!c.include) return@forEach
            val splits = splitsFor(order, c.categories, c.total)
            val mainCat = splits.maxByOrNull { it.amount }?.categoryId
            val at = order.date?.atTime(order.time ?: LocalTime.NOON)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: job.createdAt
            val merchant = order.merchant.ifBlank { sourceName }
            val memo = order.items.firstOrNull()?.name?.let { if (order.items.size > 1) "$it 외 ${order.items.size - 1}개" else it }.orEmpty()
            when {
                c.mergeInto != null -> {
                    dao.deleteSplits(c.mergeInto.id)
                    if (splits.size > 1) dao.insertSplits(splits.map { it.copy(txId = c.mergeInto.id) })
                    dao.update(c.mergeInto.copy(
                        scanJobId = job.id, memo = c.mergeInto.memo.ifBlank { memo },
                        categoryId = c.mergeInto.categoryId ?: mainCat, updatedAt = System.currentTimeMillis(),
                    ))
                }
                separateItems && splits.size > 1 -> splits.forEach { s ->
                    dao.insert(Tx(amount = s.amount, occurredAt = at, merchant = merchant, memo = s.name, categoryId = s.categoryId,
                        paymentMethodId = c.payId, source = TxSource.SCREENSHOT, scanJobId = job.id))
                }
                else -> dao.insertWithSplits(
                    Tx(amount = c.total, occurredAt = at, merchant = merchant, memo = memo, categoryId = mainCat,
                        paymentMethodId = c.payId, source = TxSource.SCREENSHOT, scanJobId = job.id),
                    if (splits.size > 1) splits else emptyList(),
                )
            }
            // learn: corrected item categories become hints for the next scan, the chosen payment becomes the app's default
            order.items.forEachIndexed { i, item ->
                val chosen = c.categories.getOrNull(i)
                if (chosen != null && chosen != item.categoryId) Categorizer.learn(item.name.split(' ').take(2).joinToString(" "), chosen)
            }
            if (order.paymentHint == null && c.payId != null && result.sourceApp in sourceNames && result.sourceApp !in setOf("bank", "card", "receipt")) {
                dao.putRule(RuleKind.SOURCE_DEFAULT_PAYMENT, result.sourceApp, c.payId.toString())
            }
        }
        dao.update(job.copy(status = ScanStatus.SAVED))
    }
}
