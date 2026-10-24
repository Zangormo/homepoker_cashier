package com.zango.pokertracker.ui.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zango.pokertracker.R
import com.zango.pokertracker.core.text.UiText
import com.zango.pokertracker.core.time.formatGameDate
import com.zango.pokertracker.data.repository.PokerRepository
import com.zango.pokertracker.domain.model.GameSummary
import com.zango.pokertracker.domain.transfer.GameTransferCodec
import com.zango.pokertracker.domain.transfer.toTransfer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** One finished game the host can tick for export. */
data class ExportRow(
    val gameId: Long,
    val name: String,
    val dateLabel: String,
    val playerCount: Int,
)

data class ExportUiState(
    val isLoading: Boolean = true,
    val games: List<ExportRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val isPreparing: Boolean = false,
    /** The chosen games as text, once the host has moved on from choosing them. */
    val payload: String? = null,
    /** How many games [payload] carries. */
    val exportedCount: Int = 0,
) {
    val allSelected: Boolean get() = games.isNotEmpty() && selected.size == games.size

    val canExport: Boolean get() = selected.isNotEmpty() && !isPreparing

    /** A history soon outgrows one QR code; it still travels as a file. */
    val fitsInQrCode: Boolean
        get() = payload != null && payload.length <= GameTransferCodec.MAX_QR_LENGTH
}

sealed interface ExportEvent {
    data class Message(val text: UiText) : ExportEvent
}

/**
 * Passes finished games on to another host's phone, as many or as few as the host ticks.
 *
 * Only finished games are offered. A running game still changes with every buy-in, and handing
 * one over is its own errand, done from the game itself.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repository: PokerRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())

    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<ExportUiState> =
        combine(repository.observeGameSummaries(), selection, ::buildState)
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ExportUiState(),
            )

    fun onToggleGame(gameId: Long) = selection.update { state ->
        val ids = if (gameId in state.ids) state.ids - gameId else state.ids + gameId
        state.copy(ids = ids)
    }

    /** Ticks every game, or clears them all when every one is already ticked. */
    fun onToggleAll() {
        val state = uiState.value
        val ids = if (state.allSelected) emptySet() else state.games.mapTo(HashSet()) { it.gameId }
        selection.update { it.copy(ids = ids) }
    }

    fun onExport() {
        val state = uiState.value
        if (!state.canExport) return
        val ids = state.games.filter { it.gameId in state.selected }.map { it.gameId }
        selection.update { it.copy(isPreparing = true) }
        viewModelScope.launch {
            runCatching {
                val transfers = ids.mapNotNull { repository.observeGame(it).first()?.toTransfer() }
                check(transfers.isNotEmpty()) { "Every chosen game is gone" }
                transfers.size to withContext(Dispatchers.Default) { GameTransferCodec.encodeHistory(transfers) }
            }
                .onSuccess { (count, payload) ->
                    selection.update { it.copy(isPreparing = false, payload = payload, exportedCount = count) }
                }
                .onFailure {
                    selection.update { it.copy(isPreparing = false) }
                    eventChannel.send(ExportEvent.Message(UiText.of(R.string.error_export_failed)))
                }
        }
    }

    /** Back from the code to the list, with the same games still ticked. */
    fun onBackToSelection() = selection.update { it.copy(payload = null, exportedCount = 0) }

    private fun buildState(summaries: List<GameSummary>, selection: Selection): ExportUiState {
        val games = summaries.filter { !it.game.isInProgress }.map { it.toExportRow() }
        // A game deleted elsewhere drops out of the selection with its row.
        val present = games.mapTo(HashSet()) { it.gameId }
        return ExportUiState(
            isLoading = false,
            games = games,
            selected = selection.ids intersect present,
            isPreparing = selection.isPreparing,
            payload = selection.payload,
            exportedCount = selection.exportedCount,
        )
    }

    private data class Selection(
        val ids: Set<Long> = emptySet(),
        val isPreparing: Boolean = false,
        val payload: String? = null,
        val exportedCount: Int = 0,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun GameSummary.toExportRow(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) = ExportRow(
    gameId = game.id,
    name = game.name,
    dateLabel = formatGameDate(game.startedAt, zone, locale),
    playerCount = playerCount,
)
