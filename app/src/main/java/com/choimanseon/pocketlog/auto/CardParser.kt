package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.data.PayKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class MsgKind { SPEND, CANCEL, WITHDRAW, DEPOSIT }

data class Parsed(
    val kind: MsgKind,
    val amount: Long?,      // KRW. null when only a foreign amount is shown
    val foreign: String?,   // e.g. "USD 12.99"
    val merchant: String,   // merchant, or counterparty for bank messages
    val installment: Int,
    val at: Long?,          // null = use the notification time
    val issuer: String?,    // key, e.g. "삼성", "카카오뱅크"
    val payKind: PayKind?,
    val last4: String?,
    val balance: Long?,
    val isCharge: Boolean,  // pay-money top-up
) {
    /** Display name for an auto-created payment method, e.g. "삼성카드", "KB국민체크", "카카오뱅크". */
    val payName: String?
        get() {
            val key = issuer ?: return null
            if (key in CardParser.completeNames) return if (payKind == PayKind.CHECK && key.endsWith("뱅크")) "$key 체크카드" else key
            return key + when (payKind) {
                PayKind.CHECK -> "체크"
                PayKind.BANK -> "은행"
                else -> "카드"
            }
        }
}

/**
 * Generic parser for Korean card / bank / pay notifications.
 * No per-issuer templates: it removes what it recognizes (names, amounts, dates, keywords)
 * and takes what is left near the time stamp as the merchant.
 * ponytail: heuristic, add per-issuer overrides when a real message fails (see CardParserTest).
 */
object CardParser {
    // Order matters: more specific keys first.
    private val issuerKeys = listOf(
        "KB국민", "국민", "KB", "신한", "삼성", "현대", "롯데", "하나", "우리", "NH농협", "NH", "농협",
        "BC", "비씨", "씨티", "IBK", "기업", "카카오뱅크", "카카오페이", "토스뱅크", "토스", "케이뱅크",
        "네이버페이", "쿠팡페이", "페이코", "SC제일", "수협", "광주", "전북", "부산", "경남", "iM", "대구",
        "새마을", "우체국", "신협",
    )
    private val canonical = mapOf(
        "국민" to "KB국민", "KB" to "KB국민", "NH" to "NH농협", "농협" to "NH농협", "비씨" to "BC",
        "기업" to "IBK기업", "IBK" to "IBK기업", "대구" to "iM", "SC제일" to "SC제일",
    )
    internal val completeNames = setOf(
        "카카오뱅크", "카카오페이", "토스뱅크", "토스", "케이뱅크", "네이버페이", "쿠팡페이", "페이코",
        "새마을", "우체국", "신협", "수협", "iM",
    )
    private val payApps = setOf("카카오페이", "토스", "네이버페이", "쿠팡페이", "페이코")
    private val issuerRegex = Regex(issuerKeys.joinToString("|") { Regex.escape(it) })

    private val num = """(\d{1,3}(?:,\d{3})+|\d+)"""
    private val amountWon = Regex("""(?<![\d.,])$num\s*원""")
    private val bankAmount = Regex("""(출금|입금|이체|결제|승인)\s*:?\s*$num(?![\d,/:]|\s*원)""")
    private val balanceRe = Regex("""잔액\s*:?\s*$num""")
    private val foreignRe = Regex("""(USD|JPY|EUR|CNY|GBP|AUD|CAD|HKD|SGD|VND|THB|PHP|TWD)\s*([\d,]+(?:\.\d+)?)""")
    private val excludedBefore = Regex("누적|잔액|한도|포인트|적립|할인|캐시백|가능")
    private val txnKeyword = Regex("승인|결제|사용|출금|입금|취소|이체|충전")
    private val installmentRe = Regex("""(\d{1,2})\s*개월""")
    private val dateTimeRe = Regex("""(\d{1,2})[/.-](\d{1,2})\s*(\d{1,2}):(\d{2})""")
    private val korDateRe = Regex("""(\d{1,2})월\s*(\d{1,2})일\s*(\d{1,2}):(\d{2})""")
    private val timeRe = Regex("""(?<![\d,])\d{1,2}:\d{2}(:\d{2})?(?!\d)""")
    private val labeled = Regex("""^(가맹점명?|이용가맹점|사용처|이용처|상호명?|받는\s?분|보낸\s?분|입금자|출금처|입금처)\s*[:：]\s*(.+)$""")

