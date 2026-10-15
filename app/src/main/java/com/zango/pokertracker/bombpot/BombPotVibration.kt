package com.zango.pokertracker.bombpot

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buzzes the phone as the live game screen announces a bomb pot, since that is exactly the moment
 * the host has put the phone down on the table.
 *
 * Only while the game is open: bomb pots are announced in the app and nowhere else. Alarms and
 * notifications were tried for a closed app and dropped, as Android delivered them too far off the
 * minute to be worth having. Marked as an alarm vibration, which follows the phone's alarm settings
 * rather than its ringer, the same way an alarm clock buzzes on a silenced phone.
 *
 * An interface so the live game's view model can be tested on the JVM, where there is no vibrator.
 */
fun interface BombPotVibration {
    fun buzz()
}

@Singleton
class SystemBombPotVibration @Inject constructor(
    @ApplicationContext private val context: Context,
) : BombPotVibration {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    override fun buzz() {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN, NO_REPEAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrateAsAlarmBefore13(vibrator, effect)
        }
    }

    /** The audio-attributes form, which is how Android 8 to 12 are told a vibration is an alarm. */
    @Suppress("DEPRECATION")
    private fun vibrateAsAlarmBefore13(vibrator: Vibrator, effect: VibrationEffect) {
        vibrator.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
    }

    private companion object {
        /**
         * Off and on times in milliseconds, starting with the pause before the first buzz: two
         * short and one long, so it reads as "something happening at the table" rather than as an
         * ordinary message arriving in a pocket.
         */
        val PATTERN = longArrayOf(0, 300, 150, 300, 150, 700)
        const val NO_REPEAT = -1
    }
}
