package com.choimanseon.pocketlog

import android.database.sqlite.SQLiteDatabase
import com.choimanseon.pocketlog.data.ClevImport
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClevImportTest {
    private val dao get() = app.dao

    @Before
    fun ready() = runBlocking(Dispatchers.IO) {
        while (dao.categoriesOnce().isEmpty()) delay(20)
    }

    /** Same tables and columns as a real 똑똑가계부 backup, with made-up rows. */
    private fun fakeBackup(): File {
        val file = File.createTempFile("clev", ".db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE spendinglist (_id INTEGER PRIMARY KEY AUTOINCREMENT, s_date TEXT NOT NULL, s_time TEXT NOT NULL, s_where TEXT NOT NULL, s_memo TEXT NOT NULL, s_card TEXT NOT NULL, s_cate TEXT NOT NULL, s_subcate TEXT NOT NULL, s_price TEXT NOT NULL, s_cardmonth TEXT NOT NULL, s_ipter TEXT NOT NULL, s_oraw TEXT NOT NULL)",
                "CREATE TABLE earninglist (_id INTEGER PRIMARY KEY AUTOINCREMENT, e_date TEXT NOT NULL, e_where TEXT NOT NULL, e_memo TEXT NOT NULL, e_cate TEXT NOT NULL, e_price TEXT NOT NULL, e_time TEXT NOT NULL, e_card TEXT NOT NULL, e_subcate TEXT NOT NULL, e_ipter TEXT NOT NULL, e_oraw TEXT NOT NULL)",
                "CREATE TABLE catelist (_id INTEGER PRIMARY KEY AUTOINCREMENT, c_name TEXT NOT NULL, c_super TEXT NOT NULL, c_sort TEXT NOT NULL, c_attr TEXT NOT NULL)",
                "CREATE TABLE ecatelist (_id INTEGER PRIMARY KEY AUTOINCREMENT, ec_name TEXT NOT NULL, ec_sort TEXT NOT NULL, ec_super TEXT NOT NULL, ec_attr TEXT NOT NULL)",
                "CREATE TABLE cardlist (_id INTEGER PRIMARY KEY AUTOINCREMENT, d_name TEXT NOT NULL, d_aicode TEXT NOT NULL, d_aistr TEXT NOT NULL, d_attr TEXT NOT NULL, d_sort TEXT NOT NULL)",
                "INSERT INTO catelist VALUES (1,'식비','0','001',''),(2,'외식','1','001',''),(51,'과식','0','002',''),(52,'술값','51','001',''),(60,'저축','0','003',''),(61,'예금/적금','60','001','')",
                "INSERT INTO ecatelist VALUES (1,'근로소득','002','0',''),(2,'급여','001','1',''),(9,'용돈','001','0','')",
                "INSERT INTO cardlist VALUES (1,'카카오뱅크','KR/B/KAB','','','00002'),(11,'토스머니','KR/B/TSP','','','00007'),(16,'삼성카드','KR/C/SSC','','','09999'),(19,'KB국민체크카드','KR/C/KBD','','','09999'),(15,'친구랑 반반','','','','00011')",
                "INSERT INTO spendinglist VALUES (1,'2026-10-02','19:30','(주)데일리샷','','19','51','52','39900','1','x',''),(2,'2026-10-01','12:00','우리할매','','16','1','2','10000','3','',''),(3,'2026-09-30','12:00','우리할매','','16','1','2','9000','1','',''),(4,'2026-09-29','08:00','편의점','','0','0','0','0','8946','','')",
                "INSERT INTO spendinglist VALUES (10,'2026-01-05','11:19','데이터산업진흥원','','16','1','2','16668','3','',''),(11,'2026-02-50','02:03','데이터산업진흥원','','16','1','2','16666','1010','',''),(12,'2026-03-50','03:03','데이터산업진흥원','','16','1','2','16666','1010','',''),(20,'2026-10-03','09:00','적금 자동이체','','1','60','61','300000','1','','')",
                "INSERT INTO earninglist VALUES (1,'2026-09-25','회사','','1','3200000','09:00','1','2','',''),(2,'2026-09-28','K-패스 환급금 입금','','0','35100','09:28','11','0','','')",
            ).forEach(db::execSQL)
        }
        return file
    }

    @Test
    fun importsCategoriesCardsAndHistory() = runBlocking(Dispatchers.IO) {
        dao.insert(Rule(kind = RuleKind.BLOCK, pattern = "쿠팡", value = ""))
        val file = fakeBackup()
        assertEquals(8, ClevImport.peek(file).expenses)

        val summary = ClevImport.run(file)
        assertEquals(8, summary.expenses)
        assertEquals(2, summary.incomes)

        val cats = dao.categoriesOnce().associateBy { it.id }
        assertEquals(listOf("식비", "외식", "과식", "술값", "저축", "예금/적금", "근로소득", "급여", "용돈").sorted(), cats.values.map { it.name }.sorted())
        assertEquals(51L, cats.getValue(52).parentId) // 술값 is now a tag of 과식
        assertEquals(listOf(TxType.SAVING, TxType.SAVING), listOf(cats.getValue(60).type, cats.getValue(61).type)) // 저축 is savings, not spending
        assertEquals(TxType.INCOME, cats.getValue(1002).type)
        assertEquals(1001L, cats.getValue(1002).parentId)

        val pays = dao.payMethodsOnce().associateBy { it.name }
        assertEquals(PayKind.BANK, pays.getValue("카카오뱅크").kind)
        assertEquals("카카오뱅크", pays.getValue("카카오뱅크").issuer)
        assertEquals(PayKind.PAY_MONEY, pays.getValue("토스머니").kind)
        assertEquals(PayKind.CREDIT, pays.getValue("삼성카드").kind)
        assertEquals("삼성", pays.getValue("삼성카드").issuer)
        assertEquals(PayKind.CHECK, pays.getValue("KB국민체크카드").kind)
        assertEquals("KB국민", pays.getValue("KB국민체크카드").issuer)
        assertEquals(PayKind.CASH, pays.getValue("현금").kind) // added: the old app has no cash entry

        val txs = dao.txAround(0, Long.MAX_VALUE).associateBy { it.merchant + it.amount }
        val wine = txs.getValue("(주)데일리샷39900")
        assertEquals(51L, wine.categoryId) // the subcategory became a tag
        assertEquals(listOf(52L), dao.tagsOfOnce(wine.id))
        assertEquals(TxType.SAVING, txs.getValue("적금 자동이체300000").type)
        assertEquals(19L, wine.paymentMethodId)
        assertEquals(LocalDateTime.of(2026, 10, 2, 19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), wine.occurredAt)
        assertEquals(3, txs.getValue("우리할매10000").installmentMonths)
        assertEquals(0, txs.getValue("우리할매9000").installmentMonths) // "1" = 일시불
        assertNull(txs.getValue("편의점0").paymentMethodId) // card "0" = none
        assertNull(txs.getValue("편의점0").categoryId)
        assertEquals(0, txs.getValue("편의점0").installmentMonths) // garbage month value
        assertEquals(1001L, txs.getValue("회사3200000").categoryId)
        assertEquals(listOf(1002L), dao.tagsOfOnce(txs.getValue("회사3200000").id))
        assertNull(txs.getValue("K-패스 환급금 입금35100").categoryId)

        // 3-month installment: one row per month, months 2 and 3 land on the purchase day of their month
        val installments = dao.txAround(0, Long.MAX_VALUE).filter { it.merchant == "데이터산업진흥원" }.sortedBy { it.occurredAt }
        assertEquals(listOf("2026-01-05", "2026-02-05", "2026-03-05"), installments.map { it.occurredAt.toLocalDate().toString() })
        assertEquals(listOf("할부 1/3회차", "할부 2/3회차", "할부 3/3회차"), installments.map { it.memo })
        assertEquals(listOf(3, 3, 3), installments.map { it.installmentMonths })
        assertEquals(listOf(null, 10L, 10L), installments.map { it.installmentOf }) // months 2..3 point to the purchase row
        assertEquals(50_000L, installments.sumOf { it.amount })

        // merchants seen twice, mostly in one category → rules; one-off merchants don't get one; block rules survive the import
        assertEquals(setOf("우리할매" to "1|2", "데이터산업진흥원" to "1|2"), dao.rulesOnce(RuleKind.CATEGORY).map { it.pattern to it.value }.toSet())
        assertEquals(1, dao.rulesOnce(RuleKind.BLOCK).size)
    }

    /**
     * Run against a real backup to check nothing is lost:
     * CLEV_DB=/path/to/clevmoney_xxx.db ./gradlew testDebugUnitTest --tests '*ClevImportTest*'
     * Expected counts and totals are read from the backup itself (the old app's 자료통계 shows the same sums),
     * so nobody's real numbers live in this public repo.
     */
    @Test
    fun realBackupKeepsEveryRowAndWon() = runBlocking(Dispatchers.IO) {
        val path = System.getenv("CLEV_DB")
        assumeTrue(path != null && File(path).exists())
        val src = SQLiteDatabase.openDatabase(path!!, null, SQLiteDatabase.OPEN_READONLY)
        fun sums(sql: String) = src.rawQuery(sql, null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        val count = sums("SELECT 'spend', COUNT(*) FROM spendinglist UNION ALL SELECT 'earn', COUNT(*) FROM earninglist UNION ALL SELECT 'cate', (SELECT COUNT(*) FROM catelist) + (SELECT COUNT(*) FROM ecatelist)")
        val spentByYear = sums("SELECT substr(s_date, 1, 4), SUM(CAST(s_price AS INTEGER)) FROM spendinglist GROUP BY 1")
        val earned = sums("SELECT 'earn', SUM(CAST(e_price AS INTEGER)) FROM earninglist").getValue("earn")
        src.close()

        val summary = ClevImport.run(File(path))
        assertEquals(count.getValue("spend").toInt(), summary.expenses)
        assertEquals(count.getValue("earn").toInt(), summary.incomes)

        val txs = dao.txAround(0, Long.MAX_VALUE)
        assertEquals(summary.expenses + summary.incomes, txs.size)
        assertEquals(earned, total(txs, TxType.INCOME))
        val byYear = txs.groupBy { it.occurredAt.toLocalDate().year.toString() }.mapValues { (_, v) -> total(v, TxType.EXPENSE) + total(v, TxType.SAVING) }
        assertEquals(spentByYear, byYear)
        assertEquals(count.getValue("cate").toInt(), dao.categoriesOnce().size)
        println("rules created from history: ${summary.rules}")
    }
}
