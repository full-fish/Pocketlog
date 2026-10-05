package com.choimanseon.pocketlog

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.choimanseon.pocketlog.data.EmojiToIcon
import com.choimanseon.pocketlog.data.PocketDb
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.SubcategoriesToTags
import com.choimanseon.pocketlog.data.TxType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Phones and backups made by older versions keep their data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    /** An empty database exactly as schema [version] made it, plus [rows]. */
    private fun old(version: Int, vararg rows: String): String {
        val name = "v$version.db"
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/com.choimanseon.pocketlog.data.PocketDb/$version.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val sql = listOf(e.getString("createSql")) + e.optJSONArray("indices")?.let { ix -> List(ix.length()) { ix.getJSONObject(it).getString("createSql") } }.orEmpty()
                sql.forEach { db.execSQL(it.replace("\${TABLE_NAME}", e.getString("tableName"))) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            rows.forEach(db::execSQL)
            db.version = version
        }
        return name
    }

    private fun open(name: String) = Room.databaseBuilder(app, PocketDb::class.java, name).addMigrations(EmojiToIcon, SubcategoriesToTags).build()

    @Test
    fun emojiBecomeIconKeys() {
        val room = open(old(2, "INSERT INTO Category (type, name, emoji, color, sort, hidden) VALUES ('EXPENSE', '외식', '🍽️', 0, 0, 0), ('EXPENSE', '내 것', '🦄', 0, 1, 0)"))
        val icons = runBlocking { room.dao().categoriesOnce() }.filter { it.type == TxType.EXPENSE && it.parentId == null && !it.tagGroup }.map { it.icon }
        room.close()
        assertEquals(listOf("restaurant", "🦄"), icons) // unknown values stay and show the default icon
    }

    /** DB 6 (TODO #47): favorites made on DB 5 stay, in their old order. */
    @Test
    fun favoritesGetAnOrder() = runBlocking {
        val room = open(old(5, "INSERT INTO Favorite (type, amount, merchant, tags, memo, repeat, repeatDay, createdAt) VALUES ('EXPENSE', 8000, '김밥천국', '', '', 'MONTHLY', 25, 0)"))
        val f = room.dao().favorites().first().single()
        room.close()
        assertEquals(Triple("김밥천국", 25, 0), Triple(f.merchant, f.repeatDay, f.sort))
    }

    @Test
    fun subcategoriesBecomeTags() = runBlocking {
        val room = open(
            old(
                3,
                "INSERT INTO Category (id, type, name, emoji, color, parentId, sort, hidden) VALUES " +
                    "(1, 'EXPENSE', '식비', 'rice_bowl', 0, NULL, 0, 0), (2, 'EXPENSE', '점심', 'rice_bowl', 0, 1, 0, 0), " +
                    "(3, 'EXPENSE', '저축', 'savings', 0, NULL, 1, 0), (4, 'EXPENSE', '예금/적금', 'savings', 0, 3, 0, 0)",
                "INSERT INTO Tx (id, type, amount, currency, occurredAt, merchant, memo, categoryId, installmentMonths, status, source, excludeFromStats, createdAt, updatedAt) VALUES " +
                    "(1, 'EXPENSE', 8000, 'KRW', 0, '김밥천국', '', 2, 0, 'CONFIRMED', 'MANUAL', 0, 0, 0), " +
                    "(2, 'EXPENSE', 300000, 'KRW', 0, '적금', '', 4, 0, 'CONFIRMED', 'MANUAL', 0, 0, 0)",
                "INSERT INTO `Rule` (kind, pattern, value) VALUES ('CATEGORY', '김밥천국', '2')",
                "INSERT INTO Budget (categoryId, amount) VALUES (2, 100000), (1, 300000)",
            ),
        )
        val dao = room.dao()
        val txs = dao.txAround(-1, 1).associateBy { it.id }
        assertEquals(1L, txs.getValue(1).categoryId) // 점심 → 식비 #점심
        assertEquals(listOf(2L), dao.tagsOfOnce(1))
        assertEquals(TxType.SAVING to 3L, txs.getValue(2).type to txs.getValue(2).categoryId) // 저축 was spending in 똑똑가계부
        assertEquals(listOf(4L), dao.tagsOfOnce(2))
        assertEquals("1|2", dao.rulesOnce(RuleKind.CATEGORY).single().value)
        assertEquals(listOf(1L), dao.budgetsOnce().map { it.categoryId }) // no budget on a tag
        val cats = dao.categoriesOnce()
        assertEquals(listOf("저축", "투자"), cats.filter { it.type == TxType.SAVING && it.parentId == null }.map { it.name }) // 투자 added, 저축 kept
        val shared = cats.single { it.tagGroup }
        assertEquals(7, cats.count { it.parentId == shared.id })
        room.close()
    }
}
