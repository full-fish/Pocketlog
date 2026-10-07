package com.choimanseon.pocketlog

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.lifecycle.Lifecycle
import com.choimanseon.pocketlog.auto.AutoInputService
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
        repeat(3) { compose.onNodeWithText("다음").performClick() }
        shot("0-onboarding-2")
        // no 다음 before the notification listener is on; turned on in the phone's settings, it shows when the app comes back
        compose.onNodeWithText("알림 접근을 허용하면 다음으로 가요").assertIsNotEnabled()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", ComponentName(app, AutoInputService::class.java).flattenToString())
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED).moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("배터리 제한 없음으로 하기").assertExists() // TODO #46
        shot("0-onboarding-3-battery")
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("동의하고 AI 쓰기").performClick()
        compose.onNodeWithText("동의했어요").assertExists()
        assertEquals(true, app.prefs.aiConsent)
        shot("0-onboarding-4-ai")
        compose.onNodeWithText("다음").performClick()
        shot("0-onboarding-5")
        compose.onNodeWithText("시작하기").performClick()

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
        // the month's rows come back from Room on another thread, which Compose's idling doesn't wait for
        compose.waitUntil(5_000) { compose.onAllNodesWithText("김밥천국").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("술·유흥").performClick() // a row under the donut opens what's behind it
        compose.onNodeWithText("2건", substring = true).assertExists()
        shot("4a-stats-list")
        compose.runOnUiThread { nav().pop() }
        compose.onNodeWithText("기간").performClick()
        shot("4b-stats-trend")
        // dragging the chart one and a half bars to the right goes back one period
        compose.onNodeWithContentDescription("기간별 차트").performTouchInput {
            swipeRight(startX = left + 10f, endX = left + 10f + viewConfiguration.touchSlop + width / 12f * 1.5f)
        }
        compose.onNodeWithText(period.shiftMonths(-1).label()).assertExists()
        compose.onNodeWithContentDescription("다음").performClick()
        compose.onNodeWithText(period.label()).assertExists()
        compose.onNodeWithText("합산").performClick()
        shot("4c-stats-net")
        compose.onAllNodesWithText("지출")[0].performClick() // the tab; the table header says 지출 too
        compose.onNodeWithText("분류").performClick()
        // tags as slices: a tagged order counts once per tag
        compose.onNodeWithText("카테고리별 ▾").performClick()
        compose.onNodeWithText("태그별").performClick()
        compose.onNodeWithText("야식").assertExists() // the tag's name alone (TODO #42)
        compose.onNodeWithText("친구·모임").assertExists()
        shot("4d-stats-tags")
        compose.onNodeWithText("태그별 ▾").performClick()
        compose.onNodeWithText("카테고리별").performClick()
        compose.onNodeWithText("저축").performClick()
        compose.onNodeWithText("모은 돈").assertExists()
        shot("4e-stats-saving")
        compose.onAllNodesWithText("지출")[0].performClick()
        compose.onNodeWithText("패턴").performClick()
        compose.onNodeWithText("요일 × 시간대").assertExists()
        compose.onNodeWithText("자주 가는 곳").assertExists()
        shot("4f-stats-pattern")
        compose.onNodeWithText("분류").performClick()

        compose.onNodeWithText("자산").performClick()
        shot("5-assets")

        compose.onNodeWithText("홈").performClick()
        compose.runOnUiThread { nav().push(Screen.Detail(orderId)) }
        shot("6-detail")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.ScanResult(scanId)) }
        shot("7-scan-review")
        // a category's tags in the same sheet (TODO: 결과창 태그)
        compose.onAllNodesWithText("술·유흥")[0].performClick()
        compose.onNodeWithText("#데이트").performClick()
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithText("술·유흥 #데이트").assertExists()
        // the total on the calculator keys: an operator goes on from the amount, a digit first starts over
        compose.onNodeWithText("39,900원").performClick()
        listOf("+", "1", "000").forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithText("= 40,900원").assertExists()
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithText("40,900원").performClick()
        listOf("5", "000").forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithText("5,000원").assertExists()
        shot("7b-scan-edited")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.Settings) }
        shot("8-settings")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.Categories) }
        shot("8b-categories")
        // long-press the tag 야식 (식비's last) and drag it down past "+ 태그 추가" and 카페·간식: it becomes 카페·간식's first tag
        compose.onNodeWithText("야식").performTouchInput {
            down(center)
            advanceEventTime(800)
            repeat(10) { moveBy(Offset(0f, height * 0.25f)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
        runBlocking(Dispatchers.IO) {
            val cats = app.dao.categoriesOnce()
            assertEquals(cats.first { it.name == "카페·간식" }.id, cats.first { it.name == "야식" }.parentId)
        }
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.Search) }
        shot("8c-search")
        // fuzzy: "스벅" finds 스타벅스코리아 under 비슷한 내역 (matched off the main thread, so wait for it)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("스벅")
        // 스타벅스코리아 is listed before typing too, so wait for the matching itself
        compose.waitUntil(5000) { compose.onAllNodesWithText("비슷한 내역", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("8c-search-fuzzy")
        compose.runOnUiThread { nav().pop() }

        compose.runOnUiThread { nav().push(Screen.BudgetEdit) }
        compose.onNodeWithText("주 · 월 · 연 통일").assertExists() // TODO #37
        shot("8d-budget-edit")
        compose.runOnUiThread { nav().pop() }

        // the report's charts (TODO #41), from the sample month's own numbers
        val reportStart = runBlocking(Dispatchers.IO) { seedReport() }
        compose.runOnUiThread { nav().push(Screen.Reports(reportStart)) }
        shot("8e-report-1")
        listOf("카테고리", "자주 간 곳", "다음 달에 해 볼 것").forEachIndexed { i, section ->
            compose.onNodeWithText(section).performScrollTo()
            shot("8e-report-${i + 2}")
        }
        compose.runOnUiThread { nav().pop() }

        // 즐겨찾기 opens as a list beside the merchant field and fills the form (TODO #47)
        runBlocking(Dispatchers.IO) { app.dao.upsert(com.choimanseon.pocketlog.data.Favorite(amount = 4500, merchant = "단골 김밥")) }
        compose.runOnUiThread { nav().entry = com.choimanseon.pocketlog.ui.Entry() }
        shot("9-entry-sheet") // drawn in the app's own window, over the home screen (TODO #65)
        compose.onNodeWithText("즐겨찾기").performClick()
        // the list comes from the database off the main thread: wait for it
        compose.waitUntil(5000) { compose.onAllNodesWithText("단골 김밥", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("단골 김밥").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("4,500원")[0].assertExists()
        compose.runOnUiThread { nav().entry = null }

        // 사용법: every part folded, one opened by a tap
        compose.runOnUiThread { nav().push(Screen.Help) }
        compose.onNodeWithText("직접 입력 (+ 버튼)").performClick()
        shot("8f-help")
        compose.runOnUiThread { nav().pop() }

        app.prefs.theme = "dark"
        shot("10-home-dark")
    }

    private suspend fun seedReport(): String {
        val period = monthPeriod(LocalDate.now(), app.prefs.monthStartDay)
        val facts = com.choimanseon.pocketlog.ai.MonthlyReport.facts(period)!!
        val text = org.json.JSONObject(
            """{"headline":"술값이 늘고 외식은 그대로였어요","summary":"이번 달 지출은 지난달보다 늘었어요. 술·유흥이 가장 많이 늘었어요.",
            "highlights":[{"title":"술·유흥이 늘었어요","detail":"데일리샷 두 번이 컸어요."},{"title":"식비는 예산 안","detail":"300,000원 중 일부만 썼어요."},
            {"title":"구독 17,000원","detail":"넷플릭스 한 건이에요."}],"budget":"전체 예산 안에서 썼어요.","suggestions":["술은 주 1회로","구독 점검하기"],"praise":"예산을 지켰어요."}""",
        )
        app.dao.upsert(com.choimanseon.pocketlog.data.Report(period.start.toString(), period.end.toString(), org.json.JSONObject().put("facts", facts).put("text", text).toString()))
        return period.start.toString()
    }

    private suspend fun seedSample(): Pair<Long, Long> {
        val dao = app.dao
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
        app.prefs.monthStartDay = 25
        val all = dao.categoriesOnce()
        val cats = all.filter { it.parentId == null }.associateBy { it.name }
        fun cat(name: String) = cats.getValue(name).id
        val tagged = mutableMapOf<String, List<String>>() // merchant → tag names
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
            Tx(amount = 5600, occurredAt = at(0, 9, 5), merchant = "스타벅스코리아", categoryId = cat("카페·간식"), paymentMethodId = kb, source = TxSource.PUSH),
            Tx(amount = 39900, occurredAt = at(1, 19, 30), merchant = "(주)데일리샷", categoryId = cat("술·유흥"), paymentMethodId = kb, source = TxSource.SMS),
            Tx(amount = 1550, occurredAt = at(1, 18, 50), merchant = "ezl 지하철 1건 이용", categoryId = cat("교통·차량"), paymentMethodId = toss, source = TxSource.PUSH),
            Tx(amount = 2400, occurredAt = at(3, 19, 25), merchant = "CU(씨유)자양승일점", categoryId = cat("카페·간식"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 17000, occurredAt = at(4, 3, 15), merchant = "넷플릭스", categoryId = cat("구독"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(amount = 0, occurredAt = at(4, 3, 16), merchant = "NETFLIX.COM", paymentMethodId = samsung, source = TxSource.SMS, status = TxStatus.PENDING_REVIEW, originalAmount = "USD 12.99"),
            Tx(type = TxType.INCOME, amount = 3_200_000, occurredAt = at(6, 9, 0), merchant = "(주)회사이름 급여", categoryId = cat("급여"), paymentMethodId = kakao, source = TxSource.PUSH),
            Tx(type = TxType.TRANSFER, amount = 50_000, occurredAt = at(5, 10, 0), merchant = "쿠팡페이", paymentMethodId = kakao, toPaymentMethodId = coupang, source = TxSource.PUSH, memo = "충전"),
            Tx(amount = 23000, occurredAt = at(6, 20, 0), merchant = "교촌치킨", categoryId = cat("식비"), paymentMethodId = samsung, source = TxSource.SMS),
            Tx(type = TxType.SAVING, amount = 500_000, occurredAt = at(5, 9, 0), merchant = "카카오뱅크 적금", categoryId = cat("저축"), paymentMethodId = kakao, source = TxSource.PUSH),
            Tx(amount = 12300, occurredAt = at(2, 19, 43), merchant = "주식회사앨리스프랜즈", categoryId = cat("쇼핑"), paymentMethodId = samsung, source = TxSource.SMS, status = TxStatus.CANCELED),
            // the Coupang payment alert for the screenshot's second order: the review screen offers 품목 넣기
            Tx(amount = 12400, occurredAt = at(2, 13, 0), merchant = "쿠팡", categoryId = cat("쇼핑"), paymentMethodId = coupang, source = TxSource.PUSH),
        )
        tagged += mapOf(
            "김밥천국" to listOf("외식"), "스타벅스코리아" to listOf("커피"), "(주)데일리샷" to listOf("홈술"), "ezl 지하철 1건 이용" to listOf("대중교통"),
            "CU(씨유)자양승일점" to listOf("편의점"), "넷플릭스" to listOf("OTT"), "교촌치킨" to listOf("야식", "친구·모임"), "카카오뱅크 적금" to listOf("적금"),
        )
        txs.forEach { tx ->
            val id = dao.insert(tx)
            val parent = tx.categoryId?.let { c -> all.first { it.id == c } }
            val shared = all.firstOrNull { it.tagGroup && it.type == tx.type }
            tagged[tx.merchant]?.let { names -> dao.setTags(id, names.map { n -> all.first { it.name == n && (it.parentId == parent?.id || it.parentId == shared?.id) }.id }) }
        }
        val orderId = dao.insertWithSplits(
            Tx(amount = 52300, occurredAt = at(2, 12, 0), merchant = "쿠팡", memo = "데일리샷 와인 외 2개", categoryId = cat("술·유흥"), paymentMethodId = coupang, source = TxSource.SCREENSHOT),
            listOf(
                TxSplit(txId = 0, name = "데일리샷 와인", amount = 29900, categoryId = cat("술·유흥")),
                TxSplit(txId = 0, name = "생수 2L", quantity = 12, amount = 12400, categoryId = cat("식비")),
                TxSplit(txId = 0, name = "키친타월 6롤", amount = 9000, categoryId = cat("생활")),
                TxSplit(txId = 0, name = "배송비·할인", amount = 1000, categoryId = cat("술·유흥")),
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
              "items":[{"name":"데일리샷 와인","quantity":1,"amount":29900,"category_id":"${cat("술·유흥")}"},{"name":"키친타월 6롤","quantity":1,"amount":9000,"category_id":"${cat("생활")}"}],
              "shipping_fee":1000,"discount":0,"confidence":0.95},
             {"date":"${today.minusDays(2)}","time":null,"merchant":"쿠팡","total_amount":12400,"currency":"KRW","payment_hint":"쿠팡머니","status":"paid",
              "items":[{"name":"생수 2L","quantity":12,"amount":12400,"category_id":"${cat("식비")}"}],"shipping_fee":0,"discount":0,"confidence":0.9},
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
                    com.choimanseon.pocketlog.ui.EntryForm(com.choimanseon.pocketlog.ui.Entry(), Nav(), {}, {}, {})
                }
            }
        }
        listOf("1", "2", "000", "+", "3", "000").forEach { compose.onNodeWithText(it).performClick() }
        // 추가 puts the item aside, the next one goes in the cleared form, 저장 makes one record of both
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("와인")
        compose.onNodeWithText("추가").performClick()
        listOf("5", "000").forEach { compose.onNodeWithText(it).performClick() }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").mkdirs()
        File("build/screenshots/9-entry-form.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("저장하기").performClick()
        runBlocking(Dispatchers.IO) {
            var tx: com.choimanseon.pocketlog.data.Tx? = null
            while (tx == null) { delay(20); tx = app.dao.txAround(0, Long.MAX_VALUE).firstOrNull() }
            assertEquals(20000L to "와인 외 1개", tx.amount to tx.memo)
            assertEquals(listOf("와인" to 15000L, "품목" to 5000L), app.dao.splitsOf(tx.id).first().map { it.name to it.amount })
        }
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
