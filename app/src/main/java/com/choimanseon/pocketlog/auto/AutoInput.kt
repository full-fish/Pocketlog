package com.choimanseon.pocketlog.auto

import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.RawMessage
import com.choimanseon.pocketlog.data.RawStatus
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.budgetSpan
import com.choimanseon.pocketlog.domain.budgetUses
import com.choimanseon.pocketlog.domain.tops
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

private const val MIN = 60_000L
private const val DAY = 86_400_000L

/** "(주)데일리샷 " → "데일리샷" for matching merchants across SMS, push and screenshots. */
fun normalize(s: String) = s.lowercase().replace(Regex("""\(주\)|㈜|주식회사|\s"""), "")

fun similar(a: String, b: String): Boolean {
    val x = normalize(a)
    val y = normalize(b)
    return x.isEmpty() || y.isEmpty() || x.contains(y) || y.contains(x)
}

/** The same payment reported twice (SMS + card-app push, or a re-posted notification). */
fun isDuplicate(existing: Tx, amount: Long, at: Long, payId: Long?, merchant: String) =
    existing.amount == amount && kotlin.math.abs(existing.occurredAt - at) <= 3 * MIN &&
        (existing.paymentMethodId == null || payId == null || existing.paymentMethodId == payId) &&
        similar(existing.merchant, merchant)

/** What was paid for a purchase: all months of an installment plan added up. Months 2..n are not purchases. */
suspend fun paid(tx: Tx): Long? = when {
    tx.installmentOf != null -> null
    tx.installmentMonths > 1 -> app.dao.purchaseTotal(tx.id)
    else -> tx.amount
}

fun blockedBy(rules: List<Rule>, text: String, amount: Long?) = rules.any { r ->
    val keywordHit = r.pattern.isBlank() || text.contains(r.pattern, ignoreCase = true)
    val max = r.value.toLongOrNull()
    keywordHit && (max == null || (amount != null && amount <= max)) && (r.pattern.isNotBlank() || max != null)
}

private val payMoneyWords = Regex("페이|머니|pay", RegexOption.IGNORE_CASE)
private val transitWords = Regex("이즐|티머니|캐시비|레일플러스|교통카드", RegexOption.IGNORE_CASE) // 교통카드 충전은 교통비
private val cardWords = Regex("카드")
// a statement or amount-due notice (청구 · 결제예정 · 이번 달 결제금액 …), from the card company or the bank. A purchase
// message always shows its time of day; these show only dates ("[삼성카드]10/13결제금액 887,679원 (10/13출금,10/02기준)")
// 청구 only as 청구금액 · 청구액 …: a bank notice has no time either, and "청구아파트" · "청구역" are real payees
private val billWords = Regex("""청구\s*(금액|액|예정|내역|서)|명세서|이용\s*대금|카드\s*대금|결제\s*대금|결제\s*(예정\s*)?금액|결제\s*하?실\s*금액|결제\s*예정|결제일|출금\s*예정""")
private val clockTime = Regex("""(?<!\d)\d{1,2}:\d{2}(?!\d)""")
private val preAuthWords = Regex("가승인|선승인")
private val savingWords = Regex("적금|청약|정기예금|ISA|IRP|연금저축|증권|투자|펀드")
private val investWords = Regex("증권|투자|펀드|주식|ISA|IRP|연금")
private val subAccountWords = Regex("세이프박스") // 카카오뱅크 통장 속 칸: 옮겨도 내 통장 안

/**
 * The counterparty of a bank transfer is me. Exact full name only: "한전(최만선)" is a real bill, and masked
 * names ("최*선") can't be used because every card message carries the cardholder's own masked name.
 */
fun isMyName(counterparty: String, myNames: String): Boolean {
    val c = normalize(counterparty)
    return c.length >= 2 && myNames.split(',', ' ').any { normalize(it) == c }
}

/**
 * Card / bank / pay notifications → transactions.
 * Order: privacy gate → duplicate text → block rules → local parser → AI parser → cancel / duplicate rules → save.
 * Only money that enters or leaves "me" is recorded (사용자 결정 2026-10-03): transfers between my own accounts,
 * card bill payments, pay-money top-ups and card pre-authorizations are not recorded at all.
 */
object AutoInput {
    private val lock = Mutex() // ponytail: one global lock, notifications arrive a few per minute at most

