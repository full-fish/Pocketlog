package com.choimanseon.pocketlog.data

import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.domain.tops
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 설정 → 더미 데이터 넣기 (TODO #13, #52): five years of made-up spending, income and savings so every screen has something to show,
 * plus 즐겨찾기 (some repeating) and a few 해외 결제. The dummy data put in before is removed first, and nothing is dated after now.
 * Rows follow the TODO #27 table by name: each merchant goes to its category with the matching tags, some with a 공통 태그.
 * A category not in the table (renamed or imported) gets a few generic rows, and the existing payment methods are used.
 * Every row is [TxSource.DUMMY], so it can be removed without touching real records.
 */
object Dummy {
    private fun String.items() = split(',').filter { it.isNotBlank() }

    /**
     * One kind of row under a category: [tags] of that category, one merchant, and with chance [sharedChance] one of
     * the [shared] 공통 태그. A [day] makes it a fixed monthly bill on that day instead of [perMonth] random rows.
     */
    private class Kind(
        tags: String, merchants: String, val min: Int, val max: Int, val perMonth: Double = 0.0, val hours: IntRange = 8..21,
        val day: Int? = null, val round: Int = 100, shared: String = "", val sharedChance: Double = 0.35,
    ) {
        val tags = tags.items()
        val merchants = merchants.items()
        val shared = shared.items()
    }

    private const val PAYDAY = 0

    private val kinds: Map<String, List<Kind>> = mapOf(
        "식비" to listOf(
            Kind("외식", "김밥천국,한솥도시락,본죽,맘스터치,서브웨이,국밥집,명동교자,스시로", 7_000, 35_000, 14.0, 11..20, shared = "데이트,친구·모임,가족,회식"),
            Kind("장보기", "이마트,홈플러스,컬리,노브랜드", 15_000, 90_000, 4.0, 10..20, shared = "가족"),
            Kind("야식", "배달의민족,쿠팡이츠,요기요", 14_000, 32_000, 4.0, 21..23),
            Kind("야식,외식", "교촌치킨,포장마차,순대국밥", 15_000, 40_000, 1.0, 21..23, shared = "친구·모임"),
        ),
        "카페·간식" to listOf(
            Kind("커피", "스타벅스,메가MGC커피,이디야커피,투썸플레이스,컴포즈커피", 2_000, 7_000, 10.0, 8..17, shared = "데이트", sharedChance = 0.15),
            Kind("디저트·빵", "파리바게뜨,뚜레쥬르,설빙", 4_000, 15_000, 3.0, 10..20),
            Kind("과자·간식", "배스킨라빈스,붕어빵,왕타코야끼", 2_000, 12_000, 2.0, 12..21),
            Kind("편의점", "GS25,CU,세븐일레븐", 1_500, 9_000, 8.0, 7..23),
        ),
        "술·유흥" to listOf(
            Kind("술집", "역전할머니맥주,이자카야 하나,포차", 20_000, 90_000, 2.0, 19..23, shared = "친구·모임,회식,데이트", sharedChance = 0.7),
            Kind("홈술", "데일리샷,이마트 와인", 10_000, 50_000, 0.8, 17..22),
            Kind("노래방·놀거리", "코인노래방,볼링장,PC방", 3_000, 25_000, 1.0, 14..23, shared = "친구·모임,데이트"),
        ),
        "쇼핑" to listOf(
            Kind("옷·신발", "무신사,지그재그,유니클로", 19_000, 129_000, 1.5),
            Kind("가방·잡화", "29CM,쿠팡", 9_900, 79_000, 0.6),
            Kind("전자기기", "쿠팡,하이마트", 20_000, 300_000, 0.3),
            Kind("가구·인테리어", "오늘의집,이케아", 15_000, 200_000, 0.3),
            Kind("중고거래", "당근마켓,번개장터", 5_000, 80_000, 0.4, round = 1_000),
        ),
        "생활" to listOf(
            Kind("생필품", "다이소,쿠팡", 3_000, 30_000, 3.0),
            Kind("주방용품", "다이소,오늘의집", 5_000, 40_000, 0.4),
            Kind("세탁·청소", "크린토피아,세탁특공대", 5_000, 30_000, 0.8),
            Kind("수리·공구", "숨고,철물점", 10_000, 100_000, 0.1),
            Kind("이사", "짐싸 이사", 300_000, 900_000, 0.02, round = 10_000),
        ),
        "주거·통신" to listOf(
            Kind("관리비", "아파트 관리비", 180_000, 260_000, day = 25, hours = 9..9),
            Kind("전기", "한국전력", 20_000, 60_000, day = 18, hours = 9..9),
            Kind("가스", "도시가스", 10_000, 90_000, day = 22, hours = 9..9),
            Kind("휴대폰", "SKT", 55_000, 55_000, day = 15, hours = 9..9),
            Kind("인터넷", "KT 인터넷", 33_000, 33_000, day = 20, hours = 9..9),
        ),
        "교통·차량" to listOf(
            Kind("대중교통", "지하철,버스,티머니", 1_400, 3_000, 16.0, 7..21),
            Kind("택시", "카카오T 택시", 5_000, 25_000, 2.0, 0..23, shared = "업무·청구", sharedChance = 0.2),
            Kind("기차", "코레일,SRT", 8_000, 60_000, 0.3, shared = "여행,가족", sharedChance = 0.6),
            Kind("고속버스", "고속버스", 10_000, 35_000, 0.2, shared = "여행", sharedChance = 0.6),
            Kind("항공", "대한항공,제주항공", 60_000, 450_000, 0.15, shared = "여행", sharedChance = 1.0),
            Kind("주유", "GS칼텍스,SK에너지", 30_000, 80_000, 2.0),
            Kind("주차·통행료", "하이패스,공영주차장", 1_000, 15_000, 2.0),
            Kind("정비·세차", "스피드메이트,손세차장", 10_000, 150_000, 0.3),
        ),
        "건강·의료" to listOf(
            Kind("병원", "연세내과의원,튼튼정형외과", 3_000, 30_000, 1.0, 9..18),
            Kind("치과", "튼튼치과", 10_000, 150_000, 0.2, 9..18),
            Kind("약국", "온누리약국", 2_000, 20_000, 1.0, 9..20),
            Kind("운동", "헬스장,필라테스,배드민턴 클럽 회비", 30_000, 120_000, 0.8, round = 10_000),
            Kind("영양제", "아이허브,쿠팡", 15_000, 60_000, 0.4),
            Kind("안경·렌즈", "다비치안경,렌즈미", 20_000, 150_000, 0.1),
        ),
        "뷰티·미용" to listOf(
            Kind("헤어", "준오헤어,블루클럽", 12_000, 80_000, 0.7, round = 1_000),
            Kind("화장품", "올리브영", 8_000, 50_000, 0.8),
            Kind("네일", "네일샵", 30_000, 70_000, 0.2, round = 1_000),
            Kind("피부관리", "피부과", 50_000, 150_000, 0.1, round = 1_000),
        ),
        "문화·여가" to listOf(
            Kind("영화·공연", "CGV,메가박스,인터파크티켓", 9_000, 120_000, 1.2, 13..22, shared = "데이트,친구·모임", sharedChance = 0.5),
            Kind("도서", "교보문고,YES24", 10_000, 40_000, 0.6),
            Kind("게임", "스팀,닌텐도 eShop", 5_000, 70_000, 0.4),
            Kind("취미", "복권,화방,클라이밍", 1_000, 50_000, 0.6, round = 1_000),
            Kind("숙소", "야놀자,여기어때,에어비앤비", 60_000, 300_000, 0.3, shared = "여행", sharedChance = 1.0),
            Kind("전시·체험", "국립현대미술관,원데이클래스", 5_000, 60_000, 0.3, shared = "데이트"),
        ),
        "구독" to listOf(
            Kind("OTT", "넷플릭스", 17_000, 17_000, day = 5, hours = 0..6),
            Kind("음악", "유튜브 프리미엄", 14_900, 14_900, day = 10, hours = 0..6),
            Kind("클라우드·앱", "Google One", 2_400, 2_400, day = 12, hours = 0..6),
            Kind("개발 툴", "GitHub", 6_000, 6_000, day = 3, hours = 0..6),
            Kind("멤버십", "쿠팡 와우", 7_890, 7_890, day = 8, hours = 0..6, round = 10),
        ),
        "교육" to listOf(
            Kind("강의", "인프런,클래스101,유데미", 15_000, 150_000, 0.4, round = 1_000),
            Kind("학원", "영어학원", 150_000, 300_000, 0.05, round = 10_000),
            Kind("자격증·시험", "큐넷,YBM 토익", 20_000, 50_000, 0.1),
            Kind("교재", "교보문고", 15_000, 35_000, 0.2),
        ),
        "경조사·선물" to listOf(
            Kind("선물", "카카오톡 선물하기,꽃집", 10_000, 100_000, 0.8, shared = "데이트,가족,친구·모임", sharedChance = 0.6),
            Kind("축의금", "축의금", 50_000, 100_000, 0.15, round = 50_000),
            Kind("조의금", "조의금", 50_000, 100_000, 0.05, round = 50_000),
            Kind("기부", "유니세프", 10_000, 30_000, 0.1, round = 10_000),
            Kind("부모님 용돈", "부모님 용돈", 100_000, 300_000, 0.3, round = 50_000, shared = "가족", sharedChance = 1.0),
        ),
        "금융" to listOf(
            Kind("보험", "실손보험", 35_000, 35_000, day = 21, hours = 9..9),
            Kind("세금", "자동차세,재산세", 30_000, 300_000, 0.1, 9..17),
            Kind("수수료", "수수료", 500, 2_000, 0.5, 9..17),
            Kind("연회비", "카드 연회비", 10_000, 30_000, 0.08, 9..17, round = 1_000),
        ),
        "반려동물" to listOf(
            Kind("사료·간식", "펫프렌즈", 15_000, 60_000, 0.8),
            Kind("동물병원", "동물병원", 20_000, 150_000, 0.2),
        ),
        "급여" to listOf(
            Kind("월급", "(주)회사 급여", 3_200_000, 3_200_000, day = PAYDAY, hours = 9..9, round = 10_000),
            Kind("상여", "(주)회사 상여금", 1_000_000, 2_000_000, 0.15, 9..9, round = 10_000),
        ),
        "부수입" to listOf(
            Kind("중고판매", "당근마켓,번개장터", 10_000, 200_000, 0.3, round = 1_000),
            Kind("리워드·캐시백", "캐시백,토스 포인트", 500, 15_000, 0.6),
        ),
        "금융수입" to listOf(
            Kind("이자", "예금 이자,카카오뱅크 이자", 1_000, 30_000, 0.5, 9..9, round = 10),
            Kind("환급", "연말정산 환급", 100_000, 600_000, 0.08, 9..9, round = 10),
        ),
        "용돈·기타" to listOf(Kind("용돈", "용돈", 50_000, 200_000, 0.15, round = 10_000)),
        "저축" to listOf(
            Kind("적금", "자동이체 적금", 300_000, 300_000, day = 26, hours = 9..9, round = 10_000),
            Kind("청약", "주택청약", 100_000, 100_000, day = 26, hours = 9..9, round = 10_000),
            Kind("비상금", "비상금 통장", 50_000, 300_000, 0.2, round = 10_000),
        ),
        "투자" to listOf(
            Kind("주식", "증권 계좌 이체", 100_000, 1_000_000, 0.3, 9..15, round = 10_000),
            Kind("ETF·펀드", "ETF 적립", 100_000, 100_000, day = 27, hours = 9..9, round = 10_000),
        ),
    )
    private val other = listOf(Kind("", "기타 결제", 5_000, 30_000, 0.5))

    /** Returns how many rows were added. */
    suspend fun insert(today: LocalDate = LocalDate.now(), random: Random = Random(2026)): Int {
        val dao = app.dao
        dao.deleteDummy()
        val cats = dao.categoriesOnce().filter { !it.hidden }
        var pays = dao.payMethodsOnce().filter { !it.hidden }
        if (pays.size < 2) {
            dao.upsert(PayMethod(kind = PayKind.CREDIT, name = "테스트 신용카드", sort = 90))
            dao.upsert(PayMethod(kind = PayKind.CHECK, name = "테스트 체크카드", sort = 91))
            pays = dao.payMethodsOnce().filter { !it.hidden }
        }
        val zone = ZoneId.systemDefault()
        // a row at 21:17 today would show before it happened
        val cutoff = if (today == LocalDate.now()) System.currentTimeMillis() else today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val first = today.minusYears(5)
        val payday = app.prefs.monthStartDay.takeIf { it > 1 } ?: 25
        fun at(date: LocalDate, hours: IntRange) = date.atTime(hours.random(random), random.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
        // prices and the salary go up 3% a year
        fun won(k: Kind, date: LocalDate) = (random.nextInt(k.min, k.max + 1) * (1 + 0.03 * (date.year - first.year))).toLong() / k.round * k.round

        val tagIds = cats.filter { it.parentId != null }.associate { (it.parentId to it.name) to it.id }
        val group = cats.filter { it.tagGroup }.associateBy { it.type }
        fun tag(owner: Category?, name: String) = owner?.let { tagIds[it.id to name] }

        val plan = TxType.entries.flatMap { cats.tops(it) }.map { it to (kinds[it.name] ?: other) }
        val txs = mutableListOf<Tx>()
        val tags = mutableListOf<List<Long>>()
        var day = first
        while (!day.isAfter(today)) {
            for ((c, ks) in plan) for (k in ks) {
                val due = k.day?.let { day.dayOfMonth == minOf(if (it == PAYDAY) payday else it, day.lengthOfMonth()) }
                    ?: (random.nextDouble() < k.perMonth / 30)
                if (!due) continue
                val shared = k.shared.takeIf { it.isNotEmpty() && random.nextDouble() < k.sharedChance }?.random(random)
                val tx = Tx(
                    type = c.type, amount = won(k, day), occurredAt = at(day, k.hours), merchant = k.merchants.random(random), categoryId = c.id,
                    paymentMethodId = pays.random(random).id.takeIf { c.type != TxType.SAVING }, source = TxSource.DUMMY,
                )
                if (tx.occurredAt > cutoff) continue
                txs += tx
                tags += k.tags.mapNotNull { tag(c, it) } + listOfNotNull(shared?.let { tag(group[c.type], it) })
            }
            day = day.plusDays(1)
        }
        val ids = dao.insertTxs(txs)
        dao.insertTags(ids.zip(tags).flatMap { (id, t) -> t.map { TxTag(id, it) } })

        // a few installment purchases, written the way real ones are
        val card = pays.firstOrNull { it.kind == PayKind.CREDIT } ?: pays.first()
        val shopping = cats.tops(TxType.EXPENSE).let { tops -> tops.firstOrNull { it.name == "쇼핑" } ?: tops.firstOrNull() }
        repeat(4) { i ->
            val id = dao.insertPurchase(Tx(
                amount = listOf(1_590_000L, 890_000L, 1_200_000L, 450_000L)[i], occurredAt = at(today.minusMonths(6L + i * 13L), 14..20),
                merchant = listOf("애플스토어", "삼성디지털프라자", "가구점", "쿠팡")[i], categoryId = shopping?.id,
                paymentMethodId = card.id, installmentMonths = listOf(10, 6, 12, 3)[i], source = TxSource.DUMMY,
            ))
            dao.setTags(id, listOfNotNull(tag(shopping, if (i == 2) "가구·인테리어" else "전자기기")))
        }

        fun top(type: TxType, name: String) = cats.tops(type).firstOrNull { it.name == name }
        // 해외 결제: won at the day's rate, the foreign amount kept the way card messages and 직접 입력 keep it
        val abroad = listOf(
            Foreign("AMAZON.COM", "USD 35.20", 47_520, "쇼핑", "전자기기", 2), Foreign("ALIEXPRESS", "USD 12.80", 17_150, "쇼핑", "가방·잡화", 9),
            Foreign("Booking.com", "JPY 42,000", 382_200, "문화·여가", "숙소", 14), Foreign("JR EAST", "JPY 6,300", 57_330, "교통·차량", "기차", 14),
            Foreign("ICHIRAN", "JPY 2,980", 27_120, "식비", "외식", 14), Foreign("STEAM", "USD 19.99", 26_390, "문화·여가", "게임", 20),
        )
        for (f in abroad) {
            val c = top(TxType.EXPENSE, f.category) ?: continue
            val id = dao.insert(Tx(
                amount = f.won, originalAmount = f.foreign, occurredAt = at(today.minusMonths(f.monthsAgo.toLong()), 10..22), merchant = f.merchant,
                categoryId = c.id, paymentMethodId = card.id, source = TxSource.DUMMY,
                memo = "해외 결제 · ${f.foreign.substringBefore(' ')} 환산 (더미)",
            ))
            dao.setTags(id, listOfNotNull(tag(c, f.tag), tag(group[TxType.EXPENSE], "여행").takeIf { f.foreign.startsWith("JPY") }))
        }

        // 즐겨찾기, a few repeating: their records from today on are dummy rows too
        val favorites = listOf(
            Fav(TxType.EXPENSE, "김밥천국", 8_000, "식비", "외식"), Fav(TxType.EXPENSE, "스타벅스", 4_700, "카페·간식", "커피"),
            Fav(TxType.EXPENSE, "지하철", 1_550, "교통·차량", "대중교통"), Fav(TxType.EXPENSE, "GS25", 5_500, "카페·간식", "편의점"),
            Fav(TxType.EXPENSE, "부모님 용돈", 300_000, "경조사·선물", "부모님 용돈", "가족", Repeat.MONTHLY, 1),
            Fav(TxType.EXPENSE, "헬스장", 70_000, "건강·의료", "운동", repeat = Repeat.MONTHLY, day = 10),
            Fav(TxType.EXPENSE, "로또", 5_000, "문화·여가", "취미", repeat = Repeat.WEEKLY, day = 6),
            Fav(TxType.INCOME, "용돈", 100_000, "용돈·기타", "용돈"),
            Fav(TxType.SAVING, "비상금 통장", 100_000, "저축", "비상금", repeat = Repeat.MONTHLY, day = 28),
        )
        favorites.forEachIndexed { i, f ->
            val c = top(f.type, f.category) ?: return@forEachIndexed
            dao.upsert(Favorite(
                type = f.type, amount = f.amount, merchant = f.merchant, categoryId = c.id,
                tags = listOfNotNull(tag(c, f.tag), f.shared?.let { tag(group[f.type], it) }).joinToString(","),
                paymentMethodId = pays[i % pays.size].id.takeIf { f.type != TxType.SAVING },
                repeat = f.repeat, repeatDay = f.day, nextAt = com.choimanseon.pocketlog.auto.Repeats.firstAt(f.repeat, f.day, today),
                sort = i, dummy = true,
            ))
        }
        return txs.size + 4 + abroad.size
    }

    private class Foreign(val merchant: String, val foreign: String, val won: Long, val category: String, val tag: String, val monthsAgo: Int)
    private class Fav(
        val type: TxType, val merchant: String, val amount: Long, val category: String, val tag: String, val shared: String? = null,
        val repeat: Repeat = Repeat.NONE, val day: Int = 1,
    )
}
