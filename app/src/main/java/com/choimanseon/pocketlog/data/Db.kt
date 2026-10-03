package com.choimanseon.pocketlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Tx::class, TxSplit::class, Category::class, PayMethod::class, Budget::class, Rule::class, RawMessage::class, ScanJob::class],
    version = 1,
)
abstract class PocketDb : RoomDatabase() {
    abstract fun dao(): PocketDao

    companion object {
        const val NAME = "pocketlog.db"
        fun open(context: Context) = Room.databaseBuilder(context, PocketDb::class.java, NAME).build()
    }
}

val CategoryColors = longArrayOf(
    0xFF4C5BF5, 0xFF12B886, 0xFFFF9F1C, 0xFFF06595, 0xFF845EF7,
    0xFF22B8CF, 0xFFFAB005, 0xFFFF6B6B, 0xFF51CF66, 0xFF868E96,
)

private val defaultExpense = listOf(
    "식비" to "🍚" to listOf("점심", "저녁", "간식"),
    "카페" to "☕" to emptyList(),
    "배달" to "🛵" to emptyList(),
    "편의점" to "🏪" to emptyList(),
    "장보기" to "🛒" to emptyList(),
    "쇼핑" to "🛍️" to listOf("의류", "전자기기"),
    "생활용품" to "🧻" to emptyList(),
    "교통" to "🚇" to listOf("대중교통", "택시"),
    "자동차" to "🚗" to listOf("주유", "주차·통행료"),
    "주거·통신" to "🏠" to listOf("월세·관리비", "통신비", "공과금"),
    "의료·건강" to "💊" to emptyList(),
    "문화·여가" to "🎬" to emptyList(),
    "여행" to "✈️" to emptyList(),
    "교육" to "📚" to emptyList(),
    "구독" to "📺" to emptyList(),
    "술·음료" to "🍺" to emptyList(),
    "미용" to "💇" to emptyList(),
    "경조사·선물" to "🎁" to emptyList(),
    "반려동물" to "🐶" to emptyList(),
    "기타" to "📦" to emptyList(),
)

private val defaultIncome = listOf("급여" to "💰", "용돈" to "🧧", "부수입" to "💵", "환급·이자" to "🏦", "기타수입" to "➕")

/** Called only inside [PocketDao.seedIfEmpty]'s transaction. */
internal suspend fun PocketDao.insertDefaults() {
    defaultExpense.forEachIndexed { i, (head, subs) ->
        val (name, emoji) = head
        val color = CategoryColors[i % CategoryColors.size]
        val id = upsert(Category(type = TxType.EXPENSE, name = name, emoji = emoji, color = color, sort = i))
        insertCategories(subs.mapIndexed { j, sub ->
            Category(type = TxType.EXPENSE, name = sub, emoji = emoji, color = color, parentId = id, sort = j)
        })
    }
    insertCategories(defaultIncome.mapIndexed { i, (name, emoji) ->
        Category(type = TxType.INCOME, name = name, emoji = emoji, color = CategoryColors[(i + 1) % CategoryColors.size], sort = i)
    })
    if (payMethodsOnce().isEmpty()) upsert(PayMethod(kind = PayKind.CASH, name = "현금"))
}
