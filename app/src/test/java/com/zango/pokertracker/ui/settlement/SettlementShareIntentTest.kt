package com.zango.pokertracker.ui.settlement

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.zango.pokertracker.R
import com.zango.pokertracker.core.locale.AppCurrencyStore
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.core.text.UiText
import com.zango.pokertracker.domain.model.Player
import com.zango.pokertracker.domain.settlement.PLAY_STORE_URL
import com.zango.pokertracker.domain.settlement.Payment
import com.zango.pokertracker.domain.settlement.Settlement
import com.zango.pokertracker.domain.settlement.shareLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * What the Share button hands to the chooser: the plain text a chat app pastes, the HTML a mail
 * app shows, and the results picture when there is one.
 *
 * Resolved against real resources, because the footer link is found by looking for the link
 * text inside the translated footer, and that only works if every language keeps it whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettlementShareIntentTest {

    private lateinit var context: Context

    private val anna = Player(1, "Anna", createdAt = 0)
    private val boris = Player(2, "Boris", createdAt = 0)
    private val chris = Player(3, "Chris", createdAt = 0)

    private val image = Uri.parse("content://com.zango.pokertracker.debug.handoff/share/settlement.png")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppCurrencyStore.init(context)
    }

    private fun settlement(vararg payments: Payment) = Settlement(
        payments = payments.toList(),
        nets = emptyList(),
        settledNets = emptyList(),
        roundingUnit = Money(10_000),
        roundingAdjustment = Money.ZERO,
        adjustedPlayer = null,
        imbalance = Money.ZERO,
        unsettled = emptyList(),
    )

    private val twoPayments = settlement(
        Payment(anna, boris, Money(4_500_000)),
        Payment(chris, boris, Money(1_200_000)),
    )

    private fun intent(
        lines: List<UiText> = twoPayments.shareLines("Thursday"),
        image: Uri? = this.image,
        inContext: Context = context,
    ) = settlementShareIntent(inContext, lines, subject = "Thursday — settlement", image = image)

    private fun Intent.text() = getStringExtra(Intent.EXTRA_TEXT)!!
    private fun Intent.html() = getStringExtra(Intent.EXTRA_HTML_TEXT)!!

    private fun inLocale(tag: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration)
    }

    // -----------------------------------------------------------------------------------------
    // Plain text
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the plain text is the requested format, line for line`() {
        val lines = intent().text().lines()

        assertEquals("Thursday — settlement", lines[0])
        assertEquals("", lines[1])
        assertTrue(lines[2], lines[2].startsWith("Anna pays Boris ") && lines[2].contains("4.50"))
        assertEquals("", lines[3])
        assertTrue(lines[4], lines[4].startsWith("Chris pays Boris ") && lines[4].contains("1.20"))
        assertEquals("", lines[5])
        assertEquals("Settlement was made via Homepoker Cashier - check it out on Google Play!", lines[6])
        assertEquals(PLAY_STORE_URL, lines[7])
        assertEquals(8, lines.size)
    }

    @Test
    fun `the subject goes along for apps that use one`() {
        assertEquals("Thursday — settlement", intent().getStringExtra(Intent.EXTRA_SUBJECT))
    }

    // -----------------------------------------------------------------------------------------
    // HTML
    // -----------------------------------------------------------------------------------------

    @Test
    fun `in html the link text itself is the link`() {
        assertTrue(
            intent().html(),
            intent().html().endsWith(
                "Settlement was made via Homepoker Cashier - " +
                    "<a href=\"$PLAY_STORE_URL\">check it out on Google Play!</a>",
            ),
        )
    }

    /** The address is already behind the link text, so a second bare copy would only be noise. */
    @Test
    fun `in html the bare address is left out`() {
        val html = intent().html()
        assertEquals(1, Regex(Regex.escape(PLAY_STORE_URL)).findAll(html).count())
    }

    @Test
    fun `html keeps the lines and the blank lines between payments`() {
        val parts = intent().html().split("<br>")

        assertEquals("Thursday — settlement", parts[0])
        assertEquals("", parts[1])
        assertTrue(parts[2], parts[2].startsWith("Anna pays Boris"))
        assertEquals("", parts[3])
        assertTrue(parts[4], parts[4].startsWith("Chris pays Boris"))
        assertEquals("", parts[5])
        assertTrue(parts[6], parts[6].contains("<a href="))
        assertEquals(7, parts.size)
    }

    /** Names are the host's own typing, so they must never be read as markup. */
    @Test
    fun `names that look like markup are escaped in html but left alone in text`() {
        val tom = Player(4, "Tom & <b>Jo</b>", createdAt = 0)
        val shared = intent(lines = settlement(Payment(tom, boris, Money(1_000_000))).shareLines("Fri \"late\""))

        assertTrue(shared.html(), shared.html().contains("Tom &amp; &lt;b&gt;Jo&lt;/b&gt; pays Boris"))
        assertTrue(shared.html(), shared.html().contains("Fri &quot;late&quot;"))
        assertFalse(shared.html(), shared.html().contains("<b>"))
        assertTrue(shared.text(), shared.text().contains("Tom & <b>Jo</b> pays Boris"))
    }

    @Test
    fun `a table where everyone came out even still ends with the footer`() {
        val shared = intent(lines = settlement().shareLines("Quiet one"))

        assertEquals(
            listOf(
                "Quiet one — settlement",
                "",
                context.getString(R.string.settlement_everyone_even),
                "",
                "Settlement was made via Homepoker Cashier - check it out on Google Play!",
                PLAY_STORE_URL,
            ),
            shared.text().lines(),
        )
        assertTrue(shared.html(), shared.html().contains("<a href=\"$PLAY_STORE_URL\">"))
    }

    // -----------------------------------------------------------------------------------------
    // Every language
    // -----------------------------------------------------------------------------------------

    /**
     * The link is placed by finding the link text inside the footer, so a translation that
     * dropped the placeholder or reworded it would silently lose the link.
     */
    @Test
    fun `every language links its own link text and ends the text with the address`() {
        for (tag in listOf("en", "ru", "es", "de", "fr")) {
            val localized = inLocale(tag)
            val linkText = localized.getString(R.string.settlement_share_footer_link)
            val footer = localized.getString(R.string.settlement_share_footer, linkText)
            val shared = intent(inContext = localized)

            assertTrue("$tag: $footer", footer.contains("Homepoker Cashier"))
            assertTrue("$tag: $footer", footer.endsWith(linkText))
            assertEquals(tag, footer, shared.text().lines().dropLast(1).last())
            assertEquals(tag, PLAY_STORE_URL, shared.text().lines().last())
            assertTrue(
                "$tag: ${shared.html()}",
                shared.html().contains("<a href=\"$PLAY_STORE_URL\">${linkText.replace("&", "&amp;")}</a>"),
            )
        }
    }

    @Test
    fun `the footer is translated rather than left in english`() {
        val english = context.getString(R.string.settlement_share_footer_link)
        for (tag in listOf("ru", "es", "de", "fr")) {
            val linkText = inLocale(tag).getString(R.string.settlement_share_footer_link)
            assertTrue("$tag still reads \"$linkText\"", linkText != english)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The picture
    // -----------------------------------------------------------------------------------------

    @Test
    fun `with a picture the share is an image with the text as its caption`() {
        val shared = intent()

        assertEquals(Intent.ACTION_SEND, shared.action)
        assertEquals("image/png", shared.type)
        assertEquals(image, shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(shared.text().endsWith(PLAY_STORE_URL))
    }

    /** A picture that could not be made must never cost the host the payment list. */
    @Test
    fun `without a picture the share falls back to plain text`() {
        val shared = intent(image = null)

        assertEquals("text/plain", shared.type)
        assertNull(shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(0, shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(shared.text().endsWith(PLAY_STORE_URL))
    }

    @Test
    fun `sharing opens a chooser around the settlement`() {
        // The screen shares from its activity, which needs no new-task flag to start the chooser.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shareSettlement(activity, twoPayments.shareLines("Thursday"), "Thursday — settlement", "Share settlement", image)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals("Share settlement", started.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val inner = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("image/png", inner.type)
        assertEquals(image, inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(inner.text().startsWith("Thursday — settlement"))
    }
}
