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
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.total
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

fun blockedBy(rules: List<Rule>, text: String, amount: Long?) = rules.any { r ->
    val keywordHit = r.pattern.isBlank() || text.contains(r.pattern, ignoreCase = true)
    val max = r.value.toLongOrNull()
    keywordHit && (max == null || (amount != null && amount <= max)) && (r.pattern.isNotBlank() || max != null)
}

private val payMoneyWords = Regex("페이|머니|pay", RegexOption.IGNORE_CASE)
private val transitWords = Regex("이즐|티머니|캐시비|레일플러스|교통카드", RegexOption.IGNORE_CASE) // 교통카드 충전은 교통비
private val cardWords = Regex("카드")
private val preAuthWords = Regex("가승인|선승인")

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
        val amount = p.amount ?: 0L
        val nearby = dao.txAround(at - 5 * MIN, at + 5 * MIN)

        if (p.balance != null && pay != null && pay.kind in setOf(PayKind.BANK, PayKind.PAY_MONEY)) {
            dao.upsert(pay.copy(balance = p.balance))
        }
        if (notMoneyInOrOut(p, pay, amount, nearby, text)) return null to RawStatus.IGNORED

        // a cancel looks exactly like its original payment, so it is matched in its own branch below
        if (p.kind != MsgKind.CANCEL) nearby.firstOrNull { isDuplicate(it, amount, at, pay?.id, p.merchant) && amount > 0 }?.let {
            return it.id to RawStatus.DUPLICATE
        }

        val base = Tx(
            amount = amount, occurredAt = at, merchant = p.merchant, paymentMethodId = pay?.id,
            installmentMonths = p.installment, source = source, rawMessageId = rawId,
        )
        val tx: Tx = when (p.kind) {
            MsgKind.CANCEL -> {
                val matches = dao.txAround(at - 31 * DAY, at + DAY).filter {
                    it.type == TxType.EXPENSE && it.amount == amount &&
                        (it.paymentMethodId == null || pay == null || it.paymentMethodId == pay.id) && similar(it.merchant, p.merchant)
                }
                val original = matches.firstOrNull { it.status != TxStatus.CANCELED }
                // the same cancel arriving again by SMS and push
                matches.firstOrNull { it.status == TxStatus.CANCELED && System.currentTimeMillis() - it.updatedAt < 10 * MIN }
                    ?.takeIf { original == null }?.let { return it.id to RawStatus.DUPLICATE }
                if (original != null) {
                    dao.update(original.copy(status = TxStatus.CANCELED, updatedAt = System.currentTimeMillis()))
                    return original.id to RawStatus.PARSED
                }
                // partial cancel or the original was never recorded: a refund lowers spending
                val cat = dao.txAround(at - 31 * DAY, at).firstOrNull { similar(it.merchant, p.merchant) && it.categoryId != null }?.categoryId
                base.copy(amount = -amount, memo = "결제 취소", categoryId = cat)
            }
            MsgKind.DEPOSIT -> base.copy(type = TxType.INCOME, categoryId = Categorizer.categorize(p.merchant, TxType.INCOME))
            MsgKind.WITHDRAW, MsgKind.SPEND -> base.copy(categoryId = Categorizer.categorize(p.merchant, TxType.EXPENSE))
        }.let { t ->
            if (p.amount == null) t.copy(status = TxStatus.PENDING_REVIEW, originalAmount = p.foreign, memo = "해외 결제: 원화 금액을 확인해 주세요")
            else if (t.merchant.isBlank()) t.copy(status = TxStatus.PENDING_REVIEW)
            else t
        }

        val id = dao.insert(tx)
        val saved = tx.copy(id = id)
        val category = saved.categoryId?.let { c -> dao.categoriesOnce().firstOrNull { it.id == c } }
        if (saved.type != TxType.TRANSFER) Notify.saved(saved, category, saved.status == TxStatus.PENDING_REVIEW)
        if (saved.type == TxType.EXPENSE && saved.categoryId == null) app.scope.launch { Categorizer.categorizeWithAi(saved) }
        if (saved.type == TxType.EXPENSE) checkBudget()
        return id to RawStatus.PARSED
    }

    private suspend fun isBank(payId: Long?) = payId != null && app.dao.payMethodsOnce().any { it.id == payId && it.kind == PayKind.BANK }

    /** Money that stays mine (or never moved): not recorded. Removes the other half of an own-account transfer. */
    private suspend fun notMoneyInOrOut(p: Parsed, pay: PayMethod?, amount: Long, nearby: List<Tx>, text: String): Boolean {
        // a card hold (가승인/선승인) and its release; the real fare is approved separately
        if (preAuthWords.containsMatchIn(p.merchant) || preAuthWords.containsMatchIn(text)) return true
        val bankMove = p.kind == MsgKind.WITHDRAW || p.kind == MsgKind.DEPOSIT
        if (bankMove && isMyName(p.merchant, app.prefs.myName)) return true
        // card bill: the card purchases themselves are already recorded
        if (p.kind == MsgKind.WITHDRAW && (cardWords.containsMatchIn(p.merchant) || "카드대금" in text)) return true
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

    suspend fun checkBudget() {
        val dao = app.dao
        val budget = dao.budgetsOnce().firstOrNull { it.categoryId == null && it.amount > 0 } ?: return
        val period = monthPeriod(LocalDate.now(), app.prefs.monthStartDay)
        val spent = total(dao.txBetweenOnce(period.startMillis, period.endMillis), TxType.EXPENSE)
        val percent = (spent * 100 / budget.amount).toInt()
        val step = listOf(100, 80, 50).firstOrNull { percent >= it } ?: return
        val key = "${period.start}:$step"
        val last = app.prefs.budgetAlert
        if (last.substringBefore(':') == period.start.toString() && (last.substringAfter(':').toIntOrNull() ?: 0) >= step) return
        app.prefs.budgetAlert = key
        Notify.budget(step, budget.amount - spent)
    }
}
