package com.choimanseon.pocketlog.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Tx::class, TxSplit::class, Category::class, PayMethod::class, Budget::class, Rule::class, RawMessage::class, ScanJob::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class PocketDb : RoomDatabase() {
    abstract fun dao(): PocketDao

    companion object {
        const val NAME = "pocketlog.db"
        fun open(context: Context) = Room.databaseBuilder(context, PocketDb::class.java, NAME).addMigrations(EmojiToIcon).build()
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

private val defaultExpense = listOf(
    "식비" to "rice_bowl" to listOf("점심", "저녁", "간식"),
    "카페" to "cafe" to emptyList(),
    "배달" to "delivery" to emptyList(),
    "편의점" to "convenience" to emptyList(),
    "장보기" to "cart" to emptyList(),
    "쇼핑" to "bag" to listOf("의류", "전자기기"),
    "생활용품" to "soap" to emptyList(),
    "교통" to "subway" to listOf("대중교통", "택시"),
    "자동차" to "car" to listOf("주유", "주차·통행료"),
    "주거·통신" to "home" to listOf("월세·관리비", "통신비", "공과금"),
    "의료·건강" to "medication" to emptyList(),
    "문화·여가" to "movie" to emptyList(),
    "여행" to "flight" to emptyList(),
    "교육" to "book" to emptyList(),
    "구독" to "subscriptions" to emptyList(),
    "술·음료" to "bar" to emptyList(),
    "미용" to "cut" to emptyList(),
    "경조사·선물" to "gift" to emptyList(),
    "반려동물" to "pets" to emptyList(),
    "기타" to "box" to emptyList(),
)

private val defaultIncome = listOf("급여" to "work", "용돈" to "redeem", "부수입" to "payments", "환급·이자" to "bank", "기타수입" to "more")

/** Called only inside [PocketDao.seedIfEmpty]'s transaction. */
internal suspend fun PocketDao.insertDefaults() {
    defaultExpense.forEachIndexed { i, (head, subs) ->
        val (name, icon) = head
        val color = CategoryColors[i % CategoryColors.size]
        val id = upsert(Category(type = TxType.EXPENSE, name = name, icon = icon, color = color, sort = i))
        insertCategories(subs.mapIndexed { j, sub ->
            Category(type = TxType.EXPENSE, name = sub, icon = icon, color = color, parentId = id, sort = j)
        })
    }
    insertCategories(defaultIncome.mapIndexed { i, (name, icon) ->
        Category(type = TxType.INCOME, name = name, icon = icon, color = CategoryColors[(i + 1) % CategoryColors.size], sort = i)
    })
    if (payMethodsOnce().isEmpty()) upsert(PayMethod(kind = PayKind.CASH, name = "현금"))
}
