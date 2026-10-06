package com.choimanseon.pocketlog.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.Categorizer
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.auto.paid
import com.choimanseon.pocketlog.auto.similar
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.tagIds
import com.choimanseon.pocketlog.domain.tagsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ScanItem(val name: String, val quantity: Int, val amount: Long, val categoryId: Long?, val tags: List<Long> = emptyList())

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
    val names: List<String> = emptyList(), // per item, as corrected on the review screen
    val note: String = "", // 메모
    val tags: List<Set<Long>> = emptyList(), // per item, as picked on the review screen; missing = the AI's
)

/** What an existing record already has of a scanned order: decides what the review screen offers for it. */
enum class Holds {
    MISSING,     // no items yet, e.g. a card SMS: 품목 넣기
    NOTHING_NEW, // the same items, categories and tags, or the order has no items: nothing to put in
    DIFFERENT,   // other items: 품목 바꾸기
}

val sourceNames = mapOf(
    "coupang" to "쿠팡", "naver" to "네이버", "kurly" to "컬리", "baemin" to "배달의민족", "yogiyo" to "요기요",
    "musinsa" to "무신사", "11st" to "11번가", "gmarket" to "G마켓", "toss" to "토스", "kakaopay" to "카카오페이",
    "naverpay" to "네이버페이", "bank" to "은행", "card" to "카드", "receipt" to "영수증",
)

object Scan {
    // longest edge the model reads at full resolution. Measured 2026-10-05 (TODO.md #32): 1600px saves 0.2원 a shot on Luna and
    // no time, 768px misreads small text, so screenshots go at full size
    private const val MAX_EDGE = 2576
    private const val MAX_TILES = 8
    private const val KEPT_EDGE = 960 // after saving, a screenshot is only a thumbnail on the detail screen
    private val zone get() = ZoneId.systemDefault()

    /** The job whose ScanScreen is on screen right now: no notification for it. */
    @Volatile var viewing: Long? = null

    private fun dir() = File(app.filesDir, "scans").apply { mkdirs() }
    fun imageFiles(job: ScanJob) = (0 until job.imageCount).map { File(dir(), "${job.id}_$it.jpg") }

    /** Copies the images right away (shared URIs can expire), then calls the AI in the app scope. */
    suspend fun start(context: Context, uris: List<Uri>): Long = withContext(Dispatchers.IO) {
        val tiles = uris.flatMap { runCatching { tiles(context, it) }.getOrDefault(emptyList()) }.take(MAX_TILES)
        require(tiles.isNotEmpty()) { "이미지를 읽을 수 없어요" }
        val hash = MessageDigest.getInstance("SHA-256").run { tiles.forEach { update(it) }; digest() }.joinToString("") { "%02x".format(it) }
        reuse(hash, tiles)?.let { return@withContext it }
        val id = app.dao.insert(ScanJob(imageHash = hash, imageCount = tiles.size, status = ScanStatus.RUNNING))
        tiles.forEachIndexed { i, bytes -> File(dir(), "${id}_$i.jpg").writeBytes(bytes) }
        app.scope.launch { run(id) }
        id
    }

    /**
     * The same screenshot again opens its earlier analysis, with no second AI call. One saved before whose records were
     * all deleted since opens for review again, its images back (TODO #66); one still recorded shows as saved.
     */
    suspend fun reuse(hash: String, tiles: List<ByteArray>): Long? {
        val old = app.dao.scanByHash(hash) ?: return null
        if (old.status == ScanStatus.SAVED && app.dao.liveTxOfScan(old.id) == 0) {
            tiles.forEachIndexed { i, bytes -> File(dir(), "${old.id}_$i.jpg").writeBytes(bytes) }
            app.dao.update(old.copy(status = ScanStatus.DONE, imageCount = tiles.size))
        }
        return old.id
    }