    suspend fun handle(pkg: String, title: String, body: String, postTime: Long) = lock.withLock {
        val dao = app.dao
        if (!CardParser.looksFinancial(pkg, title, body)) return@withLock
        if (dao.countSameBody(body, postTime - 10 * MIN) > 0) return@withLock

        var parsed = CardParser.parse(title, body, postTime)
        if (blockedBy(dao.rulesOnce(RuleKind.BLOCK), "$pkg $title $body", parsed?.amount)) return@withLock

        val channel = if (pkg in CardParser.smsApps) "SMS" else "PUSH"
        val raw = RawMessage(channel = channel, sender = pkg, title = title, body = body, receivedAt = postTime, status = RawStatus.FAILED)
        val rawId = dao.insert(raw)

        if (parsed == null || parsed.merchant.isBlank()) {
            parsed = Ai.parseMessage(title, body, postTime) ?: parsed
        }
        if (parsed == null || (parsed.amount == null && parsed.foreign == null)) return@withLock // stays FAILED → 확인 필요

        val source = if (channel == "SMS") TxSource.SMS else TxSource.PUSH
        val (txId, status) = apply(parsed, rawId, source, postTime, "$title $body")
        dao.update(raw.copy(id = rawId, status = status, txId = txId))
    }

    /** Re-run a failed message, e.g. after the user turned on AI. */
    suspend fun retry(m: RawMessage): Boolean {
        val parsed = CardParser.parse(m.title, m.body, m.receivedAt)?.takeIf { it.merchant.isNotBlank() }
            ?: Ai.parseMessage(m.title, m.body, m.receivedAt) ?: return false
        if (parsed.amount == null && parsed.foreign == null) return false
        val source = if (m.channel == "SMS") TxSource.SMS else TxSource.PUSH
        val (txId, status) = lock.withLock { apply(parsed, m.id, source, m.receivedAt, "${m.title} ${m.body}") }
        app.dao.update(m.copy(status = status, txId = txId))
        return true
    }

    private suspend fun apply(p: Parsed, rawId: Long, source: TxSource, postTime: Long, text: String): Pair<Long?, RawStatus> {
        val dao = app.dao
        val at = p.at ?: postTime
        val pay = resolvePay(p)
        // only a foreign amount: the day's rate gives the won (TODO #35)
        val fx = if (p.amount == null) Fx.toWon(p.foreign) else null
        val amount = p.amount ?: fx?.first ?: 0L
        val nearby = dao.txAround(at - 5 * MIN, at + 5 * MIN)

        if (p.balance != null && pay != null && pay.kind in setOf(PayKind.BANK, PayKind.PAY_MONEY)) {
            dao.upsert(pay.copy(balance = p.balance))
        }
        if ((p.kind == MsgKind.WITHDRAW || p.kind == MsgKind.DEPOSIT) && amount > 0 && savingWords.containsMatchIn("${p.merchant} $text") && "이자" !in p.merchant) {
            return saving(p, amount, at, pay, nearby, source, rawId, "${p.merchant} $text")
        }
        if (notMoneyInOrOut(p, pay, amount, nearby, text)) return null to RawStatus.IGNORED

        // a cancel looks exactly like its original payment, so it is matched in its own branch below.
        // The same app never reports one payment twice with different text: two 1,000원 at one shop minutes apart are two payments (TODO #48)
        val sender = dao.senderOf(rawId)
        if (p.kind != MsgKind.CANCEL) nearby.firstOrNull {
            amount > 0 && (it.rawMessageId == null || dao.senderOf(it.rawMessageId) != sender) &&
                isDuplicate(it.copy(amount = paid(it) ?: return@firstOrNull false), amount, at, pay?.id, p.merchant)
        }?.let {
            return it.id to RawStatus.DUPLICATE
        }

        val base = Tx(
            amount = amount, occurredAt = at, merchant = p.merchant, paymentMethodId = pay?.id,
            installmentMonths = p.installment, source = source, rawMessageId = rawId,
        )
        var tags = emptyList<Long>()
        val tx: Tx = when (p.kind) {
            MsgKind.CANCEL -> {
                val matches = dao.txAround(at - 31 * DAY, at + DAY).filter {
                    it.type == TxType.EXPENSE && (paid(it) == amount || (p.foreign != null && it.originalAmount == p.foreign)) &&
                        (it.paymentMethodId == null || pay == null || it.paymentMethodId == pay.id) && similar(it.merchant, p.merchant)
                }
                val original = matches.firstOrNull { it.status != TxStatus.CANCELED }
                // the same cancel arriving again by SMS and push
                matches.firstOrNull { it.status == TxStatus.CANCELED && System.currentTimeMillis() - it.updatedAt < 10 * MIN }
                    ?.takeIf { original == null }?.let { return it.id to RawStatus.DUPLICATE }
                if (original != null) {
                    dao.setStatus(original.id, TxStatus.CANCELED)
                    return original.id to RawStatus.PARSED
                }
                // partial cancel or the original was never recorded: a refund lowers spending
                val cat = dao.txAround(at - 31 * DAY, at).firstOrNull { similar(it.merchant, p.merchant) && it.categoryId != null }?.categoryId
                base.copy(amount = -amount, memo = "결제 취소", categoryId = cat, installmentMonths = 0)
            }
            MsgKind.DEPOSIT -> Categorizer.categorize(p.merchant, TxType.INCOME).let { tags = it?.tags.orEmpty(); base.copy(type = TxType.INCOME, categoryId = it?.category) }
            MsgKind.WITHDRAW, MsgKind.SPEND -> Categorizer.categorize(p.merchant, TxType.EXPENSE).let { tags = it?.tags.orEmpty(); base.copy(categoryId = it?.category) }
        }.let { t ->
            if (p.amount == null && fx != null) t.copy(
                originalAmount = p.foreign,
                memo = listOf(t.memo, "해외 결제 · 1 ${p.foreign!!.take(3)} = ${"%,.2f".format(fx.second)}원으로 환산 (카드사 청구액과 조금 다를 수 있어요)").filter { it.isNotBlank() }.joinToString(" · "),
            )
            else if (p.amount == null) t.copy(status = TxStatus.PENDING_REVIEW, originalAmount = p.foreign, memo = "해외 결제: 원화 금액을 확인해 주세요")
            else if (t.merchant.isBlank()) t.copy(status = TxStatus.PENDING_REVIEW)
            else t
        }

        val id = dao.insertPurchase(tx)
        if (tags.isNotEmpty()) dao.setTags(id, tags)
        val saved = tx.copy(id = id) // the whole purchase, also for an installment plan
        val category = saved.categoryId?.let { c -> dao.categoriesOnce().firstOrNull { it.id == c } }
        if (saved.type != TxType.TRANSFER) Notify.saved(saved, category, saved.status == TxStatus.PENDING_REVIEW)
        if (saved.type == TxType.EXPENSE && saved.categoryId == null) app.scope.launch { Categorizer.categorizeWithAi(saved) }
        if (saved.type == TxType.EXPENSE) checkBudget()
        return id to RawStatus.PARSED
    }

