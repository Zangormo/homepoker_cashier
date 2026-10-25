package com.zango.pokertracker.responsive

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zango.pokertracker.R
import com.zango.pokertracker.core.locale.AppCurrencyStore
import com.zango.pokertracker.core.money.ChipRate
import com.zango.pokertracker.core.money.Chips
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.data.repository.CreatePlayerResult
import com.zango.pokertracker.data.repository.PokerRepository
import com.zango.pokertracker.domain.model.NewGameEntry
import com.zango.pokertracker.domain.model.NewGameSetup
import com.zango.pokertracker.ui.navigation.Routes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject

/**
 * DevTools' responsive mode for the app: every screen, drawn by the app's own code against a
 * seeded database, at each window size in [devices], portrait and landscape. Nothing is asserted;
 * the output is a gallery to look through, app/build/responsive/index.html, with every shot
 * scaled to its size in dp so the windows compare the way they would side by side.
 *
 * Drawn on the JVM by Robolectric, so no emulator is needed. What it does not show: system bars
 * and the camera cutout (Robolectric reports no insets), the AdMob banner (no ads SDK off a
 * device), and anything in its own window - dialogs, bottom sheets, the drawer.
 *
 * Kept out of ordinary test runs; see app/build.gradle.kts for the command.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveScreensTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var repository: PokerRepository

    private val outDir = File("build/responsive")

    /** A window size in dp, named the way DevTools names its presets. */
    private data class Device(val name: String, val widthDp: Int, val heightDp: Int) {
        val isLandscape get() = widthDp > heightDp
        val slug get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "-") + "-${widthDp}x$heightDp"
    }

    private val devices = listOf(
        Device("Small phone", 320, 568),
        Device("Phone", 360, 640),
        Device("Pixel", 412, 915),
        Device("Large phone", 430, 932),
        Device("Small phone landscape", 568, 320),
        Device("Phone landscape", 640, 360),
        Device("Pixel landscape", 915, 412),
        Device("Foldable", 673, 841),
        Device("Foldable landscape", 841, 673),
        Device("Split screen", 400, 800),
        Device("Tablet", 800, 1280),
        Device("Tablet landscape", 1280, 800),
        Device("Large tablet", 1024, 1366),
        Device("Large tablet landscape", 1366, 1024),
        Device("Chromebook", 1366, 768),
        Device("Desktop window", 1920, 1080),
    )

    private data class Screen(val name: String, val route: String)

    private lateinit var screens: List<Screen>

    @Before
    fun setUp() {
        hilt.inject()
        val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        AppCurrencyStore.init(context)
        // Defined in the test sources, so not in any manifest; Robolectric is told about it here.
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = ResponsiveHostActivity::class.java.name
                packageName = context.packageName
                theme = R.style.Theme_PokerTracker
            },
        )
        screens = seed()
    }

    @Test
    fun drawEveryScreenAtEverySize() {
        outDir.deleteRecursively()
        compose.mainClock.autoAdvance = false
        for (device in devices) {
            RuntimeEnvironment.setQualifiers(
                "w${device.widthDp}dp-h${device.heightDp}dp-" +
                    (if (device.isLandscape) "land" else "port") + "-hdpi",
            )
            ActivityScenario.launch(ResponsiveHostActivity::class.java).use { scenario ->
                for (screen in screens) {
                    scenario.onActivity { it.navController.navigate(screen.route) }
                    settle()
                    scenario.onActivity { capture(it, File(outDir, "${screen.name}/${device.slug}.png")) }
                }
            }
        }
        writeGallery()
    }

    /** Lets navigation finish animating and the database answer before the shot is taken. */
    private fun settle() {
        repeat(40) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * Draws the window straight into a bitmap. The Compose test capture waits for a frame, which
     * never comes while [settle] holds the clock - and the clock is held because the live game's
     * running timer would otherwise keep the test from ever going idle.
     */
    private fun capture(activity: ResponsiveHostActivity, file: File) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * A running game with rebuys, a chip return and a cash-out, and a finished one for the
     * settlement. Names and a game name at the longest the app accepts, so truncation shows.
     */
    private fun seed(): List<Screen> = runBlocking {
        val names = listOf("Alexander", "Boris", "Catherine", "Dmitry", "Elena", "Konstantinos")
        val players = names.map { (repository.createPlayer(it) as CreatePlayerResult.Created).player }
        val rate = ChipRate(500_000)
        val buyIn = Money(100_000_000)

        val running = repository.createGame(
            NewGameSetup(
                name = "Friday game",
                smallBlind = Money(500_000),
                bigBlind = Money(1_000_000),
                chipRate = rate,
                defaultBuyIn = buyIn,
                entries = players.map { NewGameEntry(it.id, buyIn) },
            ),
        )
        val runningSeats = repository.observeGame(running).first()!!.seats
        repository.addBuyIn(runningSeats[0].id, buyIn)
        repository.addBuyIn(runningSeats[0].id, buyIn)
        repository.addBuyIn(runningSeats[2].id, buyIn)
        repository.returnChips(runningSeats[1].id, Chips(50))
        repository.cashOut(runningSeats[3].id, Chips(340))

        val finished = repository.createGame(
            NewGameSetup(
                name = "Thursday night at Boris'",
                smallBlind = Money(500_000),
                bigBlind = Money(1_000_000),
                chipRate = rate,
                defaultBuyIn = buyIn,
                entries = players.take(4).map { NewGameEntry(it.id, buyIn) },
            ),
        )
        val finishedSeats = repository.observeGame(finished).first()!!.seats
        listOf(420L, 0L, 260L, 120L).forEachIndexed { i, chips ->
            repository.setFinalChipCount(finishedSeats[i].id, Chips(chips))
        }
        repository.endGame(finished)

        listOf(
            Screen("01-game-hub", Routes.HISTORY),
            Screen("02-new-game", Routes.CREATE_GAME),
            Screen("03-live-game", Routes.liveGame(running)),
            Screen("04-end-game", Routes.endGame(running)),
            Screen("05-settlement", Routes.settlement(finished)),
            Screen("06-hand-over", Routes.handOff(running)),
            Screen("07-players", Routes.PLAYERS),
            Screen("08-player", Routes.player(players[0].id)),
            Screen("09-settings", Routes.SETTINGS),
            Screen("10-export", Routes.EXPORT),
        )
    }

    /** One section per screen, each window drawn at a fixed px-per-dp so sizes compare truly. */
    private fun writeGallery() {
        val sections = screens.joinToString("\n") { screen ->
            val figures = devices.joinToString("\n") { d ->
                """<figure style="--w:${d.widthDp}"><img src="${screen.name}/${d.slug}.png" loading="lazy">""" +
                    """<figcaption>${d.name} <span>${d.widthDp}×${d.heightDp}</span></figcaption></figure>"""
            }
            """<section><h2>${screen.name.substringAfter('-').replace('-', ' ')}</h2><div class="row">$figures</div></section>"""
        }
        File(outDir, "index.html").writeText(
            """
            <!doctype html><html><head><meta charset="utf-8"><title>Responsive screens</title>
            <style>
              :root { --scale: 0.35; color-scheme: dark; }
              body { font: 14px system-ui, sans-serif; background: #0b0d10; color: #ddd; margin: 16px; }
              header { position: sticky; top: 0; background: #0b0d10; padding: 8px 0; z-index: 1; }
              h2 { text-transform: capitalize; margin: 32px 0 8px; }
              .row { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; }
              figure { margin: 0; width: calc(var(--w) * var(--scale) * 1px); }
              img { width: 100%; display: block; outline: 1px solid #444; }
              figcaption { margin-top: 4px; font-size: 12px; } figcaption span { color: #888; }
            </style></head><body>
            <header>Scale <input type="range" min="0.15" max="1" step="0.05" value="0.35"
              oninput="document.documentElement.style.setProperty('--scale', this.value)"></header>
            $sections
            </body></html>
            """.trimIndent(),
        )
    }
}
