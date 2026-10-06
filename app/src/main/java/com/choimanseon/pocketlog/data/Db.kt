package com.choimanseon.pocketlog.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Tx::class, TxSplit::class, Category::class, TxTag::class, PayMethod::class, Budget::class, Rule::class, RawMessage::class, ScanJob::class,
        Favorite::class, Report::class,
    ],
    version = 8,
    // 5: 즐겨찾기 · 반복 기록, AI 월간 리포트, 주 · 연 예산 (new tables and a column with a default, so Room writes it)
    // 6: 즐겨찾기 order and dummy mark
    // 7: Tx.note, the 메모 apart from the 품명 (Tx.memo)
    // 8: TxSplit.tags, each item's own tags (SplitTags)
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class PocketDb : RoomDatabase() {
    abstract fun dao(): PocketDao

    companion object {
        const val NAME = "pocketlog.db"
        fun open(context: Context) = Room.databaseBuilder(context, PocketDb::class.java, NAME).addMigrations(EmojiToIcon, SubcategoriesToTags, SplitTags).build()
    }
}

/** Category icons were emoji until version 3. Anything not listed falls back to the default icon. */
internal val EmojiToIcon = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) = mapOf(
        "🍚" to "rice_bowl", "🍽️" to "restaurant", "🍳" to "egg", "🥬" to "grocery", "🍗" to "dinner", "🍪" to "cookie",
        "☕" to "cafe", "🍺" to "bar", "🛵" to "delivery", "🏪" to "convenience", "🛒" to "cart", "🛍️" to "bag",
        "👕" to "clothes", "💻" to "laptop", "📱" to "phone", "🛋️" to "chair", "🧻" to "soap", "🏠" to "home",
        "🧺" to "laundry", "🛠️" to "handyman", "🧾" to "receipt", "🚇" to "subway", "🚕" to "taxi", "🚄" to "train",
        "✈️" to "flight", "🚗" to "car", "⛽" to "gas", "🔧" to "car_repair", "🅿️" to "parking", "💊" to "medication",
        "🏥" to "hospital", "🏃" to "run", "🎬" to "movie", "🎮" to "game", "🎵" to "music", "📚" to "book",
        "📖" to "book", "🎨" to "palette", "🎲" to "casino", "📺" to "subscriptions", "💇" to "cut", "💄" to "brush",
        "💅" to "spa", "🎓" to "school", "🧸" to "toys", "🐶" to "pets", "🦜" to "pets", "🎁" to "gift",
        "🧧" to "redeem", "💐" to "flower", "🎉" to "celebration", "👥" to "groups", "🤝" to "handshake", "💼" to "work",
        "💵" to "payments", "💰" to "savings", "🏦" to "bank", "📈" to "chart", "🛡️" to "shield", "📦" to "box", "➕" to "more",
    ).forEach { (emoji, icon) -> db.execSQL("UPDATE Category SET emoji = ? WHERE emoji = ?", arrayOf(icon, emoji)) }
}

val CategoryColors = longArrayOf(
    0xFF4C5BF5, 0xFF12B886, 0xFFFF9F1C, 0xFFF06595, 0xFF845EF7,
    0xFF22B8CF, 0xFFFAB005, 0xFFFF6B6B, 0xFF51CF66, 0xFF868E96,
)

/** A category and its tags (TODO #27). [tagGroup] = the 공통 태그 of its type. */
internal class Seed(val type: TxType, val name: String, val icon: String, val tags: List<String>, val hidden: Boolean = false, val tagGroup: Boolean = false)

private fun expense(name: String, icon: String, tags: String, hidden: Boolean = false) = Seed(TxType.EXPENSE, name, icon, tags.split(" · ").filter { it.isNotBlank() }, hidden)

internal val defaultSeeds = listOf(
    expense("식비", "rice_bowl", "외식 · 장보기 · 야식"),
    expense("카페·간식", "cafe", "커피 · 디저트·빵 · 과자·간식 · 편의점"),
    expense("술·유흥", "bar", "술집 · 홈술 · 노래방·놀거리"),
    expense("쇼핑", "bag", "옷·신발 · 가방·잡화 · 전자기기 · 가구·인테리어 · 중고거래"),
    expense("생활", "soap", "생필품 · 주방용품 · 세탁·청소 · 수리·공구 · 이사"),
    expense("주거·통신", "home", "월세 · 관리비 · 전기 · 가스 · 수도 · 휴대폰 · 인터넷"),
    expense("교통·차량", "subway", "대중교통 · 택시 · 기차 · 고속버스 · 항공 · 주유 · 주차·통행료 · 정비·세차"),
    expense("건강·의료", "medication", "병원 · 치과 · 약국 · 운동 · 영양제 · 안경·렌즈"),
    expense("뷰티·미용", "cut", "헤어 · 화장품 · 네일 · 피부관리"),
    expense("문화·여가", "movie", "영화·공연 · 도서 · 게임 · 취미 · 숙소 · 전시·체험"),
    expense("구독", "subscriptions", "OTT · 음악 · 클라우드·앱 · 개발 툴 · 멤버십"),
    expense("교육", "school", "강의 · 학원 · 자격증·시험 · 교재"),
    expense("경조사·선물", "gift", "선물 · 축의금 · 조의금 · 기부 · 부모님 용돈"),
    expense("금융", "bank", "보험 · 세금 · 수수료 · 대출이자 · 연회비"),
    expense("기타", "box", ""),
    expense("반려동물", "pets", "사료·간식 · 동물병원 · 용품 · 미용", hidden = true),
    expense("육아", "child", "기저귀·분유 · 옷 · 장난감 · 교육", hidden = true),
    Seed(TxType.EXPENSE, "공통 태그", "sell", listOf("데이트", "친구·모임", "가족", "회식", "여행", "업무·청구", "나"), tagGroup = true),
    Seed(TxType.INCOME, "급여", "work", listOf("월급", "상여", "수당")),
    Seed(TxType.INCOME, "부수입", "payments", listOf("부업", "중고판매", "리워드·캐시백")),
    Seed(TxType.INCOME, "금융수입", "bank", listOf("이자", "배당", "환급")),
    Seed(TxType.INCOME, "용돈·기타", "redeem", listOf("용돈", "기타")),
    Seed(TxType.SAVING, "저축", "savings", listOf("적금", "예금", "청약", "비상금")),
    Seed(TxType.SAVING, "투자", "chart", listOf("주식", "ETF·펀드", "연금", "코인")),
)