    /**
     * Money moved into savings or investments (TODO #28): kept, so neither spending nor an ignored own-account transfer.
     * Put in is positive, taken out negative. The savings account's own notice of the same move is the other half.
     */
    private suspend fun saving(p: Parsed, amount: Long, at: Long, pay: PayMethod?, nearby: List<Tx>, source: TxSource, rawId: Long, text: String): Pair<Long?, RawStatus> {
        val dao = app.dao
        nearby.firstOrNull { it.type == TxType.SAVING && kotlin.math.abs(it.amount) == amount }?.let { return it.id to RawStatus.DUPLICATE }
        val cats = dao.categoriesOnce()
        val tops = cats.tops(TxType.SAVING).filter { !it.hidden }
        val top = tops.firstOrNull { it.name == if (investWords.containsMatchIn(text)) "투자" else "저축" } ?: tops.firstOrNull()
        val hay = text.replace("예금주", "")
        val tags = cats.filter { top != null && it.parentId == top.id && it.name in hay }.map { it.id }
        val tx = Tx(
            type = TxType.SAVING, amount = if (p.kind == MsgKind.WITHDRAW) amount else -amount, occurredAt = at, merchant = p.merchant,
            categoryId = top?.id, paymentMethodId = pay?.id, source = source, rawMessageId = rawId,
        )
        val id = dao.insert(tx)
        dao.setTags(id, tags)
        Notify.saved(tx.copy(id = id), top, needsReview = false)
        return id to RawStatus.PARSED
    }

    private suspend fun isBank(payId: Long?) = payId != null && app.dao.payMethodsOnce().any { it.id == payId && it.kind == PayKind.BANK }

