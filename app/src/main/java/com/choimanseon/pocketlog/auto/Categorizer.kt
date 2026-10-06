package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.tops

/** A category and its tags, as a rule stores them: "12" or "12|31,40". */
data class Pick(val category: Long, val tags: List<Long> = emptyList()) {
    fun encode() = if (tags.isEmpty()) "$category" else "$category|${tags.joinToString(",")}"

    companion object {
        fun decode(value: String) = value.substringBefore('|').toLongOrNull()?.let { c ->
            Pick(c, value.substringAfter('|', "").split(',').mapNotNull { it.toLongOrNull() })
        }
    }
}

/** User rules → built-in merchant dictionary → AI (only for merchants never seen before; the answer is cached as a rule). */
object Categorizer {
    // category name to (tag name or null) to merchant words; the first hit wins, so specific words come first.
    // "A|B": category A if the user has it, else B (the tag is looked up under whichever was found)
    private fun w(category: String, tag: String?, words: String) = Triple(category, tag, Regex(words, RegexOption.IGNORE_CASE))

    private val expenseWords = listOf(
        w("식비", null, "배달의민족|배민|요기요|쿠팡이츠|땡겨요"),
        w("카페·간식", "커피", "스타벅스|커피|카페|이디야|메가엠지씨|메가커피|컴포즈|투썸|빽다방|폴바셋|할리스|파스쿠찌|블루보틀|공차"),
        w("카페·간식", "디저트·빵", "파리바게|뚜레쥬르|베스킨|던킨|베이커리|제과"),
        w("카페·간식", "편의점", """\bCU\b|씨유|\bGS25\b|지에스25|세븐일레븐|이마트24|미니스톱|편의점"""),
        w("식비", "장보기", "이마트|홈플러스|롯데마트|코스트코|컬리|노브랜드|하나로마트|트레이더스|식자재"),
        w("쇼핑", null, "쿠팡|11번가|G마켓|지마켓|옥션|무신사|SSG|에이블리|지그재그|29CM|오늘의집|알리익스프레스|ALIEXPRESS|테무|TEMU|네이버페이"),
        w("교통·차량", "택시", "택시|카카오T"),
        w("교통·차량", "기차", """코레일|\bSRT\b|\bKTX\b"""),
        w("교통·차량", "고속버스", "고속"),
        w("교통·차량", "대중교통", "지하철|버스|티머니|캐시비|ezl|교통"),
        w("교통·차량", "주유", "주유|GS칼텍스|SK에너지|S-OIL|에쓰오일|현대오일"),
        w("교통·차량", "주차·통행료", "주차|하이패스|통행료"),
        w("교통·차량", "항공", "대한항공|아시아나|제주항공|진에어|티웨이|항공"),
        w("주거·통신", "휴대폰", """\bSKT\b|\bKT\b|LG U\+|LGU\+|유플러스|통신"""),
        w("주거·통신", "관리비", "관리비"),
        w("주거·통신", "월세", "월세"),
        w("주거·통신", "가스", "도시가스"),
        w("주거·통신", "전기", "한국전력|한전"),
        w("주거·통신", "수도", "수도"),
        w("건강·의료", "치과", "치과"),
        w("건강·의료", "약국", "약국"),
        w("건강·의료", "병원", "병원|의원|한의원|안과|피부과|정형외과|내과"),
        w("문화·여가", "영화·공연", "CGV|메가박스|롯데시네마|영화|공연|티켓"),
        w("문화·여가", "도서", "교보문고|YES24|알라딘"),
        w("문화·여가", "숙소", "호텔|모텔|에어비앤비|AIRBNB|여기어때|야놀자|아고다"),
        w("술·유흥", "노래방·놀거리", "노래|PC방|볼링"),
        w("술·유흥", "술집", "주점|포차|호프|이자카야"),
        w("술·유흥", "홈술", "와인|데일리샷|맥주"),
        // 개발 구독비, not 교육 (사용자 결정): the imported 똑똑가계부 category if it exists, else 구독 › 개발 툴
        w("개발 구독비|구독", "개발 툴", """GITHUB|깃허브|\bAWS\b|AMAZON WEB SERVICES|ANTHROPIC|CLAUDE|OPENAI|CHATGPT|CLOUDFLARE"""),
        w("구독", "OTT", "넷플릭스|NETFLIX|유튜브|YOUTUBE|디즈니|티빙|웨이브|왓챠|쿠팡플레이"),
        w("구독", "음악", "멜론|스포티파이|SPOTIFY"),
        w("구독", "클라우드·앱", """APPLE\.COM"""),
        w("교육", "강의", "클래스|인프런|유데미"),
        w("교육", "학원", "학원|교육"),
        w("뷰티·미용", "헤어", "미용실|헤어"),
        w("뷰티·미용", "네일", "네일"),
        w("뷰티·미용", "화장품", "올리브영"),
        w("반려동물", "동물병원", "동물병원"),
        w("반려동물", null, "펫|애견"),
        w("식비", "외식", "김밥|식당|분식|국밥|치킨|피자|버거|맥도날드|롯데리아|맘스터치|서브웨이|본죽|한솥|도시락|반점|족발|보쌈|초밥|스시|라멘|돈까스|쌀국수"),
    )

