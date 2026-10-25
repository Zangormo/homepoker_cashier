package com.zango.pokertracker.domain.settlement

import com.zango.pokertracker.R
import com.zango.pokertracker.core.text.UiText

/**
 * A settlement as lines of text, for the share and copy buttons.
 *
 * Deliberately unadorned: this gets pasted into a group chat and read by people who were not
 * looking at the app, so every line has to stand on its own without a legend.
 *
 * The lines are named rather than written, because this runs in the domain and has no way to know
 * what language the phone is in. The screen resolves them and joins them with newlines, which is
 * also why this returns the parts rather than a finished block of text.
 *
 * Payments are parted by blank lines so each one stands out in a chat bubble. The message ends
 * with [ShareFooter] and the store address on a line of its own: most chat apps only turn a bare
 * address into a link, so the screen swaps the two for a real link only where the receiving app
 * understands one.
 */
fun Settlement.shareLines(gameName: String): List<UiText> = buildList {
    add(UiText.of(R.string.settlement_share_subject, gameName))
    add(BLANK)

    if (payments.isEmpty()) {
        add(UiText.of(R.string.settlement_everyone_even))
    } else {
        payments.forEachIndexed { index, payment ->
            if (index > 0) add(BLANK)
            add(payment.toSentence())
        }
    }

    val notes = notes()
    if (notes.isNotEmpty()) {
        add(BLANK)
        addAll(notes)
    }

    add(BLANK)
    add(ShareFooter)
    add(ShareLink)
}

/** The app's page on Google Play: always the release package, so a debug build links there too. */
const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.zango.pokertracker"

/** "Settlement was made via Homepoker Cashier - check it out on Google Play!" */
val ShareFooter: UiText = UiText.of(
    R.string.settlement_share_footer,
    UiText.of(R.string.settlement_share_footer_link),
)

/** The store address under [ShareFooter], for apps that only link a bare address. */
val ShareLink: UiText = UiText.Raw(PLAY_STORE_URL)

/** "Anna pays Boris €4.50" — a whole instruction in one line, no interpretation required. */
fun Payment.toSentence(): UiText =
    UiText.of(R.string.settlement_pays, from.name, to.name, UiText.cash(amount))

/**
 * Caveats the host should see alongside the payments: money nudged by rounding, and any
 * discrepancy carried over from a chip count that did not reconcile.
 */
fun Settlement.notes(): List<UiText> = buildList {
    if (hasRoundingAdjustment && adjustedPlayer != null) {
        add(
            UiText.of(
                R.string.note_rounded,
                UiText.cash(roundingUnit),
                adjustedPlayer.name,
                UiText.cash(roundingAdjustment.abs()),
            ),
        )
    }
    if (!isBalanced) {
        add(
            UiText.of(
                R.string.note_imbalance,
                UiText.cash(imbalance.abs()),
                UiText.of(
                    if (imbalance.isNegative) R.string.note_imbalance_short
                    else R.string.note_imbalance_over,
                ),
            ),
        )
    }
    unsettled.forEach {
        add(
            UiText.of(
                if (it.net.isPositive) R.string.note_still_owed else R.string.note_still_owes,
                it.player.name,
                UiText.cash(it.net.abs()),
            ),
        )
    }
}

/** A blank line between the instructions and the small print. */
private val BLANK = UiText.Raw("")
