package com.zango.pokertracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zango.pokertracker.R
import com.zango.pokertracker.core.text.UiText
import com.zango.pokertracker.core.time.formatElapsed
import com.zango.pokertracker.core.time.formatGameDate
import com.zango.pokertracker.core.time.formatGameDay
import com.zango.pokertracker.data.repository.PokerRepository
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.domain.model.GameSummary
import com.zango.pokertracker.domain.transfer.DecodedTransfer
import com.zango.pokertracker.domain.transfer.GameTransfer
import com.zango.pokertracker.domain.transfer.GameTransferCodec
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/**
 * The app's home. Games still running are listed first so a host who closed the app mid-game
 * lands straight back on it, with finished games below as the read-only record.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: PokerRepository,
) : ViewModel() {

    private val pendingDeleteId = MutableStateFlow<Long?>(null)

    /** A decoded handover waiting for the host to confirm, with the copy it would replace. */
    private val pendingTransfer = MutableStateFlow<PendingTransfer?>(null)

    /** A decoded history waiting for the host to confirm. */
    private val pendingHistory = MutableStateFlow<PendingHistory?>(null)

    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<HistoryUiState> =
        combine(
            repository.observeGameSummaries(),
            pendingDeleteId,
            pendingTransfer,
            pendingHistory,
            ::buildState,
        )
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = HistoryUiState(),
            )

    fun onDeleteRequested(gameId: Long) {
        pendingDeleteId.value = gameId
    }

    fun onDismissDelete() {
        pendingDeleteId.value = null
    }

    /**
     * Only ever reached from the confirmation dialog. The game and everything recorded against
     * it go at once, and there is no undo, so nothing here happens on a single tap.
     */
    fun onConfirmDelete() {
        val gameId = pendingDeleteId.value ?: return
        pendingDeleteId.value = null
        viewModelScope.launch {
            runCatching { repository.deleteGame(gameId) }
                .onFailure {
                    eventChannel.send(
                        HistoryEvent.Message(UiText.of(R.string.error_could_not_delete_game)),
                    )
                }
        }
    }

    /**
     * Text from a scanned QR code or an opened file. Anything that is not a game, or comes from a
     * newer app, is turned away with a reason; a game is held for the host to confirm.
     */
    fun onTransferReceived(text: String) {
        viewModelScope.launch {
            when (val decoded = GameTransferCodec.decode(text)) {
                is DecodedTransfer.Game -> {
                    val copy = runCatching { repository.findCopyOf(decoded.transfer) }.getOrNull()
                    pendingTransfer.value = PendingTransfer(decoded.transfer, copy)
                }

                is DecodedTransfer.History -> runCatching { preview(decoded.games) }
                    .onSuccess { pendingHistory.value = it }
                    .onFailure {
                        eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_import_failed)))
                    }

                DecodedTransfer.NotAGame ->
                    eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_receive_not_a_game)))

                DecodedTransfer.TooNew ->
                    eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_receive_too_new)))
            }
        }
    }

    /** The phone could not scan at all, typically because Google Play services is missing. */
    fun onScannerUnavailable() {
        viewModelScope.launch {
            eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_receive_scanner)))
        }
    }

    fun onDismissIncoming() {
        pendingTransfer.value = null
    }

    fun onConfirmIncoming() {
        val pending = pendingTransfer.value ?: return
        pendingTransfer.value = null
        viewModelScope.launch {
            runCatching { repository.importGame(pending.transfer, replacing = pending.copyId) }
                .onSuccess { eventChannel.send(HistoryEvent.OpenGame(it)) }
                .onFailure {
                    eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_receive_failed)))
                }
        }
    }

    /**
     * What a history would bring: which games are new here, and which names the roster has never
     * seen. Names are compared as the import matches them, ignoring case, and against hidden
     * players too, since a hidden player is still who a returning name lands on.
     */
    private suspend fun preview(games: List<GameTransfer>): PendingHistory {
        val known = repository.observePlayerStats().first().mapTo(HashSet()) { it.player.name.trim().lowercase() }
        val incomingNames = games.flatMap { game -> game.seats.map { it.player.trim().lowercase() } }.toSet()
        return PendingHistory(
            games = games,
            newGameCount = games.distinctBy { it.name.trim() to it.startedAt }
                .count { repository.findCopyOf(it) == null },
            newPlayerCount = (incomingNames - known).size,
        )
    }

    fun onDismissIncomingHistory() {
        pendingHistory.value = null
    }

    fun onConfirmIncomingHistory() {
        val pending = pendingHistory.value ?: return
        pendingHistory.value = null
        viewModelScope.launch {
            runCatching { repository.importGames(pending.games) }
                .onSuccess { added ->
                    eventChannel.send(
                        HistoryEvent.Message(UiText.plural(R.plurals.message_games_imported, added, added)),
                    )
                }
                .onFailure {
                    eventChannel.send(HistoryEvent.Message(UiText.of(R.string.error_import_failed)))
                }
        }
    }

    private fun buildState(
        summaries: List<GameSummary>,
        pendingId: Long?,
        transfer: PendingTransfer?,
        history: PendingHistory?,
    ): HistoryUiState {
        val rows = summaries.map { it.toRow() }
        return HistoryUiState(
            isLoading = false,
            inProgress = rows.filter { it.isInProgress },
            finished = rows.filter { !it.isInProgress },
            pendingDeletion = rows.firstOrNull { it.gameId == pendingId },
            incoming = transfer?.toIncoming(),
            incomingHistory = history?.toIncoming(),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal data class PendingTransfer(val transfer: GameTransfer, val copyId: Long?)

internal data class PendingHistory(
    val games: List<GameTransfer>,
    val newGameCount: Int,
    val newPlayerCount: Int,
)

internal fun PendingHistory.toIncoming(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): IncomingHistory {
    val first = formatGameDay(games.minOf { it.startedAt }, zone, locale)
    val last = formatGameDay(games.maxOf { it.startedAt }, zone, locale)
    return IncomingHistory(
        gameCount = games.size,
        newGameCount = newGameCount,
        dateRange = if (first == last) first else "$first – $last",
        newPlayerCount = newPlayerCount,
    )
}

internal fun PendingTransfer.toIncoming(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) = IncomingGame(
    name = transfer.name,
    dateLabel = formatGameDate(transfer.startedAt, zone, locale),
    playerCount = transfer.seats.size,
    buyInCount = transfer.buyInCount,
    totalOnTable = Money(transfer.totalOnTableMicros),
    replacesCopy = copyId != null,
)

internal fun GameSummary.toRow(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): HistoryRow = HistoryRow(
    gameId = game.id,
    name = game.name,
    dateLabel = formatGameDate(game.startedAt, zone, locale),
    stakes = "${game.smallBlind.format()} / ${game.bigBlind.format()}",
    playerCount = playerCount,
    buyInCount = buyInCount,
    totalOnTable = totalOnTable,
    chipsOnTable = chipsOnTable,
    durationLabel = durationMillis?.let { formatElapsed(it) },
    isInProgress = game.isInProgress,
    isFullyPaid = game.isFullyPaid,
)