    private val incomeWords = listOf(
        w("급여", "상여", "상여"),
        w("급여", "월급", "급여|월급|봉급"),
        w("금융수입", "이자", "이자"),
        w("금융수입", "환급", "환급"),
        w("부수입", "리워드·캐시백", "캐시백"),
        w("용돈·기타", "용돈", "용돈"),
    )

    /** The longest matching rule wins ("쿠팡이츠" over "쿠팡"); among equals, the newest. */
    fun fromRules(merchant: String, rules: List<Rule>): Pick? {
        val norm = normalize(merchant)
        if (norm.isEmpty()) return null
        return rules.filter { it.pattern.isNotEmpty() && norm.contains(it.pattern) }.maxByOrNull { it.pattern.length }?.let { Pick.decode(it.value) }
    }

    /** "스타벅스 김포점" / "스타벅스(김포점)" → "스타벅스", so one rule covers every branch. Needs the space or brackets to know where the brand ends. */
    fun brandOf(merchant: String): String {
        val words = merchant.replace(Regex("""\([^)]*점\)"""), " ").trim().split(Regex("""\s+"""))
        val brand = normalize((if (words.size > 1 && words.last().length >= 2 && words.last().endsWith("점")) words.dropLast(1) else words).joinToString(""))
        return if (brand.length >= 3) brand else normalize(merchant) // "롯데 백화점" → "롯데" would match too much
    }

    /** Works with the default category names (TODO #27 table); renamed or imported categories fall through to the AI. */
    fun fromDictionary(merchant: String, type: TxType, categories: List<Category>): Pick? {
        val words = if (type == TxType.INCOME) incomeWords else expenseWords
        val (name, tag) = words.firstOrNull { it.third.containsMatchIn(merchant) }?.let { it.first to it.second }
            ?: if (type == TxType.INCOME) "용돈·기타" to null else return null
        val tops = categories.tops(type).filter { !it.hidden }
        val category = name.split('|').firstNotNullOfOrNull { n -> tops.firstOrNull { it.name == n } } ?: return null
        val tagId = tag?.let { t -> categories.firstOrNull { it.parentId == category.id && it.name == t }?.id }
        return Pick(category.id, listOfNotNull(tagId))
    }

    suspend fun categorize(merchant: String, type: TxType): Pick? {
        if (!app.prefs.autoCategory || merchant.isBlank()) return null
        val dao = app.dao
        val cats = dao.categoriesOnce()
        val byId = cats.associateBy { it.id }
        return fromRules(merchant, dao.rulesOnce(RuleKind.CATEGORY))
            ?.takeIf { p -> byId[p.category]?.type == type }
            ?.let { p -> p.copy(tags = p.tags.filter { it in byId }) } // a deleted tag drops out
            ?: fromDictionary(merchant, type, cats)
    }

    /** A merchant no rule or dictionary word knows: the AI picks the category and its tags, and the answer becomes a rule. */
    suspend fun categorizeWithAi(tx: Tx) {
        if (!app.prefs.autoCategory || tx.merchant.isBlank() || !Ai.usable()) return
        val dao = app.dao
        val pick = runCatching { Ai.categorize(listOf(tx.merchant), dao.categoriesOnce())[tx.merchant] }.getOrNull() ?: return
        learn(tx.merchant, pick)
        if (dao.txOnce(tx.id)?.categoryId == null) {
            dao.setCategory(tx.id, pick.category)
            dao.setTags(tx.id, pick.tags)
        }
    }

    /**
     * When the user fixes a category or tags (or the AI picks them), remember them for that merchant.
     * 공통 태그 (데이트, 친구·모임 …) are about that one time, not the place, so they are not remembered.
     * Nothing is added when the rules already give that answer. Otherwise the first rule for a brand covers every branch;
     * a fix that disagrees with an existing brand (or longer) rule stays an exception for this one name.
     */
    suspend fun learn(merchant: String, chosen: Pick?) {
        val full = normalize(merchant)
        if (full.isEmpty() || chosen == null) return
        val cats = app.dao.categoriesOnce().associateBy { it.id }
        val pick = chosen.copy(tags = chosen.tags.filter { cats[it]?.parentId == chosen.category })
        val rules = app.dao.rulesOnce(RuleKind.CATEGORY)
        if (fromRules(merchant, rules)?.let { it.category == pick.category && it.tags.toSet() == pick.tags.toSet() } == true) return
        val brand = brandOf(merchant)
        val key = if (rules.any { it.pattern.length >= brand.length && full.contains(it.pattern) }) full else brand
        app.dao.putRule(RuleKind.CATEGORY, key, pick.encode())
    }
}
