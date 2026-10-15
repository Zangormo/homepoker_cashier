package com.zango.pokertracker

import android.app.Application
import android.app.NotificationManager
import com.zango.pokertracker.billing.BillingManager
import com.zango.pokertracker.core.locale.AppCurrencyStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PokerTrackerApp : Application() {

    @Inject
    lateinit var billingManager: BillingManager

    override fun onCreate() {
        super.onCreate()
        // Once per process start. The setup callback reads existing purchases, which is how a
        // purchase is restored after a reinstall: there is no account or backend to ask instead.
        billingManager.startConnection()
        AppCurrencyStore.init(this)
        // Versions up to 1.2.5 notified about bomb pots on this channel. The app posts nothing now,
        // so the channel is taken out of the phone's notification settings too, where it would
        // otherwise linger as a switch that does nothing. A no-op once it is gone.
        getSystemService(NotificationManager::class.java).deleteNotificationChannel(LEGACY_BOMB_POT_CHANNEL)
    }

    private companion object {
        const val LEGACY_BOMB_POT_CHANNEL = "bomb_pot"
    }
}
