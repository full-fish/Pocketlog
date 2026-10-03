package com.choimanseon.pocketlog.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.choimanseon.pocketlog.domain.installmentRows
import kotlinx.coroutines.flow.Flow

/** The rows of the purchase containing :id: just that row, or every month of its installment plan. */
private const val PURCHASE = "COALESCE((SELECT installmentOf FROM Tx WHERE id = :id), :id) IN (id, installmentOf)"

@Dao
interface PocketDao {
    // ---- transactions
    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end ORDER BY occurredAt DESC")
    fun txBetween(start: Long, end: Long): Flow<List<Tx>>

    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end")
    suspend fun txBetweenOnce(start: Long, end: Long): List<Tx>

    @Query("SELECT * FROM TxSplit WHERE txId IN (SELECT id FROM Tx WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end)")
    fun splitsBetween(start: Long, end: Long): Flow<List<TxSplit>>

    @Query("SELECT * FROM TxSplit WHERE txId IN (SELECT id FROM Tx WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end)")
    suspend fun splitsBetweenOnce(start: Long, end: Long): List<TxSplit>

    @Query("SELECT * FROM Tx WHERE id = :id")
    fun tx(id: Long): Flow<Tx?>

    @Query("SELECT * FROM Tx WHERE id = :id")
    suspend fun txOnce(id: Long): Tx?

    @Query("SELECT * FROM TxSplit WHERE txId = :txId ORDER BY id")
    fun splitsOf(txId: Long): Flow<List<TxSplit>>

    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    fun recentTx(limit: Int): Flow<List<Tx>>

    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL AND status = 'PENDING_REVIEW' ORDER BY occurredAt DESC")
    fun pendingTx(): Flow<List<Tx>>

    @Query(
        """SELECT * FROM Tx WHERE deletedAt IS NULL
        AND (:q = '' OR merchant LIKE '%' || :q || '%' OR memo LIKE '%' || :q || '%')
        AND (:min IS NULL OR ABS(amount) >= :min) AND (:max IS NULL OR ABS(amount) <= :max)
        AND (:cat IS NULL OR categoryId = :cat
             OR categoryId IN (SELECT id FROM Category WHERE parentId = :cat)
             OR id IN (SELECT txId FROM TxSplit WHERE categoryId = :cat
                       OR categoryId IN (SELECT id FROM Category WHERE parentId = :cat)))
        ORDER BY occurredAt DESC LIMIT 300"""
    )
    fun search(q: String, cat: Long?, min: Long?, max: Long?): Flow<List<Tx>>

    @Query("SELECT merchant FROM Tx WHERE deletedAt IS NULL AND merchant != '' AND merchant LIKE :prefix || '%' GROUP BY merchant ORDER BY MAX(occurredAt) DESC LIMIT 5")
    suspend fun merchantsLike(prefix: String): List<String>

    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL AND merchant = :merchant ORDER BY occurredAt DESC LIMIT 1")
    suspend fun lastWithMerchant(merchant: String): Tx?

    /** Candidates for duplicate / cancel / transfer matching. */
    @Query("SELECT * FROM Tx WHERE deletedAt IS NULL AND occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC")
    suspend fun txAround(from: Long, to: Long): List<Tx>

    @Insert suspend fun insert(tx: Tx): Long
    @Update suspend fun update(tx: Tx)
    @Insert suspend fun insertSplits(splits: List<TxSplit>)
    @Query("DELETE FROM TxSplit WHERE txId = :txId") suspend fun deleteSplits(txId: Long)

    @Transaction
    suspend fun insertWithSplits(tx: Tx, splits: List<TxSplit>): Long {
        val id = insert(tx)
        if (splits.isNotEmpty()) insertSplits(splits.map { it.copy(txId = id) })
        return id
    }

    /** Every new purchase goes through here so installments become monthly rows. Returns month 1's id. */
    @Transaction
    suspend fun insertPurchase(tx: Tx, splits: List<TxSplit> = emptyList()): Long {
        val rows = installmentRows(tx)
        val id = insertWithSplits(rows.first(), splits)
        rows.drop(1).forEach { insert(it.copy(installmentOf = id)) }
        return id
    }

