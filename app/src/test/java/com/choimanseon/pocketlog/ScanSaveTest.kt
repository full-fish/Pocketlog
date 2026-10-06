package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.ai.Holds
import com.choimanseon.pocketlog.ai.OrderChoice
import com.choimanseon.pocketlog.ai.Scan
import com.choimanseon.pocketlog.ai.ScanItem
import com.choimanseon.pocketlog.ai.ScanOrder
import com.choimanseon.pocketlog.ai.ScanResult
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Screenshot review: what the user fixes by hand (names, 메모, tags) reaches the records, new or existing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanSaveTest {
    private val dao get() = app.dao
    private val day = LocalDate.of(2026, 10, 5)
    private val order = ScanOrder(
        day, null, "쿠팡", 15000, "KRW", null, "paid",
        listOf(ScanItem("휴지 30롤", 1, 10000, null), ScanItem("세재", 1, 5000, null)), 0, 0, 1.0,
    )

    private suspend fun fresh(): List<Category> {
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
        return dao.categoriesOnce()
    }

    private suspend fun save(choice: OrderChoice) {
        val job = ScanJob(imageHash = "t${System.nanoTime()}", imageCount = 1, status = ScanStatus.DONE).let { it.copy(id = dao.insert(it)) }
        Scan.save(job, ScanResult("coupang", listOf(order), emptyList()), listOf(choice), false)
    }

    private fun List<Category>.named(name: String) = first { it.name == name }

    @Test
    fun editedNamesMemoAndTagsAreSaved() = runBlocking(Dispatchers.IO) {
        val cats = fresh()
        val life = cats.named("생활").id
        val family = cats.named("가족").id // 공통 태그
        val laundry = cats.named("세탁·청소").id
        val dining = cats.named("외식").id // 식비's: can't sit on a 생활 record
        save(OrderChoice(
            true, null, listOf(life, life), 15000, null, names = listOf("", "세제 2L"), note = "이사 준비",
            tags = listOf(setOf(family, dining), setOf(laundry)),
        ))
        val tx = dao.txAround(0, Long.MAX_VALUE).single()
        assertEquals("휴지 30롤 외 1개" to "이사 준비", tx.memo to tx.note) // 품명 and 메모 apart
        assertEquals(listOf("휴지 30롤", "세제 2L"), dao.splitsOf(tx.id).first().map { it.name })
        assertEquals(setOf(family, laundry), dao.tagsOfOnce(tx.id).toSet())
    }

    @Test
    fun itemsGoIntoAnExistingRecord() = runBlocking(Dispatchers.IO) {
        val cats = fresh()
        val life = cats.named("생활").id
        val family = cats.named("가족").id
        val at = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // the card SMS: amount and merchant, no items
        val sms = dao.insert(Tx(amount = 15000, occurredAt = at, merchant = "쿠팡", categoryId = cats.named("쇼핑").id))
        suspend fun holds(names: List<String>, tags: Set<Long>): Holds {
            val tx = dao.txAround(0, Long.MAX_VALUE).single { it.id == sms }
            return Scan.holds(tx, dao.splitsOf(sms).first(), dao.tagsOfOnce(sms), Scan.splitsFor(Scan.named(order, names), listOf(life, life), 15000), tags)
        }
        assertEquals(Holds.MISSING, holds(emptyList(), setOf(family)))

        // 품목 넣기: items, category and tags go in, amount and merchant stay
        save(OrderChoice(true, null, listOf(life, life), 15000, Scan.duplicateOf(order), tags = listOf(setOf(family), emptySet())))
        var tx = dao.txAround(0, Long.MAX_VALUE).single()
        assertEquals(listOf("휴지 30롤", "세재"), dao.splitsOf(sms).first().map { it.name })
        assertEquals(15000L, tx.amount)
        assertEquals(life, tx.categoryId)
        assertEquals("휴지 30롤 외 1개", tx.memo)
        assertEquals(listOf(family), dao.tagsOfOnce(sms))
        // the same screenshot again: nothing new to put in; a fixed name: something to replace
        assertEquals(Holds.NOTHING_NEW, holds(emptyList(), setOf(family)))
        val fixed = listOf("휴지 30롤 3겹", "세제 2L")
        assertEquals(Holds.DIFFERENT, holds(fixed, setOf(family)))

        // 품목 바꾸기: the 품명 the first scan wrote follows the new names, the 메모 goes in
        save(OrderChoice(true, null, listOf(life, life), 15000, Scan.duplicateOf(order), names = fixed, note = "이사"))
        tx = dao.txAround(0, Long.MAX_VALUE).single()
        assertEquals(fixed, dao.splitsOf(sms).first().map { it.name })
        assertEquals("휴지 30롤 3겹 외 1개" to "이사", tx.memo to tx.note)
        assertEquals(listOf(family), dao.tagsOfOnce(sms))
    }
}
