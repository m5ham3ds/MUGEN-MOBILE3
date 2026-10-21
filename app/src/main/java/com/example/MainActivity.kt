package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.settings.SettingsDataStore
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Startup check: Detect unreviewed crash or historical SIGSEGV/SIGABRT exit
    val isBypass = intent?.getBooleanExtra(com.example.ui.screens.crash.CrashActivity.EXTRA_BYPASS_CRASH_CHECK, false) ?: false
    if (!isBypass) {
        val historicalExit = com.example.utils.CrashReporter.checkHistoricalExitReasons(this)
        val unreviewedCrash = historicalExit ?: com.example.utils.CrashReporter.getUnreviewedCrashReport(this)
        if (unreviewedCrash != null) {
            val crashIntent = android.content.Intent(this, com.example.ui.screens.crash.CrashActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(com.example.ui.screens.crash.CrashActivity.EXTRA_REPORT_DATA, unreviewedCrash)
            }
            startActivity(crashIntent)
            finish()
            return
        }
    }

    com.example.utils.CrashReporter.recordBreadcrumb("HOME_READY", "MainActivity created and ready")
    
    // Start recording logs in the background
    com.example.utils.LogcatRecorder.start(this)
    
    enableEdgeToEdge()
    setContent {
      val themeMode by remember { SettingsDataStore.getThemeMode(this) }.collectAsState(initial = "dark")
      val primaryColor by remember { SettingsDataStore.getPrimaryColor(this) }.collectAsState(initial = "red")
      val language by remember { SettingsDataStore.getLanguage(this) }.collectAsState(initial = "en")
      
      val strings = if (language == "ar") ArabicStrings else EnglishStrings
      val layoutDirection = if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
      
      MyApplicationTheme(themeMode = themeMode, primaryColorString = primaryColor) {
        CompositionLocalProvider(
            LocalAppStrings provides strings,
            LocalLayoutDirection provides layoutDirection
        ) {
            val isOnboardingCompleted by remember {
              SettingsDataStore.isOnboardingCompleted(this)
            }.collectAsState(initial = null)
            
            if (isOnboardingCompleted != null) {
                AppNavigation(isOnboardingCompleted = isOnboardingCompleted!!)
            }
        }
      }
    }
  }
}
