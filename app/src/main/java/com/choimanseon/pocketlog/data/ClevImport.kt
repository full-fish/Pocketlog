package com.choimanseon.pocketlog.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.CardParser
import com.choimanseon.pocketlog.auto.normalize
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Imports a 똑똑가계부 (Cleveni) backup: the .db file it writes is a plain SQLite database.
 * Replaces everything in Pocketlog, keeping the old app's own categories and payment methods,
 * and turns the old history into merchant → category rules so auto-categorizing keeps the user's habits.
 */
object ClevImport {
    data class Summary(
        val expenses: Int,
        val incomes: Int,
        val categories: Int,
        val payMethods: Int,
        val rules: Int,
        val first: LocalDate?,
        val last: LocalDate?,
    )

    private const val INCOME_ID_OFFSET = 1000L // income categories are a separate table with overlapping ids

    /** SQLite needs a real file path, so the picked document is copied to the cache first. */
    fun copy(context: Context, uri: Uri): File {
        val file = File(context.cacheDir, "clev-import.db")
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun <T> read(file: File, block: (SQLiteDatabase) -> T): T {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw IllegalArgumentException("SQLite 파일이 아니에요")
        }
        return db.use {
            val tables = it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> c.strings(0).toSet() }
            require(tables.containsAll(listOf("spendinglist", "earninglist", "catelist", "ecatelist", "cardlist"))) { "똑똑가계부 백업 파일이 아니에요" }
            block(it)
        }
    }

    private fun Cursor.strings(col: Int) = buildList { while (moveToNext()) add(getString(col).orEmpty()) }
    private fun SQLiteDatabase.count(table: String) = rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun SQLiteDatabase.rows(sql: String) = rawQuery(sql, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).associate { c.getColumnName(it) to c.getString(it).orEmpty() }) }
    }

    fun peek(file: File): Summary = read(file) { db ->
        val dates = db.rows("SELECT MIN(s_date) AS a, MAX(s_date) AS b FROM spendinglist").first()
        Summary(
            expenses = db.count("spendinglist"),
            incomes = db.count("earninglist"),
            categories = db.count("catelist") + db.count("ecatelist"),
            payMethods = db.count("cardlist"),
            rules = 0,
            first = dates["a"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            last = dates["b"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    suspend fun run(file: File): Summary {
        val data = read(file) { db -> convert(db) }
        app.dao.replaceAll(data.categories, data.pays, data.txs, data.rules)
        if (data.pays.none { it.name == "현금" }) app.dao.upsert(PayMethod(kind = PayKind.CASH, name = "현금", sort = 99_999))
        return peek(file).copy(rules = data.rules.size)
    }

    private class Converted(val categories: List<Category>, val pays: List<PayMethod>, val txs: List<Tx>, val rules: List<Rule>)

    private fun convert(db: SQLiteDatabase): Converted {
        val categories = categories(db.rows("SELECT * FROM catelist"), "c_", TxType.EXPENSE, 0) +
            categories(db.rows("SELECT * FROM ecatelist"), "ec_", TxType.INCOME, INCOME_ID_OFFSET)
        val catIds = categories.map { it.id }.toSet()
        val pays = db.rows("SELECT * FROM cardlist").map { r ->
            val (kind, issuer) = payKind(r.getValue("d_name"), r.getValue("d_aicode"))
            PayMethod(id = r.getValue("_id").toLong(), kind = kind, name = r.getValue("d_name"), issuer = issuer, sort = r.getValue("d_sort").toIntOrNull() ?: 0)
        }
        val payIds = pays.map { it.id }.toSet()

        val spending = db.rows("SELECT * FROM spendinglist ORDER BY s_date, s_time")
        val spendingById = spending.associateBy { it.getValue("_id") }
        // rows keep the old ids so installment months can point to their purchase; incomes go after all spending ids
        val incomeIdOffset = spending.maxOfOrNull { it.getValue("_id").toLong() } ?: 0

        fun tx(r: Map<String, String>, p: String, type: TxType, offset: Long): Tx {
            val sub = r.getValue("${p}subcate").toLongOrNull()?.takeIf { it > 0 }?.plus(offset)
            val top = r.getValue("${p}cate").toLongOrNull()?.takeIf { it > 0 }?.plus(offset)
            val months = r["s_cardmonth"]?.toIntOrNull() ?: 1
            // 할부: the old app stores one row per month. Month 1 is the purchase (s_cardmonth = n); months 2..n
            // have day "50" in their month, time "k:n", and s_cardmonth = 1000 + the purchase row's id.
            val purchase = months.takeIf { it > 1000 }?.let { spendingById[(it - 1000).toString()] }
            val (date, time) = r.getValue("${p}date") to r.getValue("${p}time")
            val at = parseTime(date, time, purchase)
            val k = time.substringBefore(':').toIntOrNull()
            val n = purchase?.get("s_cardmonth")?.toIntOrNull() ?: time.substringAfter(':').toIntOrNull()
            val installment = when {
                months in 2..36 -> months
                purchase != null || at.second -> n ?: 0
                else -> 0 // "1" means 일시불
            }
            val memo = r.getValue("${p}memo").trim().ifBlank {
                when {
                    months in 2..36 -> "할부 1/${months}회차"
                    installment > 0 && k != null -> "할부 $k/${installment}회차"
                    else -> ""
                }
            }
            return Tx(
                id = r.getValue("_id").toLong() + if (type == TxType.INCOME) incomeIdOffset else 0,
                type = type,
                amount = r.getValue("${p}price").toLongOrNull() ?: 0,
                occurredAt = at.first,
                merchant = r.getValue("${p}where").trim(),
                memo = memo,
                categoryId = sub?.takeIf { it in catIds } ?: top?.takeIf { it in catIds },
                paymentMethodId = r.getValue("${p}card").toLongOrNull()?.takeIf { it in payIds },
                installmentMonths = installment,
                installmentOf = purchase?.getValue("_id")?.toLong(),
                source = TxSource.IMPORT,
            )
        }
        val txs = spending.map { tx(it, "s_", TxType.EXPENSE, 0) } +
            db.rows("SELECT * FROM earninglist ORDER BY e_date, e_time").map { tx(it, "e_", TxType.INCOME, INCOME_ID_OFFSET) }
        return Converted(categories, pays, txs, rulesFrom(txs))
    }

    /**
     * epoch millis, and whether the date was the "day 50" installment marker. Nothing is dropped:
     * an unreadable date falls back to the first day of its month (or 2000-01-01), so totals always match the old app.
     */
    private fun parseTime(date: String, time: String, purchase: Map<String, String>?): Pair<Long, Boolean> {
        val zone = ZoneId.systemDefault()
        runCatching { return LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli() to false }
        val month = runCatching { YearMonth.parse(date.take(7)) }.getOrElse { YearMonth.of(2000, 1) }
        val purchaseAt = purchase?.let { runCatching { LocalDateTime.parse("${it.getValue("s_date")}T${it.getValue("s_time")}") }.getOrNull() }
        val day = (purchaseAt?.dayOfMonth ?: 1).coerceAtMost(month.lengthOfMonth())
        val at = month.atDay(day).atTime(purchaseAt?.toLocalTime() ?: LocalTime.NOON)
        return at.atZone(zone).toInstant().toEpochMilli() to true
    }

    private fun categories(rows: List<Map<String, String>>, p: String, type: TxType, offset: Long): List<Category> {
        val tops = rows.filter { it.getValue("${p}super") == "0" }.sortedBy { it.getValue("${p}sort") }
        val colorOf = tops.mapIndexed { i, r -> r.getValue("_id") to CategoryColors[(i + if (type == TxType.INCOME) 1 else 0) % CategoryColors.size] }.toMap()
        val byId = rows.associateBy { it.getValue("_id") }
        return rows.map { r ->
            val parent = r.getValue("${p}super").takeIf { it != "0" }
            val name = r.getValue("${p}name")
            val parentName = parent?.let { byId[it]?.get("${p}name") }
            Category(
                id = r.getValue("_id").toLong() + offset,
                type = type,
                name = name,
                icon = icons[name] ?: parentName?.let { icons[it] } ?: "box",
                color = colorOf[parent ?: r.getValue("_id")] ?: CategoryColors.last(),
                parentId = parent?.toLong()?.plus(offset),
                sort = r.getValue("${p}sort").toIntOrNull() ?: 0,
            )
        }
    }

    /** Old card codes look like "KR/C/SSC": country / B(ank) C(ard) P(ay) / company. */
    private fun payKind(name: String, code: String): Pair<PayKind, String> {
        val (_, group, company) = (code.split('/') + listOf("", "", "")).take(3)
        val kind = when {
            "머니" in name || group == "P" -> PayKind.PAY_MONEY
            group == "B" -> PayKind.BANK
            group == "C" -> if (company.endsWith("D") || "체크" in name) PayKind.CHECK else PayKind.CREDIT
            else -> PayKind.CASH
        }
        val issuer = when (company) {
            "KAB", "KAD" -> "카카오뱅크"
            "TSD" -> "토스뱅크"
            "TSP" -> "토스"
            else -> CardParser.issuerKey(name).orEmpty()
        }
        return kind to issuer
    }

    /**
     * The old history as rules: a merchant seen at least twice, mostly in one category.
     * Short names ("술", "옷") are skipped because rules match by "contains".
     */
    private fun rulesFrom(txs: List<Tx>): List<Rule> =
        txs.filter { it.categoryId != null && it.merchant.isNotBlank() }
            .groupBy { normalize(it.merchant) }
            .filterKeys { it.length >= 3 }
            .mapNotNull { (merchant, list) ->
                if (list.size < 2) return@mapNotNull null
                val (cat, n) = list.groupingBy { it.categoryId!! }.eachCount().maxBy { it.value }
                if (n * 10 < list.size * 6) null else Triple(merchant, cat, list.size)
            }
            .sortedBy { it.third } // most frequent last = highest id = listed first (rules are read newest first)
            .map { (merchant, cat, _) -> Rule(kind = RuleKind.CATEGORY, pattern = merchant, value = cat.toString()) }

    // 똑똑가계부 default category names (plus a few common custom ones)
    private val icons = mapOf(
        "식비" to "rice_bowl", "외식" to "restaurant", "집밥" to "egg", "식재료" to "grocery",
        "문화생활비" to "movie", "영화/공연" to "movie", "게임/어플" to "game", "음악" to "music", "도서" to "book", "여행" to "flight", "취미" to "palette", "보드게임" to "casino", "앵무새" to "pets",
        "주거생활비" to "home", "집세/관리비" to "home", "청소/세탁" to "laundry", "통신비" to "phone", "생필품" to "soap", "생활서비스" to "handyman", "생활세금" to "receipt", "주방용품" to "kitchen",
        "건강관리비" to "medication", "운동/다이어트" to "run", "병원비/약값" to "medication", "요양비" to "hospital", "건강식품" to "medication",
        "교통비" to "subway", "대중교통" to "subway", "택시비" to "taxi", "장거리경비" to "train", "렌트비" to "car",
        "차량유지비" to "car", "유류비" to "gas", "정비/세차" to "car_repair", "주차/통행" to "parking", "자동차보험" to "shield",
        "쇼핑비" to "bag", "의류/잡화" to "clothes", "전자제품" to "laptop", "가구" to "chair",
        "미용비" to "cut", "헤어샵" to "cut", "화장품" to "brush", "뷰티관리" to "spa",
        "교육비" to "book", "학비" to "school", "교재비" to "book", "육아" to "toys",
        "사회생활비" to "gift", "경조사비" to "flower", "선물/용돈" to "gift", "모임회비" to "groups", "기부" to "handshake",
        "과식" to "fastfood", "술값" to "bar", "야식" to "dinner", "간식" to "cookie",
        "금융보험비" to "bank", "금융이자" to "bank", "수수료" to "bank", "보장보험" to "shield",
        "저축" to "savings", "예금/적금" to "savings", "주식/펀드" to "chart", "저축보험" to "savings", "기타" to "box", "개발 구독비" to "code",
        "근로소득" to "work", "급여" to "payments", "보너스" to "celebration", "금융소득" to "bank", "이자" to "bank", "배당금" to "chart", "중고판매" to "sell", "용돈" to "redeem",
    )
}
