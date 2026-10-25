package com.zango.pokertracker.ui.settlement

import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import com.zango.pokertracker.R
import com.zango.pokertracker.core.locale.AppCurrencyStore
import com.zango.pokertracker.core.money.Chips
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.ui.common.ResultRow
import com.zango.pokertracker.ui.theme.PokerTrackerTheme
import com.zango.pokertracker.ui.theme.Tokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * The results picture attached to a shared settlement.
 *
 * Drawn for real on the JVM (Robolectric native graphics) and read back from the PNG, so these
 * check the file that actually goes out rather than the composable that makes it. The window is
 * drawn by hand, as in ResponsiveScreensTest, because the Compose test capture waits for a frame
 * Robolectric does not send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettlementShareImageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity

    private fun row(id: Long, name: String, net: Long) = ResultRow(
        seatId = id,
        name = name,
        totalBuyIn = Money(1_000_000),
        chipsOut = Chips(100),
        cashOut = Money(1_000_000 + net),
        net = Money(net),
    )

    private val threePlayers = listOf(
        row(1, "Boris", 5_700_000),
        row(2, "Anna", -4_500_000),
        row(3, "Konstantinos", -1_200_000),
    )

    @Before
    fun setUp() {
        AppCurrencyStore.init(compose.activity)
        File(compose.activity.cacheDir, SHARE_DIRECTORY).deleteRecursively()
    }

    /**
     * Shows [content] the way the settlement screen does: beside a full-size sibling. A window
     * whose only content has no size is never drawn, and neither is the hidden copy in it.
     */
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            PokerTrackerTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun GraphicsLayer.writeFile(): File? =
        compose.runOnUiThread { runBlocking { writeSharePngFile(context) } }

    private fun GraphicsLayer.picture(): Bitmap {
        val file = requireNotNull(writeFile()) { "no picture was written" }
        return BitmapFactory.decodeFile(file.path)
    }

    private val pxPerDp get() = context.resources.displayMetrics.density

    // -----------------------------------------------------------------------------------------
    // The file
    // -----------------------------------------------------------------------------------------

    /** Share is pressed before the first frame: no picture, and the text goes alone. */
    @Test
    fun `nothing is written before the picture has been drawn`() {
        lateinit var layer: GraphicsLayer
        show { layer = rememberGraphicsLayer() }

        assertNull(layer.writeFile())
        assertTrue(File(context.cacheDir, SHARE_DIRECTORY).listFiles().isNullOrEmpty())
    }

    @Test
    fun `the picture is a png in the shared folder of the cache`() {
        lateinit var layer: GraphicsLayer
        show {
            layer = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, layer)
        }

        val file = requireNotNull(layer.writeFile()) { "no picture was written" }
        assertEquals(File(File(context.cacheDir, SHARE_DIRECTORY), SHARE_IMAGE_FILE), file)
        // The PNG signature, so any app the share lands in can open it.
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), file.readBytes().take(4).map { it.toInt() and 0xFF })
        assertNotNull(BitmapFactory.decodeFile(file.path))
    }

    @Test
    fun `sharing again replaces the picture instead of piling them up`() {
        lateinit var layer: GraphicsLayer
        show {
            layer = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, layer)
        }

        layer.writeFile()
        layer.writeFile()

        assertEquals(listOf(SHARE_IMAGE_FILE), File(context.cacheDir, SHARE_DIRECTORY).list()!!.toList())
    }

    /** FileProvider refuses any file outside a folder it was told about. */
    @Test
    fun `the shared folder is one the file provider serves`() {
        val parser = context.resources.getXml(R.xml.handoff_paths)
        val cachePaths = buildList {
            while (parser.next() != XmlResourceParser.END_DOCUMENT) {
                if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "cache-path") {
                    add(parser.getAttributeValue(null, "path").trimEnd('/'))
                }
            }
        }

        assertTrue(cachePaths.toString(), SHARE_DIRECTORY in cachePaths)
    }

    // -----------------------------------------------------------------------------------------
    // What it looks like
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the picture is a fixed width whatever the window`() {
        lateinit var layer: GraphicsLayer
        show {
            layer = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, layer)
        }

        val picture = layer.picture()
        assertEquals((ShareImageWidth.value * pxPerDp).roundToInt(), picture.width)
        assertTrue("height ${picture.height}", picture.height in 1 until picture.width * 3)
    }

    /** The picture is read by the people in the chat, not at the host's own text size. */
    @Test
    fun `the host's large-font setting does not change the picture`() {
        lateinit var normal: GraphicsLayer
        lateinit var large: GraphicsLayer
        show {
            normal = rememberGraphicsLayer()
            large = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, normal)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ShareImageSource("Thursday", threePlayers, large)
            }
        }

        val atNormal = normal.picture()
        val atLarge = large.picture()
        assertEquals(atNormal.width, atLarge.width)
        assertEquals(atNormal.height, atLarge.height)
    }

    @Test
    fun `every player gets a row, so a bigger table makes a taller picture`() {
        lateinit var small: GraphicsLayer
        lateinit var big: GraphicsLayer
        show {
            small = rememberGraphicsLayer()
            big = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers.take(2), small)
            ShareImageSource(
                "Thursday",
                threePlayers + listOf(row(4, "Dmitry", 0), row(5, "Elena", 0), row(6, "Fyodor", 0)),
                big,
            )
        }

        val twoRows = small.picture().height
        val sixRows = big.picture().height
        // Rows are at least 48dp tall.
        assertTrue("$twoRows vs $sixRows", sixRows - twoRows >= (4 * 48 * pxPerDp).roundToInt())
    }

    /** It goes out on the app's own dark background, so it reads the same in a light chat. */
    @Test
    fun `the picture sits on the app background`() {
        lateinit var layer: GraphicsLayer
        show {
            layer = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, layer)
        }

        val picture = layer.picture()
        val background = Tokens.SurfaceBase.toArgb()
        assertEquals(background, picture.getPixel(1, 1))
        assertEquals(background, picture.getPixel(picture.width - 2, picture.height - 2))
    }

    /**
     * The green spade sits beside the app name in the bottom-right corner, and only there: the
     * strip under the table holds nothing else in the accent colour.
     */
    @Test
    fun `the app logo signs the bottom right corner`() {
        lateinit var layer: GraphicsLayer
        show {
            layer = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, layer)
        }

        val picture = layer.picture()
        val accent = Tokens.Accent.toArgb()
        val footerTop = picture.height - (60 * pxPerDp).roundToInt()
        fun accentPixels(fromX: Int, toX: Int): Int {
            var count = 0
            for (y in footerTop until picture.height) {
                for (x in fromX until toX) if (picture.getPixel(x, y) == accent) count++
            }
            return count
        }

        val spade = (10 * pxPerDp).roundToInt()
        assertTrue("no logo in the corner", accentPixels(picture.width * 3 / 4, picture.width) > spade * spade)
        assertEquals(0, accentPixels(0, picture.width / 2))
    }

    @Test
    fun `the picture changes when the results do`() {
        lateinit var first: GraphicsLayer
        lateinit var second: GraphicsLayer
        show {
            first = rememberGraphicsLayer()
            second = rememberGraphicsLayer()
            ShareImageSource("Thursday", threePlayers, first)
            ShareImageSource("Thursday", threePlayers.reversed(), second)
        }

        assertTrue(!first.picture().sameAs(second.picture()))
    }

    // -----------------------------------------------------------------------------------------
    // The hidden copy on screen
    // -----------------------------------------------------------------------------------------

    /**
     * The copy that feeds the picture must not be read out a second time by TalkBack. Checked on
     * the merged tree, which is what accessibility services are given.
     */
    @Test
    fun `the hidden copy is invisible to accessibility services`() {
        show {
            ShareImageSource("Thursday", threePlayers, rememberGraphicsLayer())
        }

        compose.onAllNodesWithText("Konstantinos").assertCountEquals(0)
        compose.onAllNodesWithText("Thursday", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the hidden copy draws nothing on screen`() {
        show {
            ShareImageSource("Thursday", threePlayers, rememberGraphicsLayer())
        }

        val shot = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(shot.width * shot.height).also { shot.getPixels(it, 0, shot.width, 0, 0, shot.width, shot.height) }
        assertEquals(1, pixels.distinct().size)
    }
}
