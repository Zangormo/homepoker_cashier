package com.zango.pokertracker.ui.handoff

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zango.pokertracker.R
import com.zango.pokertracker.ui.common.MinTouchTarget
import com.zango.pokertracker.ui.common.resolve
import com.zango.pokertracker.ui.theme.PokerTrackerTheme

/**
 * Two steps on one screen: tick the games, then show them as a QR code or send them as a file.
 * Back from the code returns to the ticks rather than leaving, so a host who picked one game too
 * few is not made to pick them all again.
 */
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val payload = state.payload

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExportEvent.Message -> snackbarHostState.showSnackbar(event.text.resolve(context))
            }
        }
    }

    BackHandler(enabled = payload != null) { viewModel.onBackToSelection() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (payload != null) viewModel.onBackToSelection() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (payload == null && state.games.isNotEmpty()) {
                ExportBar(state = state, onExport = viewModel::onExport)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> Centered(Modifier.padding(padding)) { CircularProgressIndicator() }

            payload != null -> ExportCode(
                payload = payload,
                gameCount = state.exportedCount,
                fitsInQrCode = state.fitsInQrCode,
                onSendFile = {
                    shareTransferFile(
                        context,
                        context.getString(R.string.export_file_name),
                        payload,
                        context.getString(R.string.export_share_chooser),
                    )
                },
                onDone = onBack,
                modifier = Modifier.padding(padding),
            )

            state.games.isEmpty() -> Centered(Modifier.padding(padding)) {
                Text(
                    stringResource(R.string.export_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> GameChoices(
                state = state,
                onToggleAll = viewModel::onToggleAll,
                onToggleGame = viewModel::onToggleGame,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun GameChoices(
    state: ExportUiState,
    onToggleAll: () -> Unit,
    onToggleGame: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // What travels with a game, said once above the list rather than on every card.
        item(key = "intro") {
            Text(
                stringResource(R.string.export_intro),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "all") {
            SelectAllRow(state = state, onClick = onToggleAll)
        }
        items(state.games, key = { it.gameId }) { row ->
            GameChoice(
                row = row,
                selected = row.gameId in state.selected,
                onClick = { onToggleGame(row.gameId) },
            )
        }
    }
}

/** Ticks every game at once; part-ticked while only some are, as the list below it shows. */
@Composable
private fun SelectAllRow(state: ExportUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row carries the click, so the box itself must not take a second one.
        TriStateCheckbox(
            state = when {
                state.allSelected -> ToggleableState.On
                state.selected.isEmpty() -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            },
            onClick = null,
        )
        Text(
            stringResource(R.string.export_select_all),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.export_selected_of, state.selected.size, state.games.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dressed like a history card, with the outline the app uses for "this one is picked". */
@Composable
private fun GameChoice(row: ExportRow, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clickable(role = Role.Checkbox, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(MinTouchTarget))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        row.dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pluralStringResource(R.plurals.player_count, row.playerCount, row.playerCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportBar(state: ExportUiState, onExport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onExport,
                enabled = state.canExport,
                modifier = Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .height(MinTouchTarget),
            ) {
                if (state.isPreparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (state.selected.isEmpty()) {
                            stringResource(R.string.export_choose)
                        } else {
                            pluralStringResource(R.plurals.export_button, state.selected.size, state.selected.size)
                        },
                    )
                }
            }
        }
    }
}

/** The chosen games as a code to scan, or as a file when they have outgrown one. */
@Composable
private fun ExportCode(
    payload: String,
    gameCount: Int,
    fitsInQrCode: Boolean,
    onSendFile: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A code that dims away while the other host is still lining up their camera is no use.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.game_count, gameCount, gameCount),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            if (fitsInQrCode) {
                QrCode(
                    payload,
                    stringResource(R.string.export_qr_description),
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
            Text(
                stringResource(if (fitsInQrCode) R.string.export_instructions else R.string.export_too_big),
                style = MaterialTheme.typography.bodyMedium,
                color = if (fitsInQrCode) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
            )
            if (fitsInQrCode) {
                OutlinedButton(
                    onClick = onSendFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MinTouchTarget),
                ) { Text(stringResource(R.string.handoff_send_file_instead)) }
            } else {
                Button(
                    onClick = onSendFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MinTouchTarget),
                ) { Text(stringResource(R.string.handoff_send_file)) }
            }
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MinTouchTarget),
            ) { Text(stringResource(R.string.export_done)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private fun previewState() = ExportUiState(
    isLoading = false,
    games = listOf(
        ExportRow(2, "Last Thursday", "23 Aug 2026 · 20:05", 6),
        ExportRow(3, "Boris's birthday", "16 Aug 2026 · 19:30", 8),
        ExportRow(4, "Quiet one", "9 Aug 2026 · 21:00", 3),
    ),
    selected = setOf(2L, 3L),
)

@Preview(name = "Export — choose", showBackground = true, heightDp = 600)
@Composable
private fun ExportChoosePreview() {
    PokerTrackerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                GameChoices(previewState(), {}, {}, Modifier.weight(1f))
                ExportBar(previewState(), {})
            }
        }
    }
}
