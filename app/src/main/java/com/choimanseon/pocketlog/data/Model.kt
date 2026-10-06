package com.choimanseon.pocketlog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** SAVING (저축 · 투자, TODO #28) is money kept, not spent: positive when put in, negative when taken out. */
enum class TxType { EXPENSE, INCOME, TRANSFER, SAVING }
enum class TxStatus { CONFIRMED, PENDING_REVIEW, CANCELED }
enum class TxSource { MANUAL, SMS, PUSH, SCREENSHOT, RECEIPT, VOICE, IMPORT, DUMMY, REPEAT }
enum class PayKind { CREDIT, CHECK, BANK, PAY_MONEY, CASH }
enum class RuleKind { CATEGORY, BLOCK, SOURCE_DEFAULT_PAYMENT }
enum class RawStatus { PARSED, DUPLICATE, FAILED, IGNORED }
enum class ScanStatus { RUNNING, DONE, FAILED, SAVED }
enum class Repeat { NONE, WEEKLY, MONTHLY }

/** Money is always whole won in a Long. A refund is an EXPENSE with a negative amount. */
@Entity(indices = [Index("occurredAt"), Index("categoryId"), Index("paymentMethodId")])
data class Tx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType = TxType.EXPENSE,
    val amount: Long,
    val currency: String = "KRW",
    val originalAmount: String? = null, // foreign amount as shown, e.g. "USD 12.99"
    val occurredAt: Long,
    val merchant: String = "",
    val memo: String = "", // shown as 품명: what was bought, written by the app too (a scan's items, 할부 n/m회차, 결제 취소)
    @ColumnInfo(defaultValue = "") val note: String = "", // 메모: only the user's own words (DB 7)
    val categoryId: Long? = null,
    val paymentMethodId: Long? = null,
    val toPaymentMethodId: Long? = null,
    val installmentMonths: Int = 0,
    val installmentOf: Long? = null, // months 2..n of an installment purchase point to month 1 (see installmentRows)
    val status: TxStatus = TxStatus.CONFIRMED,
    val source: TxSource = TxSource.MANUAL,
    val rawMessageId: Long? = null,
    val scanJobId: Long? = null,
    val excludeFromStats: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** Line items of one Tx (e.g. a Coupang order). Stats use splits instead of Tx.categoryId when present. */
@Entity(
    foreignKeys = [ForeignKey(entity = Tx::class, parentColumns = ["id"], childColumns = ["txId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("txId")],
)
data class TxSplit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txId: Long,
    val name: String,
    val quantity: Int = 1,
    val amount: Long,
    val categoryId: Long?,
)

/**
 * A top row (no [parentId]) is a category; a transaction has exactly one.
 * A row with [parentId] is a tag of that category (TODO #27, the subcategories until DB version 4); a transaction has any number, see [TxTag].
 * The [tagGroup] row of a type holds the tags offered with every category of that type (공통 태그). It never holds transactions.
 */
@Entity
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val name: String,
    @ColumnInfo(name = "emoji") val icon: String, // a [com.choimanseon.pocketlog.ui.CategoryIcons] key; the column held emoji until DB version 3
    val color: Long,
    val parentId: Long? = null,
    val sort: Int = 0,
    val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val tagGroup: Boolean = false,
)

/** The tags of a transaction. Every month of an installment plan carries the purchase's tags. */
@Entity(
    primaryKeys = ["txId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Tx::class, parentColumns = ["id"], childColumns = ["txId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class TxTag(val txId: Long, val tagId: Long)

/** A payment method that may also hold a balance (bank account, pay money, cash). */
@Entity
data class PayMethod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: PayKind,
    val name: String,
    val issuer: String = "",
    val last4: String = "",
    val aliases: String = "", // comma separated
    val balance: Long? = null,
    val billingDay: Int? = null,
    val sort: Int = 0,
    val hidden: Boolean = false,
)

/** A budget for one [period] (주 · 월 · 연, TODO #35); categoryId null = whole budget. A category can have one of each. */
@Entity
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long? = null,
    val amount: Long,
    @ColumnInfo(defaultValue = "MONTH") val period: com.choimanseon.pocketlog.domain.PeriodUnit = com.choimanseon.pocketlog.domain.PeriodUnit.MONTH,
)

/**
 * 즐겨찾기: a filled-in entry the entry sheet offers in its 즐겨찾기 list. With [repeat] it also records itself on its day
 * ([repeatDay]: 1 = Monday … 7 for WEEKLY, day of month for MONTHLY, past the month's end = its last day) at 9:00.
 */
@Entity
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType = TxType.EXPENSE,
    val amount: Long,
    val merchant: String = "",
    val categoryId: Long? = null,
    val tags: String = "", // tag ids, "3,7"
    val paymentMethodId: Long? = null,
    val memo: String = "",
    val repeat: Repeat = Repeat.NONE,
    val repeatDay: Int = 1,
    val nextAt: Long? = null, // the next automatic record, null when it doesn't repeat
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val sort: Int = 0, // 사용자 정의 order (TODO #47); a new one goes first
    @ColumnInfo(defaultValue = "0") val dummy: Boolean = false, // made by 더미 데이터 넣기, removed with it (TODO #52)
)

/** AI 월간 리포트 for the month period starting on [start] (ISO date): the numbers sent and the AI's text, as JSON. */
@Entity
data class Report(
    @PrimaryKey val start: String,
    val end: String,
    val json: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * CATEGORY: pattern = normalized keyword, value = categoryId
 * BLOCK: pattern = keyword ("" = any), value = max amount to block ("" = any amount)
 * SOURCE_DEFAULT_PAYMENT: pattern = source app (e.g. "coupang"), value = payMethodId
 */
@Entity(indices = [Index("kind", "pattern")])
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: RuleKind,
    val pattern: String,
    val value: String,
)

/** Original SMS / push text, kept on the device only. */
@Entity(indices = [Index("receivedAt")])
data class RawMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String, // SMS | PUSH
    val sender: String,  // package name
    val title: String,
    val body: String,
    val receivedAt: Long,
    val status: RawStatus,
    val txId: Long? = null,
)

@Entity(indices = [Index("imageHash")])
data class ScanJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageHash: String,
    val imageCount: Int,
    val status: ScanStatus,
    val resultJson: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
