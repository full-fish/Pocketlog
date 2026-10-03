package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxType

/** User rules → built-in merchant dictionary → AI (only for merchants never seen before; the answer is cached as a rule). */
object Categorizer {
    private val expenseWords = listOf(
        "카페" to "스타벅스|커피|카페|이디야|메가엠지씨|메가커피|컴포즈|투썸|빽다방|폴바셋|할리스|파스쿠찌|블루보틀|공차|베스킨|던킨|파리바게|뚜레쥬르",
        "배달" to "배달의민족|배민|요기요|쿠팡이츠|땡겨요",
        "편의점" to """\bCU\b|씨유|\bGS25\b|지에스25|세븐일레븐|이마트24|미니스톱|편의점""",
        "장보기" to "이마트|홈플러스|롯데마트|코스트코|컬리|노브랜드|하나로마트|트레이더스|식자재",
        "쇼핑" to "쿠팡|11번가|G마켓|지마켓|옥션|무신사|SSG|에이블리|지그재그|29CM|오늘의집|알리익스프레스|ALIEXPRESS|테무|TEMU|네이버페이",
        "교통" to """지하철|버스|택시|카카오T|티머니|코레일|\bSRT\b|고속|교통|ezl|캐시비""",
        "자동차" to "주유|GS칼텍스|SK에너지|S-OIL|에쓰오일|현대오일|주차|하이패스|통행료",
        "주거·통신" to """\bSKT\b|\bKT\b|LG U\+|LGU\+|유플러스|통신|관리비|월세|도시가스|한국전력|한전|수도""",
        "의료·건강" to "병원|의원|약국|치과|한의원|안과|피부과|정형외과|내과",
        "문화·여가" to "CGV|메가박스|롯데시네마|영화|노래|PC방|볼링|교보문고|YES24|알라딘|공연|티켓",
        "구독" to """넷플릭스|NETFLIX|유튜브|YOUTUBE|멜론|스포티파이|SPOTIFY|디즈니|티빙|웨이브|왓챠|쿠팡플레이|APPLE\.COM""",
        "여행" to "호텔|모텔|에어비앤비|AIRBNB|항공|대한항공|아시아나|제주항공|진에어|티웨이|여기어때|야놀자|아고다",
        "교육" to "학원|교육|클래스|인프런|유데미",
        "술·음료" to "주점|포차|호프|이자카야|와인|데일리샷|맥주",
        "미용" to "미용실|헤어|네일|올리브영",
        "반려동물" to "동물병원|펫|애견",
        "식비" to "김밥|식당|분식|국밥|치킨|피자|버거|맥도날드|롯데리아|맘스터치|서브웨이|본죽|한솥|도시락|반점|족발|보쌈|초밥|스시|라멘|돈까스|쌀국수",
    ).map { (name, words) -> name to Regex(words, RegexOption.IGNORE_CASE) }

    private val incomeWords = listOf(
        "급여" to Regex("급여|월급|상여|봉급"),
        "환급·이자" to Regex("이자|환급|캐시백"),
        "용돈" to Regex("용돈"),
    )

    fun fromRules(merchant: String, rules: List<Rule>): Long? {
        val norm = normalize(merchant)
        if (norm.isEmpty()) return null
        return rules.firstOrNull { it.pattern.isNotEmpty() && norm.contains(it.pattern) }?.value?.toLongOrNull()
    }

    fun fromDictionary(merchant: String, type: TxType, categories: List<Category>): Long? {
        val words = if (type == TxType.INCOME) incomeWords else expenseWords
        val name = words.firstOrNull { it.second.containsMatchIn(merchant) }?.first
            ?: if (type == TxType.INCOME) "기타수입" else return null
        return categories.firstOrNull { it.type == type && it.parentId == null && !it.hidden && it.name == name }?.id
    }

    suspend fun categorize(merchant: String, type: TxType): Long? {
        if (!app.prefs.autoCategory || merchant.isBlank()) return null
        val dao = app.dao
        val cats = dao.categoriesOnce()
        return fromRules(merchant, dao.rulesOnce(RuleKind.CATEGORY))?.takeIf { id -> cats.any { it.id == id && it.type == type } }
            ?: fromDictionary(merchant, type, cats)
    }

    suspend fun categorizeWithAi(tx: Tx) {
        if (!app.prefs.autoCategory || tx.merchant.isBlank() || !Ai.usable()) return
        val dao = app.dao
        val cats = dao.categoriesOnce().filter { it.type == TxType.EXPENSE && !it.hidden }
        val id = runCatching { Ai.categorize(listOf(tx.merchant), cats)[tx.merchant] }.getOrNull() ?: return
        dao.putRule(RuleKind.CATEGORY, normalize(tx.merchant), id.toString())
        dao.txOnce(tx.id)?.takeIf { it.categoryId == null }?.let { dao.update(it.copy(categoryId = id, updatedAt = System.currentTimeMillis())) }
    }

    /** When the user fixes a category, remember it for that merchant. */
    suspend fun learn(merchant: String, categoryId: Long?) {
        val norm = normalize(merchant)
        if (norm.isEmpty() || categoryId == null) return
        app.dao.putRule(RuleKind.CATEGORY, norm, categoryId.toString())
    }
}
