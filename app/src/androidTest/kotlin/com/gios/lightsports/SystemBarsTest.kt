package com.gios.lightsports

import android.Manifest
import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SystemBarsTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appContentStaysBetweenTheSystemBars() {
        val decor = compose.activity.window.decorView
        val systemBars = decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
        val content: Rect = compose.onNodeWithTag("app-content")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Content top ${content.top} must clear status bar ${systemBars.top}",
            content.top >= systemBars.top,
        )
        assertTrue(
            "Content bottom ${content.bottom} must clear navigation bar " +
                "${decor.height - systemBars.bottom}",
            content.bottom <= decor.height - systemBars.bottom,
        )
    }
}
