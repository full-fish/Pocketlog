package com.choimanseon.pocketlog

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Buttons that open another screen for a result (permission dialog, file pickers) must not crash.
 * androidx.fragment 1.2.5 (pulled in by biometric) threw "Can only use lower 16 bits for requestCode" on all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun permissionRequestAndFilePickerOpen() {
        repeat(3) { compose.onNodeWithText("다음").performClick() }
        compose.onNodeWithText("알림 접근 허용하기").performClick()
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("똑똑가계부에서 가져오기").performClick() // last onboarding page → backup screen
        compose.onNodeWithText("똑똑가계부에서 가져오기").performClick() // the file picker
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
    }
}