    /** Money that stays mine (or never moved): not recorded. Removes the other half of an own-account transfer. */
    private suspend fun notMoneyInOrOut(p: Parsed, pay: PayMethod?, amount: Long, nearby: List<Tx>, text: String): Boolean {
        // a card hold (가승인/선승인) and its release; the real fare is approved separately
        if (preAuthWords.containsMatchIn(p.merchant) || preAuthWords.containsMatchIn(text)) return true
        val bankMove = p.kind == MsgKind.WITHDRAW || p.kind == MsgKind.DEPOSIT
        if (bankMove && isMyName(p.merchant, app.prefs.myName)) return true
        // a move to or from a box inside my own account (세이프박스) comes as a single notice with no other half
        if (bankMove && (subAccountWords.containsMatchIn(p.merchant) || subAccountWords.containsMatchIn(text))) return true
        // card bill: the card purchases themselves are already recorded
        if (p.kind == MsgKind.WITHDRAW && (cardWords.containsMatchIn(p.merchant) || "카드대금" in text)) return true
        if (p.kind != MsgKind.CANCEL && p.payKind != PayKind.PAY_MONEY && billWords.containsMatchIn(text) && !clockTime.containsMatchIn(text)) return true
        // pay-money top-up (쿠팡페이, 카카오페이머니 …); topping up a transit card is real spending
        if ((p.kind == MsgKind.WITHDRAW || p.isCharge) && !transitWords.containsMatchIn(p.merchant) &&
            (payMoneyWords.containsMatchIn(p.merchant) || findPayByName(p.merchant, setOf(PayKind.PAY_MONEY)) != null)
        ) return true
        // the other half of a transfer between my own bank accounts arrived a moment ago
        if (bankMove && pay?.kind == PayKind.BANK) {
            val otherType = if (p.kind == MsgKind.DEPOSIT) TxType.EXPENSE else TxType.INCOME
            val other = nearby.firstOrNull {
                it.type == otherType && it.amount == amount && it.source != TxSource.MANUAL && it.paymentMethodId != pay.id && isBank(it.paymentMethodId)
            }
            if (other != null) {
                app.dao.deleteTx(other.id)
                app.dao.ignoreRawOf(other.id)
                Notify.cancel(other.id)
                return true
            }
        }
        return false
    }

    private suspend fun resolvePay(p: Parsed): PayMethod? {
        val issuer = p.issuer ?: return null
        val kind = p.payKind ?: return null
        val group = if (kind == PayKind.CREDIT || kind == PayKind.CHECK) setOf(PayKind.CREDIT, PayKind.CHECK) else setOf(kind)
        val candidates = app.dao.payMethodsOnce().filter { it.issuer == issuer && it.kind in group }
        candidates.firstOrNull { p.last4 != null && it.last4 == p.last4 }?.let { return it }
        candidates.firstOrNull { it.last4.isEmpty() || p.last4 == null }?.let { return it }
        val name = p.payName + (p.last4?.let { "($it)" } ?: "")
        val created = PayMethod(kind = kind, name = name, issuer = issuer, last4 = p.last4.orEmpty())
        return created.copy(id = app.dao.upsert(created))
    }

    private suspend fun findPayByName(name: String, kinds: Set<PayKind>) = app.dao.payMethodsOnce().firstOrNull { pm ->
        pm.kind in kinds && (similar(pm.name, name) || pm.aliases.split(',').any { it.isNotBlank() && similar(it, name) } ||
            (pm.issuer.isNotEmpty() && name.contains(pm.issuer)))
    }

    /** 50 · 80 · 100% of a whole budget, once per step and period. prefs.budgetAlert: "WEEK=2026-10-05:80;MONTH=2026-10-01:50". */
    suspend fun checkBudget() {
        val dao = app.dao
        val budgets = dao.budgetsOnce().filter { it.categoryId == null && it.amount > 0 && app.prefs.shows(it) }
        if (budgets.isEmpty()) return
        val today = LocalDate.now()
        val weekStart = java.time.DayOfWeek.of(app.prefs.weekStart)
        val span = budgetSpan(budgets, today, app.prefs.monthStartDay, weekStart)
        val sent = app.prefs.budgetAlert.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        for (u in budgetUses(budgets, dao.txBetweenOnce(span.startMillis, span.endMillis), emptyList(), emptyList(), today, app.prefs.monthStartDay, weekStart)) {
            val step = listOf(100, 80, 50).firstOrNull { u.percent >= it } ?: continue
            val last = sent[u.budget.period.name]
            if (last?.substringBefore(':') == u.period.start.toString() && (last.substringAfter(':').toIntOrNull() ?: 0) >= step) continue
            sent[u.budget.period.name] = "${u.period.start}:$step"
            Notify.budget(u.label, step, u.left)
        }
        app.prefs.budgetAlert = sent.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}
