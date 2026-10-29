package app.monokular

import app.monokular.core.PointOfSale

/**
 * What changing the point of sale touches, in the order [PosSwitch] does it:
 * the app's graph and stores behind it, a recording fake in `PosSwitchTest`.
 */
interface PosSwitchSide {
    /** The Bell's registration taken back, awaited, while the old sign-in is still here (acceptance T5.5). */
    suspend fun takeBellRegistrationBack()

    /** The shift's sales found at [pos] keep its name once another is chosen (decision 141). */
    fun keepSalesNamed(pos: PointOfSale)

    /** Signed out of [pos]: its tokens, its location or merchant, its cursor and remembered tender id forgotten. */
    fun signOut(pos: PointOfSale)

    /** [pos] saved as the till's point of sale. */
    fun choose(pos: PointOfSale)
}

/**
 * Choosing a point of sale (acceptance D6, decision 138), from step 4's rows
 * or Settings' "Change the point of sale". Choosing the one chosen changes
 * nothing. Choosing another while signed in to the first takes the Bell's
 * registration back first, then signs out of the old one, then saves the new;
 * the Wake-ups choice and the shift's saved sales are never touched, so the
 * Bell registers again for the new point of sale once it is signed in, and
 * the old sales' pages still open, named by the point of sale that took them.
 */
object PosSwitch {
    /** Whether anything changed: the caller then shows the new point of sale's sign-in. */
    suspend fun choose(side: PosSwitchSide, from: PointOfSale?, signedIn: Boolean, to: PointOfSale): Boolean {
        if (from == to) return false
        if (from != null) side.keepSalesNamed(from)
        if (from != null && signedIn) {
            side.takeBellRegistrationBack()
            side.signOut(from)
        }
        side.choose(to)
        return true
    }
}
