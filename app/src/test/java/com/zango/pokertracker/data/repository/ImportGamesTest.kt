package com.zango.pokertracker.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zango.pokertracker.core.money.Chips
import com.zango.pokertracker.core.money.Money
import com.zango.pokertracker.data.local.PokerDatabase
import com.zango.pokertracker.domain.model.ChipReturn
import com.zango.pokertracker.domain.model.Fixture
import com.zango.pokertracker.domain.model.GameSnapshot
import com.zango.pokertracker.domain.model.GameStatus
import com.zango.pokertracker.domain.transfer.GameTransfer
import com.zango.pokertracker.domain.transfer.toTransfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Importing a history, against the real schema and queries rather than the fake: the matching of
 * players by name and the skipping of games already held both live in SQL, so only a real
 * database can show they work. Robolectric supplies SQLite on the JVM; the database is in memory
 * and thrown away after each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportGamesTest {

    private lateinit var database: PokerDatabase
    private lateinit var repository: PokerRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PokerDatabase::class.java,
        ).build()
        repository = PokerRepositoryImpl(
            database = database,
            playerDao = database.playerDao(),
            gameDao = database.gameDao(),
            gamePlayerDao = database.gamePlayerDao(),
            buyInDao = database.buyInDao(),
            chipReturnDao = database.chipReturnDao(),
            settlementPaymentDao = database.settlementPaymentDao(),
            stakePresetDao = database.stakePresetDao(),
            clock = { NOW },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** A finished night: everyone bought in once and counted out, so each has a result. */
    private fun finishedGame(name: String, startedAt: Long, vararg players: String): GameTransfer =
        GameSnapshot(
            game = Fixture.game(status = GameStatus.FINISHED).copy(
                name = name,
                startedAt = startedAt,
                endedAt = startedAt + 4 * HOUR,
            ),
            seats = players.mapIndexed { index, player ->
                Fixture.seat(index + 1L, player, finalChips = Chips(200), cashedOutAt = startedAt + 3 * HOUR)
                    .copy(joinedAt = startedAt + index)
                    .let { seat -> seat.copy(buyIns = seat.buyIns.map { it.copy(createdAt = startedAt + 60_000L) }) }
            },
        ).toTransfer()

    private suspend fun stats() = repository.observePlayerStats().first()

    @Test
    fun `games land in the history and new names join the roster`() = runTest {
        val added = repository.importGames(
            listOf(
                finishedGame("Thursday", EVENING, "Anna", "Boris"),
                finishedGame("Friday", EVENING + DAY, "Anna", "Chris"),
            ),
        )

        assertEquals(2, added)
        assertEquals(
            setOf("Thursday", "Friday"),
            repository.observeGameSummaries().first().map { it.game.name }.toSet(),
        )
        val games = stats().associate { it.player.name to it.gamesPlayed }
        assertEquals(mapOf("Anna" to 2, "Boris" to 1, "Chris" to 1), games)
    }

    @Test
    fun `a name already on the roster gets the results, whatever its case`() = runTest {
        repository.createPlayer("anna")

        repository.importGames(listOf(finishedGame("Thursday", EVENING, "Anna", "Boris")))

        val players = stats()
        assertEquals(setOf("anna", "Boris"), players.map { it.player.name }.toSet())
        assertEquals(1, players.first { it.player.name == "anna" }.gamesPlayed)
    }

    @Test
    fun `imported results add to what the player already has`() = runTest {
        repository.importGames(listOf(finishedGame("Thursday", EVENING, "Anna", "Boris")))

        repository.importGames(listOf(finishedGame("Friday", EVENING + DAY, "Anna", "Boris")))

        val anna = stats().first { it.player.name == "Anna" }
        assertEquals(2, anna.gamesPlayed)
        assertEquals(Money(2_000_000), anna.totalPaidIn)
    }

    @Test
    fun `a hidden player still receives the results under their name`() = runTest {
        repository.importGames(listOf(finishedGame("Thursday", EVENING, "Anna")))
        val anna = stats().single()
        repository.setPlayerArchived(anna.player.id, archived = true)

        repository.importGames(listOf(finishedGame("Friday", EVENING + DAY, "Anna")))

        val after = stats().single()
        assertEquals(anna.player.id, after.player.id)
        assertEquals(2, after.gamesPlayed)
    }

    @Test
    fun `a game already on this phone is skipped, not doubled`() = runTest {
        repository.importGames(listOf(finishedGame("Thursday", EVENING, "Anna", "Boris")))

        val added = repository.importGames(
            listOf(
                finishedGame("Thursday", EVENING, "Anna", "Boris"),
                finishedGame("Friday", EVENING + DAY, "Anna", "Boris"),
            ),
        )

        assertEquals(1, added)
        assertEquals(2, repository.observeGameSummaries().first().size)
        assertEquals(2, stats().first { it.player.name == "Anna" }.gamesPlayed)
    }

    @Test
    fun `the same game listed twice in one batch lands once`() = runTest {
        val thursday = finishedGame("Thursday", EVENING, "Anna")

        assertEquals(1, repository.importGames(listOf(thursday, thursday)))
        assertEquals(1, repository.observeGameSummaries().first().size)
    }

    @Test
    fun `importing the same history twice changes nothing the second time`() = runTest {
        val history = listOf(
            finishedGame("Thursday", EVENING, "Anna", "Boris"),
            finishedGame("Friday", EVENING + DAY, "Anna"),
        )
        repository.importGames(history)

        assertEquals(0, repository.importGames(history))
        assertEquals(2, repository.observeGameSummaries().first().size)
        assertEquals(2, stats().size)
    }

    @Test
    fun `one bad game turns the whole batch away and writes nothing`() = runTest {
        val tooLong = "A".repeat(200)

        try {
            repository.importGames(
                listOf(
                    finishedGame("Thursday", EVENING, "Anna"),
                    finishedGame("Friday", EVENING + DAY, tooLong),
                ),
            )
            fail("A player name that long should have been refused")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertTrue(repository.observeGameSummaries().first().isEmpty())
        assertTrue(stats().isEmpty())
    }

    @Test
    fun `every buy-in, chip return and count comes back at the moment it happened`() = runTest {
        val source = GameSnapshot(
            game = Fixture.game(status = GameStatus.FINISHED).copy(
                name = "Thursday",
                startedAt = EVENING,
                endedAt = EVENING + 4 * HOUR,
            ),
            seats = listOf(
                Fixture.seat(1, "Anna", buyIns = listOf(Money(1_000_000), Money(500_000)), finalChips = Chips(350))
                    .copy(
                        joinedAt = EVENING,
                        cashedOutAt = EVENING + 3 * HOUR,
                        tablePosition = 2,
                        chipReturns = listOf(ChipReturn(1, Chips(40), EVENING + 2 * HOUR)),
                    )
                    .let { seat ->
                        seat.copy(buyIns = seat.buyIns.mapIndexed { i, it -> it.copy(createdAt = EVENING + i * HOUR) })
                    },
            ),
        )

        repository.importGames(listOf(source.toTransfer()))

        val id = repository.observeGameSummaries().first().single().game.id
        val stored = requireNotNull(repository.observeGame(id).first())
        assertEquals(GameStatus.FINISHED, stored.game.status)
        assertEquals(EVENING + 4 * HOUR, stored.game.endedAt)
        val anna = stored.seats.single()
        assertEquals(listOf(EVENING, EVENING + HOUR), anna.buyIns.map { it.createdAt })
        assertEquals(listOf(Money(1_000_000), Money(500_000)), anna.buyIns.map { it.amount })
        assertEquals(listOf(Chips(40)), anna.chipReturns.map { it.chips })
        assertEquals(EVENING + 2 * HOUR, anna.chipReturns.single().createdAt)
        assertEquals(Chips(350), anna.finalChips)
        assertEquals(EVENING + 3 * HOUR, anna.cashedOutAt)
        assertEquals(2, anna.tablePosition)
    }

    private companion object {
        /** 30 Aug 2026, 20:15 UTC. */
        const val EVENING = 1_788_120_900_000L
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        const val NOW = EVENING + 30 * DAY
    }
}
