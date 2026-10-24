package com.zango.pokertracker.ui.history

import com.zango.pokertracker.core.text.UiText
import com.zango.pokertracker.core.money.Chips
import com.zango.pokertracker.core.money.Money

/** What the host sees before taking over a game from another phone. */
data class IncomingGame(
    val name: String,
    val dateLabel: String,
    val playerCount: Int,
    val buyInCount: Int,
    val totalOnTable: Money,
    /** This phone already has the game, and taking it over replaces that copy. */
    val replacesCopy: Boolean,
)

/** What the host sees before games from another phone's history land on this one. */
data class IncomingHistory(
    val gameCount: Int,
    /** Of [gameCount], how many this phone does not have yet; only these are taken in. */
    val newGameCount: Int,
    /** "3 Jan 2026 – 22 Sep 2026", or a single day when every game was played on it. */
    val dateRange: String,
    /** Names this phone's roster does not know, who will join it. */
    val newPlayerCount: Int,
) {
    val alreadyHereCount: Int get() = gameCount - newGameCount
    val hasAnythingNew: Boolean get() = newGameCount > 0
}

/** One game in the history list. */
data class HistoryRow(
    val gameId: Long,
    val name: String,
    val dateLabel: String,
    val stakes: String,
    val playerCount: Int,
    val buyInCount: Int,
    val totalOnTable: Money,
    val chipsOnTable: Chips?,
    /** How long the game ran. Null while it is still going. */
    val durationLabel: String?,
    val isInProgress: Boolean,
    /** True once every settlement payment has been ticked off as handed over. */
    val isFullyPaid: Boolean = false,
)

data class HistoryUiState(
    val isLoading: Boolean = true,
    val inProgress: List<HistoryRow> = emptyList(),
    val finished: List<HistoryRow> = emptyList(),
    /** The game the host has asked to delete, held until they confirm. */
    val pendingDeletion: HistoryRow? = null,
    /** A game handed over from another phone, shown for the host to confirm before it lands. */
    val incoming: IncomingGame? = null,
    /** Games from another phone's history, shown for the host to confirm before they land. */
    val incomingHistory: IncomingHistory? = null,
) {
    val isEmpty: Boolean get() = !isLoading && inProgress.isEmpty() && finished.isEmpty()
}

sealed interface HistoryEvent {
    data class Message(val text: UiText) : HistoryEvent

    /** A game was taken over: open it, since carrying on with it is the whole point. */
    data class OpenGame(val gameId: Long) : HistoryEvent
}