    suspend fun run(id: Long) {
        val dao = app.dao
        val job = dao.scanJobOnce(id) ?: return
        dao.update(job.copy(status = ScanStatus.RUNNING, error = null))
        val result = runCatching {
            if (!Ai.configured) throw AiError(0, "AI 서버가 아직 설정되지 않았어요 (server/README.md)")
            Ai.scan(imageFiles(job).map { it.readBytes() }, dao.categoriesOnce(), dao.rulesOnce(RuleKind.CATEGORY))
        }
        val done = result.fold(
            onSuccess = { job.copy(status = ScanStatus.DONE, resultJson = (it.optJSONObject("result") ?: it).toString()) },
            onFailure = { job.copy(status = ScanStatus.FAILED, error = friendly(it)) },
        )
        dao.update(done)
        if (viewing == id) return
        val found = done.resultJson?.let { runCatching { parse(it).orders.size }.getOrNull() } ?: 0
        when {
            done.status == ScanStatus.FAILED -> Notify.scan(id, "스크린샷 분석에 실패했어요", done.error ?: "눌러서 다시 시도해 주세요")
            found > 0 -> Notify.scan(id, "스크린샷 분석이 끝났어요", "결제 ${found}건을 찾았어요. 눌러서 확인하고 저장해 주세요")
            else -> Notify.scan(id, "스크린샷 분석이 끝났어요", "결제 내역을 찾지 못했어요")
        }
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
        turned(context, uri)?.let { return listOf(it) }
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

    /** A camera photo stored sideways with an EXIF rotation (a receipt): turned upright as one image, or null for anything else. */
    private fun turned(context: Context, uri: Uri): ByteArray? {
        val degrees = context.contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> null
            }
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
        val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postScale(scale, scale); postRotate(degrees) }, true)
        return ByteArrayOutputStream().use { s -> upright.compress(Bitmap.CompressFormat.JPEG, 85, s); s.toByteArray() }
    }

    /**
     * Runs at app start. Images go when nothing needs them: an analysis never saved after 30 days, or a saved one whose
     * transactions were all deleted. Saved ones from before shrinking existed are shrunk now. The hash stays, so a repeat opens the old analysis ([reuse]).
     */
    suspend fun tidy(now: Long = System.currentTimeMillis()) {
        val dao = app.dao
        dao.scansToForget(now - 30 * 86_400_000L).forEach { job ->
            imageFiles(job).forEach { it.delete() }
            dao.update(job.copy(imageCount = 0))
        }
        dao.savedScans().forEach(::shrink)
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
                        val tags = it.optJSONArray("tag_ids")
                        ScanItem(
                            it.optString("name"), it.optInt("quantity", 1), it.optLong("amount"), it.optNullString("category_id")?.toLongOrNull(),
                            List(tags?.length() ?: 0) { k -> tags!!.optString(k).toLongOrNull() }.filterNotNull(),
                        )
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
        return app.dao.txAround(from, to).firstOrNull { it.type == TxType.EXPENSE && paid(it) == order.total && similar(it.merchant, order.merchant) }
    }

    /** [order] with the item names fixed on the review screen. */
    fun named(order: ScanOrder, names: List<String>) =
        order.copy(items = order.items.mapIndexed { i, it -> it.copy(name = names.getOrNull(i)?.trim()?.ifBlank { null } ?: it.name) })

    /** [tags]: each item's tags, kept on the item for the tag totals (DB 8). */
    fun splitsFor(order: ScanOrder, cats: List<Long?>, total: Long, tags: List<Collection<Long>> = emptyList()): List<TxSplit> {
        val items = order.items.mapIndexed { i, it ->
            TxSplit(txId = 0, name = it.name, quantity = it.quantity, amount = it.amount, categoryId = cats.getOrNull(i), tags = tags.getOrNull(i)?.joinToString(","))
        }
        val diff = total - items.sumOf { it.amount }
        if (diff == 0L || items.isEmpty()) return items
        val main = items.maxByOrNull { it.amount }?.categoryId
        return items + TxSplit(txId = 0, name = if (order.shippingFee > 0 || order.discount > 0) "배송비·할인" else "기타", amount = diff, categoryId = main)
    }

    /** The [picked] tags a record of [category] can carry: the category's own and the shared ones. */
    fun tagsOn(category: Long?, picked: Collection<Long>, categories: List<Category>) =
        categories.tagsFor(category, TxType.EXPENSE).map { it.id }.filter { it in picked }

    /** One record for the whole order in [category]: each item's tags that fit its own category, then those that fit [category]. */
    fun orderTags(category: Long?, itemCats: List<Long?>, picks: List<Collection<Long>>, categories: List<Category>) =
        tagsOn(category, picks.flatMapIndexed { i, p -> tagsOn(itemCats.getOrNull(i), p, categories) }, categories)

    /**
     * 내역 → 품목 (TODO #67): one item's category and tags changed after saving. The record keeps the tags it has of its own and
     * carries its items' tags, as a fresh save does. Items from before DB 8 get an empty list, so tag totals stay per item;
     * the 배송비·할인 line is no item and stays without.
     */
    suspend fun editItem(txId: Long, splitId: Long, category: Long?, tags: Collection<Long>) {
        val dao = app.dao
        val tx = dao.txOnce(txId) ?: return
        val cats = dao.categoriesOnce()
        val splits = dao.splitsOf(txId).first()
        val adjust = adjustment(splits)
        val own = dao.tagsOfOnce(txId).filter { t -> splits.none { t in it.tagIds() } }
        val edited = splits.map { s ->
            when {
                s.id == splitId -> s.copy(categoryId = category, tags = tagsOn(category, tags, cats).joinToString(","))
                s == adjust || s.tags != null -> s
                else -> s.copy(tags = "")
            }
        }
        dao.replaceItems(txId, edited, (own + tagsOn(tx.categoryId, edited.flatMap { it.tagIds() }, cats)).distinct())
    }

    /** The line [splitsFor] adds when the items don't add up to what was paid; not an item. */
    fun adjustment(splits: List<TxSplit>) = splits.lastOrNull()?.takeIf { it.tags == null && it.name in setOf("배송비·할인", "기타") }

    /** Compares [tx] (with its [txSplits] and [txTags]) with what the review screen would put in it: [planned] items and [tags]. */
    fun holds(tx: Tx, txSplits: List<TxSplit>, txTags: Collection<Long>, planned: List<TxSplit>, tags: Collection<Long>): Holds {
        fun List<TxSplit>.key() = map { Triple(it.name, it.amount, it.categoryId) }
        val sameTags = txTags.containsAll(tags)
        return when {
            planned.isEmpty() -> Holds.NOTHING_NEW
            txSplits.isNotEmpty() -> if (txSplits.key() == planned.key() && sameTags) Holds.NOTHING_NEW else Holds.DIFFERENT
            // one item is kept as the 품명 and the category, not as a list
            planned.size == 1 && tx.memo.startsWith(planned[0].name) && tx.categoryId == planned[0].categoryId && sameTags -> Holds.NOTHING_NEW
            else -> Holds.MISSING
        }
    }

    private fun itemsMemo(items: List<ScanItem>) = items.firstOrNull()?.name?.let { if (items.size > 1) "$it 외 ${items.size - 1}개" else it }.orEmpty()

    suspend fun save(job: ScanJob, result: ScanResult, choices: List<OrderChoice>, separateItems: Boolean) {
        val dao = app.dao
        val cats = dao.categoriesOnce()
        val sourceName = sourceNames[result.sourceApp] ?: "스크린샷"
        result.orders.zip(choices).forEach { (scanned, c) ->
            if (!c.include) return@forEach
            val order = named(scanned, c.names)
            val picks = order.items.mapIndexed { i, it -> c.tags.getOrNull(i) ?: it.tags.toSet() }
            val splits = splitsFor(order, c.categories, c.total, picks.mapIndexed { i, p -> tagsOn(c.categories.getOrNull(i), p, cats) })
            val mainCat = splits.maxByOrNull { it.amount }?.categoryId
            val at = order.date?.atTime(order.time ?: LocalTime.NOON)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: job.createdAt
            val merchant = order.merchant.ifBlank { sourceName }
            val note = c.note.trim()
            val memo = itemsMemo(order.items) // 품명
            when {
                c.mergeInto != null -> {
                    // 품목 넣기 · 바꾸기: amount, date and payment stay; the items, categories and tags of this screen go in
                    val old = c.mergeInto
                    val oldSplits = dao.splitsOf(old.id).first()
                    // an installment plan takes no items: month 1 would count the whole order in the stats
                    val plan = old.installmentMonths > 1
                    dao.deleteSplits(old.id)
                    if (splits.size > 1 && !plan) dao.insertSplits(splits.map { it.copy(txId = old.id) })
                    // the 품명 an earlier scan wrote ("세재 외 1개") follows the new items; one the user typed stays
                    val oldAuto = oldSplits.firstOrNull()?.let { old.memo.startsWith(it.name) } == true
                    dao.update(old.copy(
                        scanJobId = job.id, memo = if (old.memo.isBlank() || oldAuto) memo else old.memo,
                        note = listOf(old.note, note).filter { it.isNotBlank() }.distinct().joinToString("\n"), updatedAt = System.currentTimeMillis(),
                    ))
                    if (mainCat != null) dao.setCategory(old.id, mainCat)
                    val cat = mainCat ?: old.categoryId
                    dao.setTags(old.id, tagsOn(cat, dao.tagsOfOnce(old.id) + orderTags(cat, c.categories, picks, cats), cats))
                }
                separateItems && splits.size > 1 -> splits.forEachIndexed { k, s ->
                    val id = dao.insert(Tx(amount = s.amount, occurredAt = at, merchant = merchant, memo = s.name, note = note, categoryId = s.categoryId,
                        paymentMethodId = c.payId, source = TxSource.SCREENSHOT, scanJobId = job.id))
                    dao.setTags(id, tagsOn(s.categoryId, picks.getOrNull(k).orEmpty(), cats))
                }
                else -> {
                    val id = dao.insertWithSplits(
                        Tx(amount = c.total, occurredAt = at, merchant = merchant, memo = memo, note = note, categoryId = mainCat,
                            paymentMethodId = c.payId, source = TxSource.SCREENSHOT, scanJobId = job.id),
                        if (splits.size > 1) splits else emptyList(),
                    )
                    dao.setTags(id, orderTags(mainCat, c.categories, picks, cats))
                }
            }
            // learn: corrected item categories and tags become hints for the next scan, the chosen payment becomes the app's default
            order.items.forEachIndexed { i, item ->
                val chosen = c.categories.getOrNull(i) ?: return@forEachIndexed
                val tags = tagsOn(chosen, picks[i], cats)
                if (chosen != item.categoryId || tags.toSet() != tagsOn(chosen, item.tags, cats).toSet()) {
                    Categorizer.learn(item.name.split(' ').take(2).joinToString(" "), Pick(chosen, tags))
                }
            }
            if (order.paymentHint == null && c.payId != null && result.sourceApp in sourceNames && result.sourceApp !in setOf("bank", "card", "receipt")) {
                dao.putRule(RuleKind.SOURCE_DEFAULT_PAYMENT, result.sourceApp, c.payId.toString())
            }
        }
        dao.update(job.copy(status = ScanStatus.SAVED))
        shrink(job)
    }

    /** Saved screenshots are kept for the detail screen at about a tenth of the size; the hash in [ScanJob] still spots a repeat. */
    private fun shrink(job: ScanJob) = imageFiles(job).forEach { f ->
        if (f.length() < 200_000) return@forEach // already small
        runCatching {
            val bmp = BitmapFactory.decodeFile(f.path) ?: return@runCatching
            val scale = KEPT_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale >= 1f) return@runCatching
            val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        }
    }
}
