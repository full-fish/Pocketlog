package com.choimanseon.pocketlog

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.choimanseon.pocketlog.data.EmojiToIcon
import com.choimanseon.pocketlog.data.PocketDb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Phones and backups made before version 3 keep their categories, with emoji turned into icon keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test
    fun emojiBecomeIconKeys() {
        val file = app.getDatabasePath("v2.db").apply { parentFile!!.mkdirs(); delete() }
        val v2 = JSONObject(File("schemas/com.choimanseon.pocketlog.data.PocketDb/2.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = v2.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val sql = listOf(e.getString("createSql")) + e.optJSONArray("indices")?.let { ix -> List(ix.length()) { ix.getJSONObject(it).getString("createSql") } }.orEmpty()
                sql.forEach { db.execSQL(it.replace("\${TABLE_NAME}", e.getString("tableName"))) }
            }
            val setup = v2.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO Category (type, name, emoji, color, sort, hidden) VALUES ('EXPENSE', '외식', '🍽️', 0, 0, 0), ('EXPENSE', '내 것', '🦄', 0, 1, 0)")
            db.version = 2
        }
        val room = Room.databaseBuilder(app, PocketDb::class.java, "v2.db").addMigrations(EmojiToIcon).build()
        val icons = runBlocking { room.dao().categoriesOnce() }.map { it.icon }
        room.close()
        assertEquals(listOf("restaurant", "🦄"), icons) // unknown values stay and show the default icon
    }
}
