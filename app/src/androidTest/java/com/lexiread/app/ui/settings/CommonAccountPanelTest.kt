package com.lexiread.app.ui.settings

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.MainActivity
import com.lexiread.app.ui.theme.LexiReadTheme
import com.shelest.shared.sync.CloudAccount
import com.shelest.ui.AccountPanel
import com.shelest.ui.AccountPanelState
import com.shelest.ui.SyncSettingsPanel
import com.shelest.shared.SyncPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommonAccountPanelTest {
    @Test fun sharedPanelShowsPlanAndDispatchesActionsWithoutCredentials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = mutableStateOf(AccountPanelState(google = true, password = true))
        val preferences = mutableStateOf(SyncPreferences(false, false))
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent {
                LexiReadTheme(darkTheme = false) {
                    Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AccountPanel(state.value) { actions.add(it) }
                        SyncSettingsPanel(preferences.value,
                            { preferences.value = preferences.value.copy(autoSync = it) },
                            { preferences.value = preferences.value.copy(syncDeletions = it) })
                    } }
                }
            } }
            fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == text || node.text?.toString()?.startsWith(text + "\n") == true) return node
                for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
                return null
            }
            fun waitNode(text: String): AccessibilityNodeInfo {
                val end = android.os.SystemClock.uptimeMillis() + 10000
                while (android.os.SystemClock.uptimeMillis() < end) {
                    find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
                    Thread.sleep(100)
                }
                error("Missing shared panel text: $text")
            }
            fun click(text: String) {
                var node = waitNode(text)
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            click("Войти по логину")
            assertEquals(listOf("password"), actions.toList())
            scenario.onActivity { state.value = state.value.copy(busy = true) }
            var disabled = waitNode("Входим…")
            while (!disabled.isClickable && disabled.parent != null) disabled = disabled.parent
            assertFalse(disabled.isEnabled)
            scenario.onActivity { state.value = AccountPanelState(
                account = CloudAccount("ui-test", displayName = "Тестовый читатель", isPremium = true,
                    storageUsedBytes = 3*1024*1024, quotaBytes = 4*1024*1024),
                sync = true, logout = true, google = true, password = true) }
            waitNode("Шелест Premium")
            waitNode("3 МБ из 4 МБ")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Продолжить с Google"))
            click("Обновить"); click("Выйти")
            assertEquals(listOf("password","sync","logout"), actions.toList())
            click("Автосинхронизация")
            assertTrue(preferences.value.autoSync)
            assertFalse(preferences.value.syncDeletions)
        } finally {scenario.close()}
    }
}