/** Writes [seeds] through [insert] (which returns the new row id): each category, then its tags in its color. */
internal inline fun plant(seeds: List<Seed>, sortFrom: (TxType) -> Int = { 0 }, insert: (Category) -> Long) {
    seeds.groupBy { it.type }.forEach { (type, list) ->
        list.forEachIndexed { i, s ->
            val sort = sortFrom(type) + i
            val color = if (s.tagGroup) CategoryColors.last() else CategoryColors[(sort + if (type == TxType.EXPENSE) 0 else 1) % CategoryColors.size]
            val id = insert(Category(type = type, name = s.name, icon = s.icon, color = color, sort = if (s.tagGroup) 999 else sort, hidden = s.hidden, tagGroup = s.tagGroup))
            s.tags.forEachIndexed { j, t -> insert(Category(type = type, name = t, icon = s.icon, color = color, parentId = id, sort = j)) }
        }
    }
}

/** Called only inside [PocketDao.seedIfEmpty]'s transaction. */
internal suspend fun PocketDao.insertDefaults() {
    plant(defaultSeeds) { upsert(it) }
    if (payMethodsOnce().isEmpty()) upsert(PayMethod(kind = PayKind.CASH, name = "현금"))
}

/**
 * Version 4 (TODO #27, #28): subcategories become tags, and there is a savings type.
 * A transaction in a subcategory moves to its top category and gets the subcategory as a tag; a rule for a
 * subcategory becomes "top|tag". 똑똑가계부's 저축 category (imported as spending) becomes savings.
 * The savings categories and the shared spending tags are added; the user's own categories are left as they are.
 */
internal val SubcategoriesToTags = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Category ADD COLUMN tagGroup INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `TxTag` (`txId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`txId`, `tagId`), " +
                "FOREIGN KEY(`txId`) REFERENCES `Tx`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`tagId`) REFERENCES `Category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_TxTag_tagId` ON `TxTag` (`tagId`)")

        val savingTops = "SELECT id FROM Category WHERE type = 'EXPENSE' AND parentId IS NULL AND name IN ('저축', '저축·투자')"
        db.execSQL("UPDATE Category SET type = 'SAVING' WHERE parentId IN ($savingTops)")
        db.execSQL("UPDATE Category SET type = 'SAVING' WHERE id IN ($savingTops)")
        db.execSQL("UPDATE Tx SET type = 'SAVING' WHERE type = 'EXPENSE' AND categoryId IN (SELECT id FROM Category WHERE type = 'SAVING')")
        db.execSQL("DELETE FROM Budget WHERE categoryId IN (SELECT id FROM Category WHERE type = 'SAVING')")

        val subs = "SELECT id FROM Category WHERE parentId IS NOT NULL"
        val parentOf = { col: String -> "(SELECT parentId FROM Category WHERE id = $col)" }
        db.execSQL("INSERT OR IGNORE INTO TxTag (txId, tagId) SELECT id, categoryId FROM Tx WHERE categoryId IN ($subs)")
        db.execSQL("UPDATE Tx SET categoryId = ${parentOf("Tx.categoryId")} WHERE categoryId IN ($subs)")
        db.execSQL("INSERT OR IGNORE INTO TxTag (txId, tagId) SELECT txId, categoryId FROM TxSplit WHERE categoryId IN ($subs)")
        db.execSQL("UPDATE TxSplit SET categoryId = ${parentOf("TxSplit.categoryId")} WHERE categoryId IN ($subs)")
        db.execSQL("DELETE FROM Budget WHERE categoryId IN ($subs)")
        db.execSQL("UPDATE `Rule` SET value = ${parentOf("CAST(`Rule`.value AS INTEGER)")} || '|' || value WHERE kind = 'CATEGORY' AND CAST(value AS INTEGER) IN ($subs)")

        fun has(sql: String) = db.query(sql).use { it.moveToFirst() }
        val missing = defaultSeeds.filter { s ->
            when {
                s.type == TxType.SAVING -> !has("SELECT 1 FROM Category WHERE type = 'SAVING' AND parentId IS NULL AND name = '${s.name}'")
                s.tagGroup -> !has("SELECT 1 FROM Category WHERE type = '${s.type}' AND tagGroup = 1")
                else -> false
            }
        }
        plant(missing, sortFrom = { type -> db.query("SELECT COUNT(*) FROM Category WHERE type = '$type' AND parentId IS NULL").use { it.moveToFirst(); it.getInt(0) } }) { c ->
            db.insert("Category", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("type", c.type.name); put("name", c.name); put("emoji", c.icon); put("color", c.color)
                put("parentId", c.parentId); put("sort", c.sort); put("hidden", c.hidden); put("tagGroup", c.tagGroup)
            })
        }
    }
}

/** DB 8: an order's items keep their own tags, so tag totals count only the items that carry a tag. Older items have none known (null). */
internal val SplitTags = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE TxSplit ADD COLUMN tags TEXT")
}
