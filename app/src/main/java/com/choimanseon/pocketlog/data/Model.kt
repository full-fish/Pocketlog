package com.choimanseon.pocketlog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TxType { EXPENSE, INCOME, TRANSFER }
enum class TxStatus { CONFIRMED, PENDING_REVIEW, CANCELED }
enum class TxSource { MANUAL, SMS, PUSH, SCREENSHOT, RECEIPT, VOICE, IMPORT, DUMMY }
enum class PayKind { CREDIT, CHECK, BANK, PAY_MONEY, CASH }
enum class RuleKind { CATEGORY, BLOCK, SOURCE_DEFAULT_PAYMENT }
enum class RawStatus { PARSED, DUPLICATE, FAILED, IGNORED }
enum class ScanStatus { RUNNING, DONE, FAILED, SAVED }

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
    val memo: String = "",
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
)

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

/** Monthly budget; categoryId null = whole budget. */
@Entity
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long? = null,
    val amount: Long,
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
