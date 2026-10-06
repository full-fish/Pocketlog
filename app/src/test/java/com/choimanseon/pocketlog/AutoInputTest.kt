package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.auto.AutoInput
import com.choimanseon.pocketlog.auto.Categorizer
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** The whole notification → transaction pipeline against a real Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoInputTest {
    private val sms = "com.samsung.android.messaging"
    private val dao get() = app.dao
    private val post = LocalDateTime.now().withSecond(0).withNano(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val mmdd = LocalDateTime.now().let { "%02d/%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

    @Before
    fun clean() = runBlocking(Dispatchers.IO) {
        while (dao.categoriesOnce().isEmpty()) delay(20) // App.onCreate seeds asynchronously
        app.db.clearAllTables()
        dao.seedIfEmpty()
        app.prefs.myName = ""
    }

    private fun receive(body: String, title: String = "15881688", pkg: String = sms, at: Long = post) =
        runBlocking { AutoInput.handle(pkg, title, body, at) }

    private fun txs(): List<Tx> = runBlocking { dao.txAround(0, Long.MAX_VALUE) }

    @Test
    fun cardSmsBecomesExpenseWithAutoCreatedCard() {
        receive("[Web발신]\n삼성1234승인 홍*동\n4,500원 일시불\n$mmdd 스타벅스코리아\n누적1,234,560원")
        val tx = txs().single()
        assertEquals(TxType.EXPENSE, tx.type)
        assertEquals(4500L, tx.amount)
        assertEquals("스타벅스코리아", tx.merchant)
        val pay = runBlocking { dao.payMethodsOnce() }.first { it.id == tx.paymentMethodId }
        assertEquals("삼성카드(1234)", pay.name)
        val cats = runBlocking { dao.categoriesOnce() }.associateBy { it.id }
        assertEquals("카페·간식", cats.getValue(tx.categoryId!!).name)
        assertEquals(listOf("커피"), runBlocking { dao.tagsOfOnce(tx.id) }.map { cats.getValue(it).name }) // the dictionary tags it too
    }

    @Test
    fun transferToSavingsIsSaving() {
        app.prefs.myName = "홍길동"
        receive("출금 300,000원 홍길동 적금 잔액 1,000,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel")
        // the savings account's own notice of the same money is the other half, not a second entry
        receive("입금 300,000원 홍길동 적금 잔액 2,300,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel", at = post + 20_000)
        val tx = txs().single()
        assertEquals(TxType.SAVING to 300_000L, tx.type to tx.amount)
        val cats = runBlocking { dao.categoriesOnce() }.associateBy { it.id }
        assertEquals("저축", cats.getValue(tx.categoryId!!).name)
        assertEquals(listOf("적금"), runBlocking { dao.tagsOfOnce(tx.id) }.map { cats.getValue(it).name })
    }

    /** TODO #35: a foreign-only payment is recorded in won at the day's rate (cached here, so no network), and its cancel finds it. */
    @Test
    fun foreignOnlyPaymentIsConvertedAndCancelable() {
        app.prefs.fxRates = "${java.time.LocalDate.now()}|{\"USD\":1.0,\"KRW\":1350.0}"
        receive("[Web발신]\n신한카드(1234)해외승인 홍*동 USD 12.99 $mmdd NETFLIX.COM")
        val tx = txs().single()
        assertEquals(17_537L to TxStatus.CONFIRMED, tx.amount to tx.status) // 12.99 × 1,350
        assertEquals("USD 12.99", tx.originalAmount)
        app.prefs.fxRates = "${java.time.LocalDate.now()}|{\"USD\":1.0,\"KRW\":1360.0}" // the cancel comes at another rate
        receive("[Web발신]\n신한카드(1234)해외승인취소 홍*동 USD 12.99 $mmdd NETFLIX.COM", at = post + 60_000)
        assertEquals(TxStatus.CANCELED, txs().single().status)
    }

    @Test
    fun sameAmountFromCardAppPushIsADuplicate() {
        receive("[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불\n$mmdd 주식회사앨리스프랜즈")
        receive("12,300원 일시불 승인\n주식회사앨리스프랜즈", title = "삼성카드", pkg = "kr.co.samsungcard.mpocket", at = post + 30_000)
        assertEquals(1, txs().size)
    }

    /** TODO #48: 인형뽑기방 1,000원 at 13:57 and again at 13:59 were merged into one. */
    @Test
    fun twoPaymentsAtOneShopMinutesApartAreBothRecorded() {
        val later = LocalDateTime.now().plusMinutes(2).let { "%02d/%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
        receive("[Web발신] KB국민체크1234\n승인\n1,000원\n인형뽑기방\n고객명 홍*동님\n승인시각 $mmdd", title = "KB국민카드")
        receive("[Web발신] KB국민체크1234\n승인\n1,000원\n인형뽑기방\n고객명 홍*동님\n승인시각 $later", title = "KB국민카드", at = post + 120_000)
        assertEquals(2, txs().size)
        receive("[Web발신] KB국민체크1234\n승인\n1,000원\n인형뽑기방\n고객명 홍*동님\n승인시각 $later", title = "KB국민카드", at = post + 120_000)
        assertEquals(2, txs().size) // the very same text again (re-posted, or read again by catchUp) is skipped
    }

    @Test
    fun cancelMarksTheOriginal() {
        receive("[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불\n$mmdd 주식회사앨리스프랜즈")
        receive("[Web발신]\n삼성1234승인취소 홍*동\n12,300원 일시불\n$mmdd 주식회사앨리스프랜즈", at = post + 60_000)
        assertEquals(TxStatus.CANCELED, txs().single().status)
        receive("12,300원 승인취소\n주식회사앨리스프랜즈", title = "삼성카드", pkg = "kr.co.samsungcard.mpocket", at = post + 90_000)
        assertEquals(1, txs().size) // the push for the same cancel adds no refund
    }

    // 할부 is one row per month (사용자 결정 2026-10-03)

    @Test
    fun installmentPurchaseIsOneRowPerMonth() {
        receive("[Web발신]\n삼성1234승인 홍*동\n52,000원 03개월\n$mmdd 이마트 역삼점")
        val rows = txs().sortedBy { it.occurredAt }
        assertEquals(listOf(17_334L, 17_333L, 17_333L), rows.map { it.amount })
        assertEquals(listOf(null, rows[0].id, rows[0].id), rows.map { it.installmentOf })
        // the card app's push for the same payment is still a duplicate, and a cancel cancels every month
        receive("52,000원 3개월 승인\n이마트 역삼점", title = "삼성카드", pkg = "kr.co.samsungcard.mpocket", at = post + 30_000)
        assertEquals(3, txs().size)
        receive("[Web발신]\n삼성1234승인취소 홍*동\n52,000원 03개월\n$mmdd 이마트 역삼점", at = post + 60_000)
        assertEquals(List(3) { TxStatus.CANCELED }, txs().map { it.status })
    }

    @Test
    fun installmentMonthsAreEditedAndDeletedTogether() = runBlocking(Dispatchers.IO) {
        val first = dao.insertPurchase(Tx(amount = 90_000, occurredAt = post, merchant = "노트북", installmentMonths = 3))
        dao.setCategory(txs().maxBy { it.occurredAt }.id, 1) // from any month
        assertEquals(listOf(1L, 1L, 1L), txs().map { it.categoryId })
        dao.replacePurchase(dao.txOnce(first)!!.copy(amount = 120_000, installmentMonths = 2))
        assertEquals(listOf(60_000L, 60_000L), txs().sortedBy { it.occurredAt }.map { it.amount })
        dao.softDelete(txs().maxBy { it.occurredAt }.id)
        assertTrue(txs().isEmpty())
        dao.undoDelete(first)
        assertEquals(2, txs().size)
    }

    // Only money that enters or leaves "me" is recorded (사용자 결정 2026-10-03)

    @Test
    fun payMoneyTopUpIsNotRecorded() {
        receive("출금 50,000원 쿠팡페이 잔액 1,234,567원", title = "카카오뱅크", pkg = "com.kakaobank.channel")
        assertTrue(txs().isEmpty())
        // the balance in the message is still useful
        assertEquals(1_234_567L, runBlocking { dao.payMethodsOnce() }.single { it.kind == PayKind.BANK }.balance)
    }

    @Test
    fun transitCardTopUpIsSpending() {
        receive("[Web발신]\n삼성1234승인 홍*동\n10,000원 일시불\n$mmdd 모바일티머니 충전")
        assertEquals(10_000L, txs().single { it.type == TxType.EXPENSE }.amount)
    }

    @Test
    fun cardBillIsNotRecorded() {
        receive("[Web발신]\n[KB]$mmdd\n123456**789\n삼성카드\n출금  523,000\n잔액  1,000,000")
        assertTrue(txs().isEmpty())
    }

    @Test
    fun cardStatementNoticesAreNotRecorded() {
        // the card company's own notice of what the month's purchases add up to; each purchase came as its own 승인 message
        receive("[삼성카드]10/13결제금액 887,679원 (10/13출금,10/02기준)\nhttp://q.samsungcard.com/3erCTeE", title = "삼성카드", pkg = "com.kakao.talk")
        receive("[Web발신]\n[신한카드] 홍*동님 10월 결제예정금액 512,300원 (10/14 출금예정)", at = post + 60_000)
        receive("[현대카드] 10월 청구금액 412,300원 결제일 10/25", title = "현대카드", pkg = "com.kakao.talk", at = post + 120_000)
        receive("[Web발신]\n[KB국민카드] 10월 이용대금 300,000원 10/25 출금", at = post + 180_000)
        assertTrue(txs().isEmpty())
        // a purchase always shows its time, so it is still recorded
        receive("[Web발신]\n삼성1234승인 홍*동\n18,600원 일시불\n$mmdd 씨유(CU)한양대사\n누적1,279,281원", at = post + 240_000)
        assertEquals(18_600L, txs().single().amount)
    }

    @Test
    fun moneyBetweenMyAccountsIsNotRecorded() {
        receive("출금 100,000원 김철수 잔액 900,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel")
        assertEquals(1, txs().size) // looks like paying someone, until the other half arrives
        receive("[Web발신]\n[KB]$mmdd\n123456**789\n김철수\n입금  100,000\n잔액  2,000,000", at = post + 20_000)
        assertTrue(txs().isEmpty())
    }

    @Test
    fun transfersWithMyNameAreNotRecorded() {
        app.prefs.myName = "홍길동"
        receive("출금 30,000원 홍길동 잔액 900,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel")
        receive("입금 20,000원 홍길동 잔액 920,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel", at = post + 60_000)
        assertTrue(txs().isEmpty())
        receive("출금 15,000원 한전(홍길동) 잔액 905,000원", title = "카카오뱅크", pkg = "com.kakaobank.channel", at = post + 120_000)
        assertEquals("한전(홍길동)", txs().single().merchant) // a bill paid in my name is real spending
    }

    @Test
    fun taxiPreAuthorizationIsNotRecordedButTheFareIs() {
        receive("[Web발신]\n삼성1234승인 홍*동\n15,000원 일시불\n$mmdd 카카오T택시_가승인")
        receive("[Web발신]\n삼성1234승인취소 홍*동\n15,000원 일시불\n$mmdd 카카오T택시_가승인", at = post + 600_000)
        assertTrue(txs().isEmpty())
        receive("[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불\n$mmdd 카카오T일반택시_0", at = post + 610_000)
        assertEquals(12_300L, txs().single().amount)
    }

    @Test
    fun salaryIsIncome() {
        receive("입금 3,200,000원 (주)회사이름 급여 잔액 4,434,567원", title = "카카오뱅크", pkg = "com.kakaobank.channel")
        val tx = txs().single()
        assertEquals(TxType.INCOME, tx.type)
        assertEquals("급여", runBlocking { dao.categoriesOnce() }.first { it.id == tx.categoryId }.name)
    }

    @Test
    fun personalMessagesAreNeverStored() {
        receive("어제 치킨 20,000원 결제했어 ㅋㅋ", title = "친구", pkg = "com.kakao.talk")
        receive("삼성 갤럭시 150,000원 결제함", title = "엄마")
        assertTrue(txs().isEmpty())
        assertEquals(0, runBlocking { dao.countSameBody("삼성 갤럭시 150,000원 결제함", 0) })
    }

    @Test
    fun rulesLearnBrandsAndTheLongestWins() = runBlocking {
        val cats = dao.categoriesOnce()
        fun id(name: String) = cats.first { it.name == name && it.parentId == null }.id
        fun tag(name: String) = cats.first { it.name == name && it.parentId != null }.id
        fun rules() = runBlocking { dao.rulesOnce(RuleKind.CATEGORY) }
        Categorizer.learn("스타벅스 김포점", Pick(id("카페·간식"), listOf(tag("커피"))))
        assertEquals(listOf("스타벅스"), rules().map { it.pattern })
        assertEquals(Pick(id("카페·간식"), listOf(tag("커피"))), Categorizer.fromRules("스타벅스 광진점", rules()))
        Categorizer.learn("스타벅스 광진점", Pick(id("카페·간식"), listOf(tag("커피")))) // already covered: nothing new
        assertEquals(1, rules().size)
        Categorizer.learn("스타벅스 강남R점", Pick(id("식비"))) // disagrees with the brand: only this branch
        assertEquals(id("식비"), Categorizer.fromRules("스타벅스 강남R점", rules())?.category)
        assertEquals("스타벅스", Categorizer.brandOf("스타벅스(김포점)"))
        assertEquals(id("카페·간식"), Categorizer.fromRules("스타벅스 광진점", rules())?.category)

        dao.putRule(RuleKind.CATEGORY, "쿠팡이츠", Pick(id("식비"), listOf(tag("야식"))).encode())
        dao.putRule(RuleKind.CATEGORY, "쿠팡", Pick(id("쇼핑")).encode()) // newer but shorter
        assertEquals(Pick(id("식비"), listOf(tag("야식"))), Categorizer.fromRules("쿠팡이츠 결제", rules()))
        assertEquals("롯데백화점", Categorizer.brandOf("롯데 백화점")) // "롯데" alone would match too much
    }

    @Test
    fun blockRuleSkipsMessage() {
        runBlocking { dao.insert(Rule(kind = RuleKind.BLOCK, pattern = "토스", value = "")) }
        receive("ezl 지하철 1건 이용 1,550원 결제 완료", title = "토스", pkg = "viva.republica.toss")
        assertTrue(txs().isEmpty())
    }

    @Test
    fun smsConversationIsReadMessageByMessage() {
        val bank = androidx.core.app.Person.Builder().setName("15881688").build()
        val style = androidx.core.app.NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("나").build())
            .addMessage("[Web발신]\n삼성1234승인 홍*동\n9,900원 일시불\n어제 문자", post - 3_600_000, bank) // already handled an hour ago
            .addMessage("[Web발신]\n삼성1234승인 홍*동\n4,500원 일시불\n$mmdd 스타벅스코리아", post - 1_000, bank)
            .addMessage("[Web발신]\n삼성1234승인 홍*동\n8,000원 일시불\n$mmdd 김밥천국", post, bank)
        val n = androidx.core.app.NotificationCompat.Builder(app, Notify.CH_SAVED).setSmallIcon(R.drawable.ic_notify)
            .setContentText("새 메시지 2개").setStyle(style).build()
        val messages = com.choimanseon.pocketlog.auto.incomingMessages(n, post)
        assertEquals(2, messages.size)
        messages.forEach { receive(it.body, it.title, at = it.time) }
        assertEquals(setOf("스타벅스코리아", "김밥천국"), txs().map { it.merchant }.toSet())
    }

    @Test
    fun unreadableMessageWaitsForReview() {
        receive("[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불", at = post)
        assertTrue(txs().isEmpty() || txs().single().status == TxStatus.PENDING_REVIEW)
    }
}
