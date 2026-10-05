package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.data.ClevImport
import com.choimanseon.pocketlog.data.PocketDb
import com.choimanseon.pocketlog.data.TxTag
import com.choimanseon.pocketlog.data.defaultSeeds
import com.choimanseon.pocketlog.data.plant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A 똑똑가계부 backup → a Pocketlog data file with the TODO #27 categories and tags, for
 * 설정 → 백업 · 복구 → 백업 파일에서 복구 (TODO #29). Runs only when given the files:
 *
 *   CLEV_DB=…/clev-cleaned.db CLEV_MAP=…/clev-mapping.json CLEV_OUT=…/pocketlog-clev.db \
 *     ./gradlew :app:testDebugUnitTest --tests '*ClevConvertTest*'
 *
 * CLEV_MAP is a JSON array of {kind: "E"|"I", old: [category, subcategory or "-"], merchant, category, tags},
 * one per merchant of each old category; a tag starting with "+" is a shared one (공통 태그).
 * The mapping and the data stay on the computer (private/), out of this public repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClevConvertTest {
    private val dao get() = app.dao

    @Test
    fun convert() = runBlocking(Dispatchers.IO) {
        val (src, map, out) = listOf("CLEV_DB", "CLEV_MAP", "CLEV_OUT").map { System.getenv(it) }
        assumeTrue(src != null && map != null && out != null)
        while (dao.categoriesOnce().isEmpty()) delay(20)
        ClevImport.run(File(src!!)) // the old categories, their subcategories as tags

        val old = dao.categoriesOnce().associateBy { it.id }
        val db = app.db.openHelper.writableDatabase
        val oldSub = db.query("SELECT txId, tagId FROM TxTag").use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) } }
        val target = JSONArray(File(map!!).readText()).let { a ->
            (0 until a.length()).associate { i ->
                val e = a.getJSONObject(i)
                val o = e.getJSONArray("old")
                listOf(e.getString("kind"), o.getString(0), o.getString(1), e.getString("merchant")).joinToString("|") to
                    (e.optString("category").takeIf { !e.isNull("category") } to e.getJSONArray("tags").let { t -> List(t.length()) { t.getString(it) } })
            }
        }
        val txs = dao.txAround(Long.MIN_VALUE, Long.MAX_VALUE)
        fun key(tx: com.choimanseon.pocketlog.data.Tx) = listOf(
            if (tx.type == com.choimanseon.pocketlog.data.TxType.INCOME) "I" else "E",
            tx.categoryId?.let { old[it]?.name } ?: "(미분류)",
            oldSub[tx.id]?.let { old[it]?.name } ?: "-",
            tx.merchant,
        ).joinToString("|")
        val missing = txs.filter { key(it) !in target }
        assertEquals("rows with no mapping: ${missing.take(5).map(::key)}", 0, missing.size)

        // the TODO #27 categories replace the old ones
        db.execSQL("DELETE FROM TxTag")
        db.execSQL("DELETE FROM Category")
        db.execSQL("DELETE FROM `Rule` WHERE kind = 'CATEGORY'")
        plant(defaultSeeds) { dao.upsert(it) }
        val cats = dao.categoriesOnce()
        val tops = cats.filter { it.parentId == null && !it.tagGroup }.associateBy { it.name }
        val tags = mutableListOf<TxTag>()
        val mapped = txs.map { tx ->
            val (name, tagNames) = target.getValue(key(tx))
            val top = name?.let { tops[it] ?: error("no category $it") }
            val shared = cats.firstOrNull { it.tagGroup && it.type == top?.type }
            tagNames.forEach { t ->
                val owner = if (t.startsWith("+")) shared else top
                tags += TxTag(tx.id, cats.firstOrNull { it.parentId == owner?.id && it.name == t.removePrefix("+") }?.id ?: error("no tag $t under ${owner?.name}"))
            }
            tx.copy(type = top?.type ?: tx.type, categoryId = top?.id)
        }
        mapped.forEach { dao.update(it) }
        dao.insertTags(tags)
        db.execSQL("UPDATE Category SET hidden = 0 WHERE hidden = 1 AND id IN (SELECT categoryId FROM Tx)") // e.g. 반려동물 for the parrot
        dao.insertRules(ClevImport.rulesFrom(mapped, tags, cats))

        db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        app.getDatabasePath(PocketDb.NAME).copyTo(File(out!!), overwrite = true)
        val byType = mapped.groupBy { it.type }.mapValues { (_, v) -> v.size }
        println("converted ${mapped.size} rows $byType, ${tags.size} tags, uncategorized ${mapped.count { it.categoryId == null }} → $out")
    }
}
