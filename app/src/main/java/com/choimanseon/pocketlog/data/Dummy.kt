package com.choimanseon.pocketlog.data

import com.choimanseon.pocketlog.app
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 설정 → 더미 데이터 넣기 (TODO #13): five years of made-up spending and income so every screen has something to show.
 * Uses whatever categories exist (default or imported) through their icons, and the existing payment methods.
 * Every row is [TxSource.DUMMY], so it can be removed without touching real records.
 */
object Dummy {
    private class Kind(val merchants: List<String>, val min: Int, val max: Int, val perMonth: Double, val hours: IntRange = 8..21)

    private val kinds: Map<String, Kind> = buildMap {
        fun add(icons: String, k: Kind) = icons.split(' ').forEach { put(it, k) }
        add("rice_bowl dinner egg", Kind(listOf("김밥천국", "한솥도시락", "본죽", "맘스터치", "서브웨이", "국밥집"), 6_000, 15_000, 16.0, 11..20))
        add("restaurant fastfood", Kind(listOf("교촌치킨", "명동교자", "스시로", "쌀국수 포베이", "버거킹"), 12_000, 45_000, 5.0, 12..21))
        add("cafe bakery cookie", Kind(listOf("스타벅스", "메가MGC커피", "이디야커피", "투썸플레이스", "파리바게뜨"), 2_000, 8_000, 14.0, 8..17))
        add("delivery", Kind(listOf("배달의민족", "쿠팡이츠", "요기요"), 14_000, 32_000, 5.0, 18..22))
        add("convenience", Kind(listOf("GS25", "CU", "세븐일레븐"), 1_500, 9_000, 8.0, 7..23))
        add("grocery cart kitchen", Kind(listOf("이마트", "홈플러스", "컬리", "노브랜드"), 15_000, 90_000, 3.0, 10..20))
        add("bag clothes", Kind(listOf("쿠팡", "무신사", "11번가", "지그재그"), 9_900, 79_000, 3.0))
        add("laptop phone chair", Kind(listOf("쿠팡", "하이마트", "오늘의집"), 20_000, 250_000, 0.4))
        add("soap laundry handyman", Kind(listOf("다이소", "올리브영", "크린토피아"), 3_000, 25_000, 2.0))
        add("subway bus taxi train bike", Kind(listOf("지하철", "버스", "카카오T 택시", "코레일"), 1_400, 18_000, 18.0, 7..21))
        add("car gas car_repair parking", Kind(listOf("GS칼텍스", "SK에너지", "하이패스", "공영주차장"), 3_000, 80_000, 3.0))
        add("home bolt water receipt", Kind(listOf("관리비", "도시가스", "한국전력"), 30_000, 220_000, 1.5, 9..9))
        add("medication hospital", Kind(listOf("온누리약국", "연세내과의원", "튼튼치과"), 3_000, 40_000, 1.5, 9..18))
        add("run fitness", Kind(listOf("헬스장", "필라테스"), 50_000, 120_000, 0.6))
        add("movie ticket game music palette casino", Kind(listOf("CGV", "메가박스", "인터파크티켓", "스팀", "멜론"), 5_000, 35_000, 2.0, 13..22))
        add("book school child toys", Kind(listOf("교보문고", "YES24", "클래스101", "인프런"), 10_000, 60_000, 1.0))
        add("flight", Kind(listOf("대한항공", "야놀자", "에어비앤비"), 80_000, 450_000, 0.25))
        add("tv subscriptions code cloud", Kind(listOf("넷플릭스", "유튜브 프리미엄", "GitHub", "Cloudflare"), 5_000, 17_000, 2.0, 0..6))
        add("bar wine", Kind(listOf("역전할머니맥주", "이자카야 하나", "데일리샷"), 15_000, 60_000, 2.0, 19..23))
        add("cut brush spa", Kind(listOf("준오헤어", "올리브영", "네일샵"), 15_000, 60_000, 0.8))
        add("gift redeem flower celebration groups handshake", Kind(listOf("카카오톡 선물하기", "꽃집", "모임 회비"), 20_000, 100_000, 0.8))
        add("pets", Kind(listOf("펫프렌즈", "동물병원"), 15_000, 60_000, 1.0))
        add("bank shield savings chart sell work payments", Kind(listOf("보험료", "수수료"), 1_000, 90_000, 0.5, 9..17))
    }
    private val other = Kind(listOf("기타 결제"), 5_000, 30_000, 0.5)

    /** Returns how many rows were added. */
    suspend fun insert(today: LocalDate = LocalDate.now(), random: Random = Random(2026)): Int {
        val dao = app.dao
        val cats = dao.categoriesOnce().filter { !it.hidden }
        var pays = dao.payMethodsOnce().filter { !it.hidden }
        if (pays.size < 2) {
            dao.upsert(PayMethod(kind = PayKind.CREDIT, name = "테스트 신용카드", sort = 90))
            dao.upsert(PayMethod(kind = PayKind.CHECK, name = "테스트 체크카드", sort = 91))
            pays = dao.payMethodsOnce().filter { !it.hidden }
        }
        val zone = ZoneId.systemDefault()
        fun at(date: LocalDate, hours: IntRange) = date.atTime(hours.random(random), random.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
        fun won(min: Int, max: Int) = (random.nextInt(min, max + 1) / 100 * 100).toLong()

        // leaves get the spending; several categories with the same icon share that icon's frequency
        val expense = cats.filter { it.type == TxType.EXPENSE }
        val leaves = expense.filter { c -> expense.none { it.parentId == c.id } }
        val sameIcon = leaves.groupingBy { it.icon }.eachCount()
        val txs = mutableListOf<Tx>()
        val first = today.minusYears(5)
        var day = first
        while (!day.isAfter(today)) {
            for (c in leaves) {
                val k = kinds[c.icon] ?: other
                if (random.nextDouble() < k.perMonth / 30 / sameIcon.getValue(c.icon)) txs += Tx(
                    amount = won(k.min, k.max), occurredAt = at(day, k.hours), merchant = k.merchants.random(random),
                    categoryId = c.id, paymentMethodId = pays.random(random).id, source = TxSource.DUMMY,
                )
            }
            day = day.plusDays(1)
        }

        val income = cats.filter { it.type == TxType.INCOME }
        val salary = income.firstOrNull { it.icon == "work" || it.icon == "payments" || "급여" in it.name || "근로" in it.name } ?: income.firstOrNull()
        val extra = income.filter { it != salary }
        val payday = app.prefs.monthStartDay.takeIf { it > 1 } ?: 25
        var month = first.withDayOfMonth(payday)
        while (!month.isAfter(today)) {
            val raise = 1 + 0.03 * (month.year - first.year)
            if (salary != null) txs += Tx(
                type = TxType.INCOME, amount = (3_200_000 * raise).toLong() / 10_000 * 10_000, occurredAt = at(month, 9..9),
                merchant = "(주)회사 급여", categoryId = salary.id, paymentMethodId = pays.random(random).id, source = TxSource.DUMMY,
            )
            if (extra.isNotEmpty() && random.nextDouble() < 0.4) txs += Tx(
                type = TxType.INCOME, amount = won(10_000, 300_000), occurredAt = at(month.minusDays(random.nextLong(20)), 9..20),
                merchant = listOf("중고거래", "용돈", "이자", "캐시백").random(random), categoryId = extra.random(random).id, source = TxSource.DUMMY,
            )
            month = month.plusMonths(1)
        }
        dao.insertTxs(txs)

        // a few installment purchases, written the way real ones are
        val card = pays.firstOrNull { it.kind == PayKind.CREDIT } ?: pays.first()
        val bigCategory = leaves.firstOrNull { it.icon == "laptop" || it.icon == "bag" } ?: leaves.firstOrNull()
        repeat(4) { i ->
            dao.insertPurchase(Tx(
                amount = listOf(1_590_000L, 890_000L, 1_200_000L, 450_000L)[i], occurredAt = at(today.minusMonths(6L + i * 13L), 14..20),
                merchant = listOf("애플스토어", "삼성디지털프라자", "가구점", "쿠팡")[i], categoryId = bigCategory?.id,
                paymentMethodId = card.id, installmentMonths = listOf(10, 6, 12, 3)[i], source = TxSource.DUMMY,
            ))
        }
        return txs.size + 4
    }
}