    private val maskedName = Regex("""[가-힣]{1,2}\*{1,2}[가-힣]{0,2}님?""")
    private val maskedDigits = Regex("""\d+\*+[\d*]*|\*+\d+[\d*]*""")
    private val noise = listOf(
        Regex("""\[[^\]]*]"""),
        maskedName, maskedDigits,
        Regex("""(총?누적(금액)?|잔액|한도|사용가능(금액)?)\s*:?\s*[\d,]+\s*원?"""),
        foreignRe,
        Regex("""[\d,]+\s*원"""),
        Regex("""\d{1,2}[/.-]\d{1,2}"""), timeRe, Regex("""\d{1,2}월\s*\d{1,2}일"""),
        Regex("""\(?\s*일시불\s*\)?"""), Regex("""\(?\s*\d{1,2}\s*개월\s*\)?"""), Regex("할부"),
        Regex("""(승인|거래|이용|결제)?시각|고객명"""), // KB: "고객명 홍*동님", "승인시각 10/04 21:55"
        Regex("해외승인|승인취소|결제취소|취소|승인|사용|결제완료|결제|완료|출금|입금|이체|충전|체크카드|신용카드|금액|일시"),
        Regex("""(?<!\S)[\d,]{3,}(?!\S)"""),
        Regex("""\(\s*\)"""),
        Regex("""[|·:：]"""),
    )

    /** Cheap privacy gate: only messages that look like a payment are stored or parsed at all. */
    fun looksFinancial(pkg: String, title: String, body: String): Boolean {
        val t = "$title\n$body"
        if ("(광고)" in t) return false
        if (!amountWon.containsMatchIn(t) && !bankAmount.containsMatchIn(t) && !foreignRe.containsMatchIn(t)) return false
        if (!txnKeyword.containsMatchIn(t)) return false
        return when (pkg) {
            in chatApps -> issuerRegex.containsMatchIn(title) // KakaoTalk 알림톡: title is the channel name
            in smsApps -> "Web발신" in t || issuerRegex.containsMatchIn(title)
            else -> issuerRegex.containsMatchIn(t)
        }
    }

    val smsApps = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging")
    private val chatApps = setOf("com.kakao.talk")

    fun parse(title: String, body: String, postTime: Long, zone: ZoneId = ZoneId.systemDefault()): Parsed? {
        val text = body.replace("[Web발신]", "\n").replace("(광고)", "")
        val all = "$title\n$text"
        if (!txnKeyword.containsMatchIn(all)) return null

        val kind = when {
            "취소" in all -> MsgKind.CANCEL
            "입금" in all && "출금" !in all -> MsgKind.DEPOSIT
            "출금" in all || "이체" in all -> MsgKind.WITHDRAW
            else -> MsgKind.SPEND
        }
        val amount = amountWon.findAll(text).firstOrNull { m ->
            val before = text.substring(maxOf(0, m.range.first - 8), m.range.first)
            !excludedBefore.containsMatchIn(before)
        }?.groupValues?.get(1)?.toWon() ?: bankAmount.find(text)?.groupValues?.get(2)?.toWon()
        val foreign = foreignRe.find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        if (amount == null && foreign == null) return null

        val lines = splitLines(text)
        val issuerRaw = (issuerRegex.find(title) ?: lines.firstOrNull()?.let { issuerRegex.find(it) } ?: issuerRegex.find(text))?.value
        val issuerKey = issuerRaw?.let { canonical[it] ?: it }
        val isCard = kind == MsgKind.SPEND || kind == MsgKind.CANCEL || "승인" in all || "체크카드" in all || "신용카드" in all
        val payKind = when {
            issuerKey == null -> null
            issuerKey in payApps -> PayKind.PAY_MONEY
            isCard -> if ("체크" in all) PayKind.CHECK else PayKind.CREDIT
            else -> PayKind.BANK
        }
        val last4 = lines.firstOrNull()?.let { Regex("""(?<![\d,/:])(\d{4})(?![\d,/:])""").find(it)?.groupValues?.get(1) }

        return Parsed(
            kind = kind,
            amount = amount,
            foreign = if (amount == null) foreign else null,
            merchant = merchant(lines, issuerRaw),
            installment = if ("일시불" in text) 0 else installmentRe.find(text)?.groupValues?.get(1)?.toInt() ?: 0,
            at = timeOf(text, postTime, zone),
            issuer = issuerKey,
            payKind = payKind,
            last4 = last4,
            balance = balanceRe.find(text)?.groupValues?.get(1)?.toWon(),
            isCharge = "충전" in all,
        )
    }

