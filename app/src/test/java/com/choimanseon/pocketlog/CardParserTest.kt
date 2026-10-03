package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.auto.CardParser
import com.choimanseon.pocketlog.auto.MsgKind
import com.choimanseon.pocketlog.auto.Parsed
import com.choimanseon.pocketlog.data.PayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Sample messages modeled on real Korean card / bank formats. Add a case here whenever a real message fails. */
class CardParserTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val post = LocalDateTime.of(2026, 10, 2, 21, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(month: Int, day: Int, h: Int, m: Int, year: Int = 2026) =
        LocalDateTime.of(year, month, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun parse(body: String, title: String = "15881688"): Parsed =
        assertNotNullAndGet(CardParser.parse(title, body, post, zone))

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }

    @Test fun samsungCard() {
        val p = parse("[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불\n10/02 19:43 주식회사앨리스프랜즈\n누적1,234,560원")
        assertEquals(MsgKind.SPEND, p.kind)
        assertEquals(12300L, p.amount)
        assertEquals("주식회사앨리스프랜즈", p.merchant)
        assertEquals("삼성", p.issuer)
        assertEquals("1234", p.last4)
        assertEquals(PayKind.CREDIT, p.payKind)
        assertEquals("삼성카드", p.payName)
        assertEquals(at(10, 2, 19, 43), p.at)
    }

    @Test fun kbCheckCard() {
        val p = parse("[Web발신]\nKB국민체크(1234)\n홍*동님\n10/02 19:30\n39,900원\n(주)데일리샷 사용")
        assertEquals(39900L, p.amount)
        assertEquals("(주)데일리샷", p.merchant)
        assertEquals("KB국민", p.issuer)
        assertEquals(PayKind.CHECK, p.payKind)
        assertEquals("KB국민체크", p.payName)
    }

    @Test fun shinhanSingleLine() {
        val p = parse("[Web발신]\n신한카드(1234)승인 홍*동 45,000원(일시불)10/02 12:30 스타벅스코리아 누적1,234,560원")
        assertEquals(45000L, p.amount)
        assertEquals("스타벅스코리아", p.merchant)
        assertEquals("신한", p.issuer)
        assertEquals(at(10, 2, 12, 30), p.at)
    }

    @Test fun hyundaiCardProductLetter() {
        val p = parse("[Web발신]\n현대카드 M 승인\n홍*동\n8,000원 일시불\n10/02 12:10\n김밥천국\n누적123,000원")
        assertEquals(8000L, p.amount)
        assertEquals("김밥천국", p.merchant)
        assertEquals("현대", p.issuer)
    }

    @Test fun hanaMaskedLast4AndInstallment() {
        val p = parse("[Web발신]\nNH카드1*2*승인\n홍*동\n33,000원 3개월\n10/02 20:15\n쿠팡\n총누적567,000원")
        assertEquals(33000L, p.amount)
        assertEquals(3, p.installment)
        assertEquals("쿠팡", p.merchant)
        assertEquals("NH농협", p.issuer)
        assertNull(p.last4)
    }

    @Test fun merchantBeforeTime() {
        val p = parse("[Web발신]\nBC카드(1234)승인\n홍*동\n12,000원 일시불\n스타벅스\n10/02 14:00")
        assertEquals("스타벅스", p.merchant)
    }

    @Test fun cancel() {
        val p = parse("[Web발신]\n삼성1234승인취소 홍*동\n12,300원 일시불\n10/02 20:01 주식회사앨리스프랜즈")
        assertEquals(MsgKind.CANCEL, p.kind)
        assertEquals(12300L, p.amount)
        assertEquals("주식회사앨리스프랜즈", p.merchant)
    }

    @Test fun overseasHasNoWonAmount() {
        val p = parse("[Web발신]\n신한카드(1234)해외승인 홍*동 USD 12.99 10/02 03:15 NETFLIX.COM")
        assertNull(p.amount)
        assertEquals("USD 12.99", p.foreign)
        assertEquals("NETFLIX.COM", p.merchant)
    }

    @Test fun bankWithdrawalWithoutWon() {
        val p = parse("[Web발신]\n[KB]10/01 09:00\n123456**789\n삼성카드\n출금  523,000\n잔액  1,000,000")
        assertEquals(MsgKind.WITHDRAW, p.kind)
        assertEquals(523000L, p.amount)
        assertEquals("삼성카드", p.merchant)
        assertEquals(1_000_000L, p.balance)
        assertEquals(PayKind.BANK, p.payKind)
        assertEquals("KB국민은행", p.payName)
    }

    @Test fun kakaoBankPushWithdrawal() {
        val p = assertNotNullAndGet(CardParser.parse("카카오뱅크", "출금 50,000원 쿠팡페이 잔액 1,234,567원", post, zone))
        assertEquals(MsgKind.WITHDRAW, p.kind)
        assertEquals(50000L, p.amount)
        assertEquals("쿠팡페이", p.merchant)
        assertEquals(1_234_567L, p.balance)
        assertEquals("카카오뱅크", p.payName)
    }

    @Test fun kakaoBankDeposit() {
        val p = parse("[카카오뱅크] 입금 3,200,000원 (주)회사이름 급여 잔액 4,434,567원")
        assertEquals(MsgKind.DEPOSIT, p.kind)
        assertEquals(3_200_000L, p.amount)
        assertEquals("(주)회사이름 급여", p.merchant)
    }

    @Test fun tossPush() {
        val p = assertNotNullAndGet(CardParser.parse("토스", "ezl 지하철 1건 이용 1,550원 결제 완료", post, zone))
        assertEquals(1550L, p.amount)
        assertEquals("ezl 지하철 1건 이용", p.merchant)
        assertEquals(PayKind.PAY_MONEY, p.payKind)
        assertNull(p.at)
    }

    @Test fun kakaoTalkAlimtalkLabels() {
        val p = assertNotNullAndGet(CardParser.parse(
            "KB국민카드",
            "[KB국민카드] 승인 안내\n홍*동님\n카드: 1234\n금액: 39,900원\n일시: 10/02 19:30\n가맹점: (주)데일리샷\n누적금액: 1,234,000원",
            post, zone,
        ))
        assertEquals(39900L, p.amount)
        assertEquals("(주)데일리샷", p.merchant)
    }

    @Test fun yearRollsBackInJanuary() {
        val jan = LocalDateTime.of(2027, 1, 1, 0, 5).atZone(zone).toInstant().toEpochMilli()
        val p = assertNotNullAndGet(CardParser.parse("", "[Web발신]\n삼성1234승인 홍*동\n5,000원 일시불\n12/31 23:59 편의점", jan, zone))
        assertEquals(at(12, 31, 23, 59, year = 2026), p.at)
    }

    @Test fun privacyGate() {
        val sms = "com.samsung.android.messaging"
        assertTrue(CardParser.looksFinancial(sms, "15881688", "[Web발신]\n삼성1234승인 홍*동\n12,300원 일시불"))
        assertFalse(CardParser.looksFinancial(sms, "엄마", "삼성 갤럭시 150,000원 결제했어"))
        assertFalse(CardParser.looksFinancial("com.kakao.talk", "친구", "치킨 20,000원 결제함"))
        assertTrue(CardParser.looksFinancial("com.kakao.talk", "KB국민카드", "승인 39,900원"))
        assertFalse(CardParser.looksFinancial(sms, "15881688", "(광고)[Web발신] 삼성카드 결제하면 5,000원 할인"))
    }
}