    /** An edited purchase: month 1 keeps its id, months 2..n are written again. */
    @Transaction
    suspend fun replacePurchase(tx: Tx) {
        deleteLaterInstallments(tx.id)
        val rows = installmentRows(tx)
        update(rows.first())
        rows.drop(1).forEach { insert(it.copy(installmentOf = tx.id)) }
    }

    @Query("DELETE FROM Tx WHERE installmentOf = :id")
    suspend fun deleteLaterInstallments(id: Long)

    @Query("SELECT * FROM Tx WHERE $PURCHASE ORDER BY occurredAt")
    suspend fun purchaseRows(id: Long): List<Tx>

    @Query("SELECT * FROM Tx WHERE $PURCHASE ORDER BY occurredAt")
    fun purchaseRowsFlow(id: Long): Flow<List<Tx>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM Tx WHERE $PURCHASE")
    suspend fun purchaseTotal(id: Long): Long

    @Query("UPDATE Tx SET deletedAt = :now, updatedAt = :now WHERE $PURCHASE")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE Tx SET deletedAt = NULL WHERE $PURCHASE")
    suspend fun undoDelete(id: Long)

    @Query("UPDATE Tx SET status = :status, updatedAt = :now WHERE $PURCHASE")
    suspend fun setStatus(id: Long, status: TxStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE Tx SET categoryId = :categoryId, updatedAt = :now WHERE $PURCHASE")
    suspend fun setCategory(id: Long, categoryId: Long?, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM Tx WHERE id = :id")
    suspend fun deleteTx(id: Long)

    @Query("UPDATE RawMessage SET status = 'IGNORED', txId = NULL WHERE txId = :txId")
    suspend fun ignoreRawOf(txId: Long)

    // ---- categories
    @Query("SELECT * FROM Category ORDER BY sort, id")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM Category ORDER BY sort, id")
    suspend fun categoriesOnce(): List<Category>

    /** One transaction: a first launch killed mid-seed must not leave half the categories behind forever. */
    @Transaction
    suspend fun seedIfEmpty() {
        if (categoriesOnce().isEmpty()) insertDefaults()
    }

    @Upsert suspend fun upsert(c: Category): Long
    @Insert suspend fun insertCategories(c: List<Category>): List<Long>
    @Delete suspend fun delete(c: Category)

    @Transaction
    suspend fun mergeCategory(from: Long, to: Long) {
        moveTxCategory(from, to)
        moveSplitCategory(from, to)
        moveChildren(from, to)
        moveRuleCategory(from.toString(), to.toString())
        deleteBudgetsOf(from)
        deleteCategory(from)
    }
    @Query("UPDATE Tx SET categoryId = :to WHERE categoryId = :from") suspend fun moveTxCategory(from: Long, to: Long)
    @Query("UPDATE TxSplit SET categoryId = :to WHERE categoryId = :from") suspend fun moveSplitCategory(from: Long, to: Long)
    @Query("UPDATE Category SET parentId = :to WHERE parentId = :from") suspend fun moveChildren(from: Long, to: Long)
    @Query("UPDATE `Rule` SET value = :to WHERE kind = 'CATEGORY' AND value = :from") suspend fun moveRuleCategory(from: String, to: String)
    @Query("DELETE FROM Budget WHERE categoryId = :id") suspend fun deleteBudgetsOf(id: Long)
    @Query("DELETE FROM Category WHERE id = :id") suspend fun deleteCategory(id: Long)

    // ---- payment methods
    @Query("SELECT * FROM PayMethod ORDER BY sort, id")
    fun payMethods(): Flow<List<PayMethod>>

    @Query("SELECT * FROM PayMethod ORDER BY sort, id")
    suspend fun payMethodsOnce(): List<PayMethod>

    @Upsert suspend fun upsert(p: PayMethod): Long

    // ---- budgets
    @Query("SELECT * FROM Budget")
    fun budgets(): Flow<List<Budget>>

    @Query("SELECT * FROM Budget")
    suspend fun budgetsOnce(): List<Budget>

    @Upsert suspend fun upsert(b: Budget): Long
    @Delete suspend fun delete(b: Budget)

    // ---- rules
    @Query("SELECT * FROM `Rule` WHERE kind = :kind ORDER BY id DESC")
    fun rules(kind: RuleKind): Flow<List<Rule>>

    @Query("SELECT * FROM `Rule` WHERE kind = :kind ORDER BY id DESC")
    suspend fun rulesOnce(kind: RuleKind): List<Rule>

    @Query("DELETE FROM `Rule` WHERE kind = :kind AND pattern = :pattern")
    suspend fun deleteRule(kind: RuleKind, pattern: String)

    @Insert suspend fun insert(r: Rule): Long
    @Delete suspend fun delete(r: Rule)

    @Transaction
    suspend fun putRule(kind: RuleKind, pattern: String, value: String) {
        deleteRule(kind, pattern)
        insert(Rule(kind = kind, pattern = pattern, value = value))
    }

    // ---- raw messages
    @Insert suspend fun insert(m: RawMessage): Long
    @Update suspend fun update(m: RawMessage)

    @Query("SELECT * FROM RawMessage WHERE id = :id")
    fun rawMessage(id: Long): Flow<RawMessage?>

    @Query("SELECT * FROM RawMessage WHERE status = 'FAILED' ORDER BY receivedAt DESC")
    fun failedMessages(): Flow<List<RawMessage>>

    @Query("SELECT COUNT(*) FROM RawMessage WHERE body = :body AND receivedAt > :since")
    suspend fun countSameBody(body: String, since: Long): Int

    // ---- import (똑똑가계부): everything is replaced in one transaction
    @Query("DELETE FROM TxSplit") suspend fun deleteAllSplits()
    @Query("DELETE FROM Tx") suspend fun deleteAllTx()
    @Query("DELETE FROM Category") suspend fun deleteAllCategories()
    @Query("DELETE FROM PayMethod") suspend fun deleteAllPayMethods()
    @Query("DELETE FROM Budget") suspend fun deleteAllBudgets()
    @Query("DELETE FROM `Rule` WHERE kind = 'CATEGORY'") suspend fun deleteCategoryRules()
    @Query("DELETE FROM `Rule` WHERE kind = 'SOURCE_DEFAULT_PAYMENT'") suspend fun deleteSourceDefaults()
    @Query("DELETE FROM RawMessage") suspend fun deleteAllRaw()
    @Insert suspend fun insertPayMethods(p: List<PayMethod>)
    @Insert suspend fun insertTxs(t: List<Tx>)
    @Insert suspend fun insertRules(r: List<Rule>)

    /** Block rules are kept: they are the user's settings, not data. */
    @Transaction
    suspend fun replaceAll(categories: List<Category>, pays: List<PayMethod>, txs: List<Tx>, rules: List<Rule>) {
        deleteAllSplits()
        deleteAllTx()
        deleteAllRaw()
        deleteAllBudgets()
        deleteCategoryRules()
        deleteSourceDefaults()
        deleteAllCategories()
        deleteAllPayMethods()
        insertCategories(categories)
        insertPayMethods(pays)
        insertTxs(txs)
        insertRules(rules)
    }

    // ---- scans
    @Insert suspend fun insert(j: ScanJob): Long
    @Update suspend fun update(j: ScanJob)

    @Query("SELECT * FROM ScanJob WHERE id = :id")
    fun scanJob(id: Long): Flow<ScanJob?>

    @Query("SELECT * FROM ScanJob WHERE id = :id")
    suspend fun scanJobOnce(id: Long): ScanJob?

    @Query("SELECT * FROM ScanJob WHERE imageHash = :hash AND status IN ('DONE', 'SAVED') ORDER BY id DESC LIMIT 1")
    suspend fun scanByHash(hash: String): ScanJob?
}
