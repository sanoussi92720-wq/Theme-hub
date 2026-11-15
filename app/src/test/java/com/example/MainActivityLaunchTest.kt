package com.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MainActivityLaunchTest {

    @Test
    fun `MainActivity launches cleanly without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertNotNull(activity.resolveWebClientId())
                val versionJson = org.json.JSONObject(AndroidWebAppInterface(activity).getAppVersionInfo())
                org.junit.Assert.assertEquals(BuildConfig.VERSION_NAME, versionJson.getString("versionName"))
                org.junit.Assert.assertEquals(BuildConfig.VERSION_CODE, versionJson.getInt("versionCode"))
                val localeJson = org.json.JSONObject(AndroidWebAppInterface(activity).getDeviceLocaleInfo())
                org.junit.Assert.assertTrue(localeJson.getString("language").isNotBlank())
                org.junit.Assert.assertTrue(localeJson.getString("languageTag").isNotBlank())

                val queryIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://themehub.app/?theme=obsidian_gold_01")
                )
                org.junit.Assert.assertEquals("obsidian_gold_01", activity.extractDeepLinkThemeId(queryIntent))

                val schemeIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("themehub://theme/neon_carbon_02")
                )
                org.junit.Assert.assertEquals("neon_carbon_02", activity.extractDeepLinkThemeId(schemeIntent))

                activity.pendingDeepLinkThemeId = "obsidian_gold_01"
                val bridge = AndroidWebAppInterface(activity)
                org.junit.Assert.assertEquals("obsidian_gold_01", bridge.consumePendingDeepLinkThemeId())
                org.junit.Assert.assertEquals("", bridge.consumePendingDeepLinkThemeId())
            }
        }
    }
}
