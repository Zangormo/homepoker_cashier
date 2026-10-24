package com.zango.pokertracker.ui.history

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.zango.pokertracker.R
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.ui.common.CashAmountText
import com.zango.pokertracker.ui.common.MinTouchTarget
import com.zango.pokertracker.ui.theme.PokerTheme
import com.zango.pokertracker.ui.theme.PokerTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The two ways a handed-over game can arrive. */
class GameReceiver(val scan: () -> Unit, val openFile: () -> Unit)

/**
 * Scanning goes through Google's code scanner in Play services, which draws its own camera screen:
 * the app never asks for the camera permission. Files come through the system picker, because a
 * messenger's download folder is not somewhere the app can see on its own.
 */
@Composable
fun rememberGameReceiver(onText: (String) -> Unit, onScannerUnavailable: () -> Unit): GameReceiver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onText)
    val unavailable by rememberUpdatedState(onScannerUnavailable)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // An unreadable file reads as empty, which the decoder names as "not a game".
                val text = withContext(Dispatchers.IO) { runCatching { readSmallText(context, uri) }.getOrNull() }
                deliver(text.orEmpty())
            }
        }
    }

    return remember(context, picker) {
        GameReceiver(
            scan = {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                GmsBarcodeScanning.getClient(context, options)
                    .startScan()
                    .addOnSuccessListener { barcode -> deliver(barcode.rawValue.orEmpty()) }
                    // Cancelling is not a failure, and arrives through its own listener, unheard.
                    .addOnFailureListener { unavailable() }
            },
            openFile = { picker.launch(arrayOf("*/*")) },
        )
    }
}

/** Nothing a home game produces comes anywhere near this; anything bigger is not ours. */
private const val MAX_FILE_BYTES = 2_000_000

private fun readSmallText(context: Context, uri: Uri): String? {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_FILE_BYTES) return null
        }
        out.toByteArray().decodeToString()
    }
}

/**
 * Scan or open a file: asked once, so the host never lands in the wrong one. The same choice
 * serves taking over one game and importing a history, so only the wording changes.
 */
@Composable
fun ReceiveGameDialog(
    onScan: () -> Unit,
    onOpenFile: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.receive_title),
    body: String = stringResource(R.string.receive_body),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MinTouchTarget),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text(stringResource(R.string.receive_scan), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = onOpenFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MinTouchTarget),
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text(stringResource(R.string.receive_file), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The last look before a game from another phone lands on this one. */
@Composable
fun IncomingGameDialog(game: IncomingGame, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.receive_confirm_title, game.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.receive_started, game.dateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.history_row_counts,
                        pluralStringResource(R.plurals.player_count, game.playerCount, game.playerCount),
                        pluralStringResource(R.plurals.buy_in_count, game.buyInCount, game.buyInCount),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CashAmountText(game.totalOnTable, style = PokerTheme.type.numericLarge)
                if (game.replacesCopy) {
                    Text(
                        stringResource(R.string.receive_replaces_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.receive_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The last look before another phone's history lands on this one. It says what will merge, so a
 * host is not surprised to find a familiar name's results suddenly larger.
 */
@Composable
fun IncomingHistoryDialog(history: IncomingHistory, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pluralStringResource(R.plurals.game_count, history.gameCount, history.gameCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    history.dateRange,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (history.hasAnythingNew) {
                    Text(
                        stringResource(R.string.import_players_merge),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (history.newPlayerCount > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.import_new_players,
                                history.newPlayerCount,
                                history.newPlayerCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (history.alreadyHereCount > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.import_already_here,
                                history.alreadyHereCount,
                                history.alreadyHereCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.import_nothing_new),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (history.hasAnythingNew) {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.import_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (history.hasAnythingNew) R.string.action_cancel else R.string.action_close))
            }
        },
    )
}

@Preview(name = "Import — history", showBackground = true, heightDp = 520)
@Composable
private fun IncomingHistoryPreview() {
    PokerTrackerTheme {
        IncomingHistoryDialog(
            history = IncomingHistory(
                gameCount = 12,
                newGameCount = 10,
                dateRange = "3 Jan 2026 – 22 Sep 2026",
                newPlayerCount = 2,
            ),
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Receive — take over", showBackground = true, heightDp = 520)
@Composable
private fun IncomingGamePreview() {
    PokerTrackerTheme {
        IncomingGameDialog(
            game = IncomingGame(
                name = "Thursday",
                dateLabel = "22 Sep 2026 · 20:14",
                playerCount = 6,
                buyInCount = 9,
                totalOnTable = Money(9_000_000),
                replacesCopy = true,
            ),
            onConfirm = {},
            onDismiss = {},
        )
    }
}
