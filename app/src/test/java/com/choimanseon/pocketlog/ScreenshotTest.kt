package com.choimanseon.pocketlog

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.background
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.choimanseon.pocketlog.data.Budget
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.ui.Nav
import com.choimanseon.pocketlog.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Renders the real screens with sample data into PNG files under app/build/screenshots.
 * Not an assertion test: it proves the screens compose without crashing and lets a human look at them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(800) // let enter animations settle
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        roots.indices.forEach { i ->
            val bmp = compose.onAllNodes(isRoot())[i].captureToImage().asAndroidBitmap()
            File(out, if (i == 0) "$name.png" else "$name-layer$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun nav(): Nav = MainActivity::class.java.getDeclaredField("nav").run { isAccessible = true; get(compose.activity) as Nav }

    @Test
    fun screens() {
        shot("0-onboarding-1")
        compose.onNodeWithText("건너뛰기").performClick()
        shot("0-onboarding-2")
        compose.onNodeWithText("다음").performClick()
        shot("0-onboarding-3")
        compose.onNodeWithText("다음").performClick()
        shot("0-onboarding-4")
        compose.onNodeWithText("새로 시작하기").performClick()

        val (scanId, orderId) = runBlocking(Dispatchers.IO) { seedSample() }
        compose.waitForIdle()
        shot("1-home")

        compose.onNodeWithText("내역").performClick()
        shot("2-history")
        // swipe the month (anywhere but a row), then come back
        val period = monthPeriod(LocalDate.now(), 25)
        compose.onNodeWithText("남은 돈", substring = true).performTouchInput { swipeLeft() }
        compose.onNodeWithText(period.shiftMonths(1).label()).assertExists()
        compose.onNodeWithText("남은 돈", substring = true).performTouchInput { swipeRight() }
        compose.onNodeWithText(period.label()).assertExists()
        // long-press starts selecting
        compose.onNodeWithText("김밥천국").performTouchInput { longClick() }
        compose.onNodeWithText("스타벅스코리아").performClick()
        compose.onNodeWithText("2건 선택").assertExists()
        shot("2b-history-select")
        compose.onNodeWithContentDescription("선택 취소").performClick()
        compose.onNodeWithContentDescription("달력으로 보기").performClick()
        shot("3-calendar")

        compose.onNodeWithText("분석").performClick()
        shot("4-stats")

        compose.onNodeWithText("자산").performClick()
        shot("5-assets")

        compose.onNodeWithText("홈").performClick()
        compose.runOnUiThread { nav().push(Screen.Detail(orderId)) }
        shot("6-detail")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.ScanResult(scanId)) }
        shot("7-scan-review")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.Settings) }
        shot("8-settings")
        compose.runOnUiThread { nav().pop() }

        app.prefs.theme = "dark"
        shot("10-home-dark")
    }

    private suspend fun seedSample(): Pair<Long, Long> {
        val dao = app.dao
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
        app.prefs.monthStartDay = 25
        val cats = dao.categoriesOnce().associateBy { it.name }
        fun cat(name: String) = cats.getValue(name).id
        val samsung = dao.upsert(PayMethod(kind = PayKind.CREDIT, name = "삼성카드(1234)", issuer = "삼성", last4 = "1234"))
        val kb = dao.upsert(PayMethod(kind = PayKind.CHECK, name = "KB국민체크", issuer = "KB국민"))
        val toss = dao.upsert(PayMethod(kind = PayKind.PAY_MONEY, name = "토스머니", issuer = "토스"))
        val kakao = dao.upsert(PayMethod(kind = PayKind.BANK, name = "카카오뱅크", issuer = "카카오뱅크", balance = 1_234_567))
        val coupang = dao.upsert(PayMethod(kind = PayKind.PAY_MONEY, name = "쿠팡머니", aliases = "쿠팡페이,쿠페이", balance = 32_000))
        dao.upsert(Budget(amount = 1_000_000))
        dao.upsert(Budget(categoryId = cat("식비"), amount = 300_000))

        val today = LocalDate.now()
        val period = monthPeriod(today, 25)
        fun at(daysAgo: Long, h: Int, m: Int): Long {
            val d = today.minusDays(daysAgo).let { if (it.isBefore(period.start)) period.start else it }
            return d.atTime(LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val txs = listOf(
            Tx(amount = 8000, occurredAt = at(0, 12, 10), merchant = "김밥천국", categoryId = cat("식비"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 5600, occurredAt = at(0, 9, 5), merchant = "스타벅스코리아", categoryId = cat("카페"), paymentMethodId = kb, source = TxSource.PUSH),
            Tx(amount = 39900, occurredAt = at(1, 19, 30), merchant = "(주)데일리샷", categoryId = cat("술·음료"), paymentMethodId = kb, source = TxSource.SMS),
            Tx(amount = 1550, occurredAt = at(1, 18, 50), merchant = "ezl 지하철 1건 이용", categoryId = cat("교통"), paymentMethodId = toss, source = TxSource.PUSH),
            Tx(amount = 2400, occurredAt = at(3, 19, 25), merchant = "CU(씨유)자양승일점", categoryId = cat("편의점"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 17000, occurredAt = at(4, 3, 15), merchant = "넷플릭스", categoryId = cat("구독"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 0, occurredAt = at(4, 3, 16), merchant = "NETFLIX.COM", paymentMethodId = samsung, source = TxSource.SMS, status = TxStatus.PENDING_REVIEW, originalAmount = "USD 12.99"),
            Tx(type = TxType.INCOME, amount = 3_200_000, occurredAt = at(6, 9, 0), merchant = "(주)회사이름 급여", categoryId = cat("급여"), paymentMethodId = kakao, source = TxSource.PUSH),
            Tx(type = TxType.TRANSFER, amount = 50_000, occurredAt = at(5, 10, 0), merchant = "쿠팡페이", paymentMethodId = kakao, toPaymentMethodId = coupang, source = TxSource.PUSH, memo = "충전"),
            Tx(amount = 23000, occurredAt = at(6, 20, 0), merchant = "교촌치킨", categoryId = cat("배달"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 12300, occurredAt = at(2, 19, 43), merchant = "주식회사앨리스프랜즈", categoryId = cat("쇼핑"), paymentMethodId = samsung, source = TxSource.SMS, status = TxStatus.CANCELED),
        )
        txs.forEach { dao.insert(it) }
        val orderId = dao.insertWithSplits(
            Tx(amount = 52300, occurredAt = at(2, 12, 0), merchant = "쿠팡", memo = "데일리샷 와인 외 2개", categoryId = cat("술·음료"), paymentMethodId = coupang, source = TxSource.SCREENSHOT),
            listOf(
                TxSplit(txId = 0, name = "데일리샷 와인", amount = 29900, categoryId = cat("술·음료")),
                TxSplit(txId = 0, name = "생수 2L", quantity = 12, amount = 12400, categoryId = cat("장보기")),
                TxSplit(txId = 0, name = "키친타월 6롤", amount = 9000, categoryId = cat("생활용품")),
                TxSplit(txId = 0, name = "배송비·할인", amount = 1000, categoryId = cat("술·음료")),
            ),
        )
        // earlier months for the 6-month chart
        listOf(1_180_000L, 940_000L, 1_320_000L, 870_000L, 1_050_000L).forEachIndexed { i, amount ->
            val d = period.start.minusMonths((i + 1).toLong()).plusDays(3)
            dao.insert(Tx(amount = amount, occurredAt = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), merchant = "지난달 지출", categoryId = cat("기타")))
        }
        val result = """
            {"source_app":"coupang","document_type":"order_list","warnings":[],"transactions":[
             {"date":"${today.minusDays(1)}","time":null,"merchant":"쿠팡","total_amount":39900,"currency":"KRW","payment_hint":null,"status":"paid",
              "items":[{"name":"데일리샷 와인","quantity":1,"amount":29900,"category_id":"${cat("술·음료")}"},{"name":"키친타월 6롤","quantity":1,"amount":9000,"category_id":"${cat("생활용품")}"}],
              "shipping_fee":1000,"discount":0,"confidence":0.95},
             {"date":"${today.minusDays(2)}","time":null,"merchant":"쿠팡","total_amount":12400,"currency":"KRW","payment_hint":"쿠팡머니","status":"paid",
              "items":[{"name":"생수 2L","quantity":12,"amount":12400,"category_id":"${cat("장보기")}"}],"shipping_fee":0,"discount":0,"confidence":0.9},
             {"date":"${today.minusDays(5)}","time":null,"merchant":"쿠팡","total_amount":8900,"currency":"KRW","payment_hint":null,"status":"refunded",
              "items":[{"name":"휴대폰 케이스","quantity":1,"amount":8900,"category_id":"${cat("쇼핑")}"}],"shipping_fee":0,"discount":0,"confidence":0.9}
            ]}
        """.trimIndent()
        return dao.insert(ScanJob(imageHash = "sample", imageCount = 0, status = ScanStatus.DONE, resultJson = result)) to orderId
    }
}

/** The entry sheet lives in its own window, which Robolectric can't capture, so its form is rendered directly. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class EntryScreenshotTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    @Test
    fun entryForm() {
        compose.setContent {
            com.choimanseon.pocketlog.ui.PocketTheme(false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                    com.choimanseon.pocketlog.ui.EntryForm(com.choimanseon.pocketlog.ui.Entry(), Nav(), {}, {})
                }
            }
        }
        listOf("1", "2", "000", "+", "3", "000").forEach { compose.onNodeWithText(it).performClick() }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").mkdirs()
        File("build/screenshots/9-entry-form.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Screens with a real 똑똑가계부 backup imported. Runs only with CLEV_DB set; output stays in build/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class RealDataScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(800)
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        File("build/screenshots").mkdirs()
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File("build/screenshots/real-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun screens() {
        val path = System.getenv("CLEV_DB")
        org.junit.Assume.assumeTrue(path != null && File(path).exists())
        app.prefs.onboarded = true
        runBlocking(Dispatchers.IO) {
            while (app.dao.categoriesOnce().isEmpty()) delay(20)
            val started = System.currentTimeMillis()
            com.choimanseon.pocketlog.data.ClevImport.run(File(path!!))
            println("import took ${System.currentTimeMillis() - started} ms")
        }
        shot("1-home")
        compose.onNodeWithText("내역").performClick()
        compose.onNodeWithContentDescription("이전").performClick()
        shot("2-history-prev-month")
        compose.onNodeWithText("분석").performClick()
        compose.onNodeWithContentDescription("이전").performClick()
        shot("3-stats-prev-month")
        val nav = MainActivity::class.java.getDeclaredField("nav").run { isAccessible = true; get(compose.activity) as Nav }
        compose.runOnUiThread { nav.push(Screen.Categories) }
        shot("4-categories")
    }
}
