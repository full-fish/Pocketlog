package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.Favorite
import com.choimanseon.pocketlog.data.Repeat
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 반복 기록: a 즐겨찾기 that records itself every week or month at 9:00 on its day. */
object Repeats {
    private val AT = LocalTime.of(9, 0)

    /** The first repeat day strictly after [date]. A monthly day past the month's end is that month's last day (31 → 2/28). */
    fun nextDate(repeat: Repeat, day: Int, date: LocalDate): LocalDate = when (repeat) {
        Repeat.WEEKLY -> date.with(TemporalAdjusters.next(DayOfWeek.of(day.coerceIn(1, 7))))
        Repeat.MONTHLY -> {
            fun on(m: YearMonth) = m.atDay(minOf(day, m.lengthOfMonth()))
            on(YearMonth.from(date)).takeIf { it.isAfter(date) } ?: on(YearMonth.from(date).plusMonths(1))
        }
        Repeat.NONE -> error("not repeating")
    }

    /** When a favorite set to repeat now records first: its next day after today, so turning it on never records twice today. */
    fun firstAt(repeat: Repeat, day: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long? =
        if (repeat == Repeat.NONE) null else nextDate(repeat, day, today).atTime(AT).atZone(zone).toInstant().toEpochMilli()

    /** Records every run that is due, also the ones missed while the phone was off (at most a year of them). Returns how many. */
    suspend fun runDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val dao = app.dao
        val cats = dao.categoriesOnce().associateBy { it.id }
        var count = 0
        for (f in dao.dueFavorites(now)) {
            var at = f.nextAt ?: continue
            var runs = 0
            while (at <= now && runs < 60) {
                val tx = record(f, at, cats)
                Notify.saved(tx, f.categoryId?.let { cats[it] }, needsReview = false)
                at = nextDate(f.repeat, f.repeatDay, Instant.ofEpochMilli(at).atZone(zone).toLocalDate()).atTime(AT).atZone(zone).toInstant().toEpochMilli()
                runs++
            }
            dao.upsert(f.copy(nextAt = at))
            count += runs
        }
        if (count > 0) AutoInput.checkBudget()
        return count
    }

    /** A deleted category or tag since the favorite was made is left out. */
    private suspend fun record(f: Favorite, at: Long, cats: Map<Long, Category>): Tx {
        val dao = app.dao
        val tx = Tx(
            type = f.type, amount = f.amount, occurredAt = at, merchant = f.merchant, memo = f.memo,
            categoryId = f.categoryId?.takeIf { it in cats }, paymentMethodId = f.paymentMethodId,
            source = if (f.dummy) TxSource.DUMMY else TxSource.REPEAT, // a dummy favorite's records go away with the dummy data
        )
        val id = dao.insertPurchase(tx)
        dao.setTags(id, f.tags.split(',').mapNotNull { it.toLongOrNull() }.filter { it in cats })
        return tx.copy(id = id)
    }
}
