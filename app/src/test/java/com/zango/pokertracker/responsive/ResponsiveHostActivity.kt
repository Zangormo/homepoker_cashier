package com.zango.pokertracker.responsive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.zango.pokertracker.core.locale.AppCurrencyStore
import com.zango.pokertracker.ui.common.LocalCashFormat
import com.zango.pokertracker.ui.navigation.PokerNavHost
import com.zango.pokertracker.ui.theme.PokerTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity's content without its consent, billing and interstitial plumbing, none of which can
 * run off a device. The screens, view models, database and navigation are the app's own, and the
 * test drives navigation through [navController] instead of tapping its way round.
 */
@AndroidEntryPoint
class ResponsiveHostActivity : ComponentActivity() {

    lateinit var navController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val currency by AppCurrencyStore.code.collectAsState()
            val locale = LocalConfiguration.current.locales[0]
            val cashFormat = remember(currency, locale) { AppCurrencyStore.cashFormat(currency, locale) }
            CompositionLocalProvider(LocalCashFormat provides cashFormat) {
                PokerTrackerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val nav = rememberNavController()
                        navController = nav
                        PokerNavHost(navController = nav)
                    }
                }
            }
        }
    }
}