    private fun splitLines(text: String): List<String> {
        var s = timeRe.replace(text) { it.value + "\n" }
        s = Regex("총?누적|잔액|한도").replace(s) { "\n" + it.value }
        return s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun merchant(lines: List<String>, issuerRaw: String?): String {
        lines.firstNotNullOfOrNull { labeled.find(it)?.groupValues?.get(2)?.let(::clean) }?.let { if (it.hasLetters()) return it }

        val timeLine = lines.indexOfFirst { timeRe.containsMatchIn(it) }.takeIf { it >= 0 }
        val candidates = lines.mapIndexedNotNull { i, line ->
            clean(if (i == 0 && issuerRaw != null) stripHeader(line, issuerRaw) else line).takeIf { it.hasLetters() }?.let { i to it }
        }
        val pick = if (timeLine == null) candidates.firstOrNull()
        else candidates.firstOrNull { it.first > timeLine }
            ?: candidates.firstOrNull { it.first == timeLine }
            ?: candidates.lastOrNull { it.first < timeLine }
        return pick?.second.orEmpty()
    }

    /** First line usually is "삼성1234승인 홍*동" / "현대카드 M 승인": drop the issuer word and card-product letters. */
    private fun stripHeader(line: String, issuerRaw: String): String {
        val words = line.split(Regex("\\s+"))
        if (words.none { issuerRaw in it }) return line
        return words.filterNot { issuerRaw in it || it.matches(Regex("[A-Za-z]{1,2}")) || it in setOf("카드", "체크", "은행") }
            .joinToString(" ")
    }

    private fun clean(s: String): String {
        var r = s
        for (re in noise) r = re.replace(r, " ")
        return r.replace(Regex("\\s+"), " ").trim().trim('-', ',', '/').trim()
    }

    private fun String.hasLetters() = any { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' }
    private fun String.toWon() = replace(",", "").toLongOrNull()

    private fun timeOf(text: String, postTime: Long, zone: ZoneId): Long? =
        (dateTimeRe.findAll(text) + korDateRe.findAll(text)).firstNotNullOfOrNull { m ->
            val (mo, d, h, mi) = m.destructured
            timeFrom(mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), postTime, zone)
        }

    /** Messages show "10/02 19:43" without a year: take the notification's year, or last year if that lands in the future. */
    fun timeFrom(month: Int, day: Int, hour: Int, minute: Int, postTime: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val post = Instant.ofEpochMilli(postTime).atZone(zone)
        val local = runCatching { LocalDateTime.of(post.year, month, day, hour, minute) }.getOrNull() ?: return null
        val at = local.atZone(zone).toInstant().toEpochMilli()
        return if (at > postTime + 86_400_000L) local.minusYears(1).atZone(zone).toInstant().toEpochMilli() else at
    }

    /** "삼성카드" → "삼성", for AI answers. */
    fun issuerKey(name: String?): String? = name?.let { issuerRegex.find(it)?.value }?.let { canonical[it] ?: it }
}
