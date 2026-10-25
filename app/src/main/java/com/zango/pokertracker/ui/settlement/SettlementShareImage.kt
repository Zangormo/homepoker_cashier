package com.zango.pokertracker.ui.settlement

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.text.htmlEncode
import com.zango.pokertracker.R
import com.zango.pokertracker.core.money.Chips
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.core.text.UiText
import com.zango.pokertracker.domain.settlement.PLAY_STORE_URL
import com.zango.pokertracker.domain.settlement.ShareFooter
import com.zango.pokertracker.domain.settlement.ShareLink
import com.zango.pokertracker.ui.common.ResultRow
import com.zango.pokertracker.ui.common.ResultsTable
import com.zango.pokertracker.ui.common.SectionLabel
import com.zango.pokertracker.ui.common.resolve
import com.zango.pokertracker.ui.theme.PokerTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The width the shared picture is laid out at, whatever the phone. A fixed width keeps the
 * table's proportions the same in the chat whether it was shared from a small phone, a tablet or
 * in landscape.
 */
internal val ShareImageWidth = 380.dp

/** The logo's canvas beside the app name; the spade itself comes out about half this tall. */
private val LogoSize = 28.dp

/**
 * The results table as it goes out with a shared settlement: the game, the table, and the app it
 * came from, on the app's own background so it reads the same in a light or a dark chat.
 *
 * A [Surface] rather than a plain background, because the table's names take their colour from
 * the surface they sit on, and off screen there is no Scaffold to provide one.
 */
@Composable
internal fun SettlementShareCard(gameName: String, results: List<ResultRow>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settlement_share_subject, gameName),
                style = MaterialTheme.typography.titleLarge,
            )
            SectionLabel(stringResource(R.string.settlement_section_results), modifier = Modifier.padding(top = 8.dp))
            ResultsTable(results)
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The launcher icon is adaptive, which Compose cannot draw, so this is its
                // foreground alone: the spade on the same dark ground the picture already has.
                // The artwork fills a little over half its canvas, hence the generous size.
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(LogoSize),
                )
            }
        }
    }
}

/**
 * Keeps [layer] holding a fresh drawing of [SettlementShareCard] without showing it.
 *
 * The table on screen sits in a lazy list and is not drawn while scrolled away, so the picture is
 * drawn from its own copy: laid out at [ShareImageWidth] in a box of no size, recorded into the
 * layer and never drawn to the screen. Font scale is pinned to 1 because the picture is for the
 * people reading the chat, not for the host's own text-size setting.
 */
@Composable
internal fun ShareImageSource(gameName: String, results: List<ResultRow>, layer: GraphicsLayer) {
    val density = LocalDensity.current
    Box(modifier = Modifier.size(0.dp).clearAndSetSemantics {}) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
            SettlementShareCard(
                gameName = gameName,
                results = results,
                modifier = Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredWidth(ShareImageWidth)
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
            )
        }
    }
}

/**
 * Writes what [layer] last recorded to a PNG in the cache and returns a shareable address for it,
 * or null if nothing has been drawn yet. The file is overwritten on every share, so the cache
 * never collects old nights.
 */
internal suspend fun GraphicsLayer.writeSharePng(context: Context): Uri? {
    val file = writeSharePngFile(context) ?: return null
    return FileProvider.getUriForFile(context, "${context.packageName}.handoff", file)
}

/** The file half of [writeSharePng], apart so it can be checked without a content provider. */
internal suspend fun GraphicsLayer.writeSharePngFile(context: Context): File? {
    if (size == IntSize.Zero) return null
    // Usually a hardware bitmap, which cannot be compressed directly; a software copy can.
    val bitmap = toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    return withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        File(directory, SHARE_IMAGE_FILE).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** Offers [settlementShareIntent] to whatever the host sends things with. */
internal fun shareSettlement(
    context: Context,
    lines: List<UiText>,
    subject: String,
    chooserTitle: String,
    image: Uri?,
) {
    context.startActivity(Intent.createChooser(settlementShareIntent(context, lines, subject, image), chooserTitle))
}

/**
 * The settlement as text, with the results picture attached when there is one.
 *
 * Chat apps take the plain text and put the bare store address on its own line, which they turn
 * into a link. Mail apps that read HTML get the footer with "check it out on Google Play!" as the
 * link itself, and no bare address.
 */
internal fun settlementShareIntent(
    context: Context,
    lines: List<UiText>,
    subject: String,
    image: Uri?,
): Intent {
    val text = lines.joinToString(separator = "\n") { it.resolve(context) }
    val html = lines.mapNotNull { line ->
        when (line) {
            ShareLink -> null
            ShareFooter -> footerHtml(context)
            else -> line.resolve(context).htmlEncode()
        }
    }.joinToString(separator = "<br>")

    return Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_HTML_TEXT, html)
        if (image != null) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, image)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
}

private fun footerHtml(context: Context): String {
    val link = context.getString(R.string.settlement_share_footer_link).htmlEncode()
    val footer = context
        .getString(R.string.settlement_share_footer, context.getString(R.string.settlement_share_footer_link))
        .htmlEncode()
    return footer.replace(link, "<a href=\"$PLAY_STORE_URL\">$link</a>")
}

internal const val SHARE_DIRECTORY = "share"
internal const val SHARE_IMAGE_FILE = "settlement.png"

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "Settlement — shared image", widthDp = 380)
@Composable
private fun SettlementShareCardPreview() {
    PokerTrackerTheme {
        SettlementShareCard(
            gameName = "Thursday",
            results = listOf(
                ResultRow(1, "Boris", Money(1_000_000), Chips(1340), Money(6_700_000), Money(5_700_000)),
                ResultRow(2, "Anna", Money(5_000_000), Chips(100), Money(500_000), Money(-4_500_000)),
                ResultRow(3, "Chris", Money(2_000_000), Chips(160), Money(800_000), Money(-1_200_000)),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
