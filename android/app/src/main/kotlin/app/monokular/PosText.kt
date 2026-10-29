package app.monokular

import app.monokular.core.PointOfSale

/**
 * Every word on screen that names the point of sale (acceptance D5, decision
 * A136): Square's and Clover's from one exhaustive `when` each, so a point of
 * sale added later doesn't compile until it has its words, and no screen
 * spells "Square" itself. A null point of sale is a till that runs without
 * one (or hasn't chosen yet): "your point of sale". Pure: tested in
 * `PosTextTest`, and through the screens' own texts.
 */
object PosText {
    /** "Square", "Clover", or "your point of sale" with none. */
    fun name(pos: PointOfSale?): String = pos?.displayName ?: "your point of sale"

    /** [name] at the start of a sentence: "Your point of sale" with none. */
    fun nameFirst(pos: PointOfSale?): String = name(pos).replaceFirstChar(Char::uppercase)

    /** What the till watches at the point of sale: a Square location, a Clover merchant (its only location, acceptance D1). */
    fun place(pos: PointOfSale?): String = when (pos) {
        PointOfSale.Square, null -> "location"
        PointOfSale.Clover -> "merchant"
    }

    /** [place] as a row's name: "Location", "Merchant". */
    fun placeRow(pos: PointOfSale?): String = place(pos).replaceFirstChar(Char::uppercase)

    /** "Square location", "Clover merchant". */
    fun placeAt(pos: PointOfSale?): String = pos?.let { "${it.displayName} ${place(it)}" } ?: place(null)

    /** "Square sign-in", "point-of-sale sign-in". */
    fun signInOf(pos: PointOfSale?): String = "${pos?.displayName ?: "point-of-sale"} sign-in"

    fun signIn(pos: PointOfSale?): String = "Sign in to ${name(pos)}"

    fun signInAgain(pos: PointOfSale?): String = "Sign in to ${name(pos)} again"

    fun signOut(pos: PointOfSale): String = "Sign out of ${pos.displayName}"

    /** "Signing in…" on the sign-in button while it waits. */
    const val SIGNING_IN = "Signing in…"

    /** Settings' section and onboarding step 4's heading. */
    const val POINT_OF_SALE = "Point of sale"
    const val CONNECT_HEADING = "Connect your point of sale"
    const val CHANGE = "Change the point of sale"

    /** The choice rows' details (acceptance T1.1). */
    fun choiceDetail(pos: PointOfSale): String = when (pos) {
        PointOfSale.Square -> "Square Point of Sale on a phone, tablet or Register"
        PointOfSale.Clover -> "A Clover device"
    }

    /** The Welcome screen's first feature: every point of sale the till knows. */
    val WELCOME_FEATURE = "Ring up in ${PointOfSale.entries.joinToString(" or ") { it.displayName }} as usual and choose Monero as the payment."

    // Step 4's card, for the point of sale chosen.

    fun connect(pos: PointOfSale): String = "Connect ${pos.displayName}"

    fun lead(pos: PointOfSale): String = "Monokular checks ${pos.displayName} for each Monero payment you take, so it can show the code straight away."

    fun account(pos: PointOfSale): String = "${pos.displayName} account"

    /** What the till may do at the point of sale, and that its sign-in stays here (acceptance T2.6). */
    fun permissions(pos: PointOfSale): String = when (pos) {
        PointOfSale.Square -> "Monokular can read payments, orders and locations, and refund a Monero sale that's never paid."
        PointOfSale.Clover -> "Monokular can read payments and orders, and add a Monero payment type."
    } + " Your ${pos.displayName} sign-in stays on this device: Monokulo never sees it, or what was bought."

    /** The section over the steps at the point of sale itself. */
    fun atTheTill(pos: PointOfSale): String = when (pos) {
        PointOfSale.Square -> "In Square Point of Sale"
        PointOfSale.Clover -> "In Clover Register"
    }

    /** Clover's steps once the Monero payment type is there (acceptance T3.3). */
    val CLOVER_STEPS = listOf("Open **Clover Register** on the device", "Ring up a sale and choose **Monero** as the payment")

    /** Clover refused to let the till add the payment type (acceptance T3.2, decision A129). */
    const val CLOVER_ADD_TENDER = "Add a payment type called **Monero** in Clover (Setup › Payments, or ask whoever manages your " +
        "Clover account), then tap **Look again**"
    const val LOOK_AGAIN = "Look again"

    /** How a sale never paid is refunded, said before the first one is (decision A130). */
    const val CLOVER_REFUND_NOTE = "A sale never paid in Monero is refunded in Clover's Orders app; Monokular shows you which."

    fun testSaleFound(pos: PointOfSale, amount: String): String = "Test sale found: $amount. Refund it in ${pos.displayName} when you like."

    // Square's locations, a step of its own.

    val LOCATIONS_LEAD = "Monokular shows the Monero sales ${PointOfSale.Square.displayName} takes there."
    val NO_LOCATIONS = "${PointOfSale.Square.displayName} has no active locations"

    // Steps 5 and 6.

    /** The bubble floats over the point-of-sale app on this phone; beside a Clover device, over whatever the phone shows. */
    fun floatOver(pos: PointOfSale?): String = when (pos) {
        PointOfSale.Square -> "Square"
        PointOfSale.Clover, null -> "other apps"
    }

    fun floatHeading(pos: PointOfSale?): String = "Float over ${floatOver(pos)}"

    fun floatLead(pos: PointOfSale?): String = "Monokular puts a small bubble over ${floatOver(pos)}. It's faded while there's nothing to do " +
        "and lights up when a Monero sale comes in. Tap it to show the code."

    fun readyLead(pos: PointOfSale?, bubble: Boolean): String {
        val start = when (pos) {
            PointOfSale.Square -> "Start Monokular and switch to Square."
            PointOfSale.Clover -> "Start Monokular and ring up on your Clover device."
            null -> "Start Monokular."
        }
        val wait = if (bubble) {
            "Monokular waits in a faded bubble over ${floatOver(pos)} and lights up when a sale comes in."
        } else {
            "Monokular waits in its notification and alerts you when a sale comes in."
        }
        return "$start $wait"
    }

    /** The Ready screen's check row and Settings' fact: the point of sale's name. */
    fun checkName(pos: PointOfSale?): String = pos?.displayName ?: POINT_OF_SALE

    // Settings.

    /** Under the signed-in facts. */
    fun signedInFootnote(pos: PointOfSale): String = "Sales paid with this payment type at this ${place(pos)} show up in Monokular."

    /** Under the sign-in row, or the choice rows. */
    fun withoutFootnote(pos: PointOfSale?): String =
        "Without ${pos?.displayName ?: "a point of sale"}, Monokular shows the sales other tills make, without receipts."

    fun bubbleFootnote(pos: PointOfSale?): String = "A faded bubble floats over ${floatOver(pos)} and lights up when a sale comes in. Drag it to either side."

    /** The debug build's field for a stand-in on the developer's machine (acceptance T6.5). */
    fun standIn(pos: PointOfSale): String = "Stand-in ${pos.displayName} address"

    /** The Wake-ups status card's pace row. */
    fun checking(pos: PointOfSale?): String = "Checking ${name(pos)}"

    // A sale's words, by the till's point of sale: Monokulo doesn't say which point of sale took another till's
    // sale, and a sale kept from before the point of sale changed is named by the new one (decision A138).

    fun refundedIn(pos: PointOfSale?): String = "Refunded in ${name(pos)}"

    fun refundItIn(pos: PointOfSale?): String = "refund it in ${name(pos)}"

    fun refundButton(pos: PointOfSale?, amount: String): String = "Refund $amount in ${name(pos)}"

    /** Another till's sale: its point of sale is on that till. */
    fun refundOnOtherTill(pos: PointOfSale?, till: String): String = "Refund it in ${name(pos)} on $till."

    fun hasItAsMonero(pos: PointOfSale?): String = "${nameFirst(pos)} has this sale as Monero"

    /**
     * What the till built a receipt from: Square's receipt, Clover's order (Clover prints no
     * receipt number, acceptance D7); a receipt kept after signing out of either, just that.
     */
    fun receiptLabel(pos: PointOfSale?, receiptId: String): String = when (pos) {
        PointOfSale.Square -> "Square receipt $receiptId"
        PointOfSale.Clover -> "Clover order $receiptId"
        null -> "Receipt $receiptId"
    }

    /** The live sale's button back to the point-of-sale app on this phone. */
    fun backTo(pos: PointOfSale?): String = "Back to ${pos?.displayName ?: "the till"}"

    /** The shift's own footing, and the empty shift's hint. */
    fun waitingHint(pos: PointOfSale?): String = when (pos) {
        PointOfSale.Square -> "The code appears here, and the bubble over Square lights up, when you tap Monero in Square."
        PointOfSale.Clover -> "The code appears here, and the bubble lights up, when you choose Monero on the Clover device."
        null -> "The code appears here, and the bubble lights up, when another till rings up a Monero sale."
    }

    fun startHint(pos: PointOfSale?): String = "Start watching and Monokular checks ${name(pos)} for Monero sales until you stop it."

    /** The refund sheet for a point of sale whose API can't refund (acceptance T4.1): [posName] and [where] are the source's. */
    fun refundSheetTitle(posName: String): String = "Refund in $posName"

    /** What to refund, and where: the order by its id, when it was rung up, the amount. */
    fun refundSheetBody(where: String, order: String?, time: String?, amount: String): String =
        "This sale wasn't paid in Monero. Refund it in $where: " +
            listOfNotNull(order?.let { "order $it" }, time, amount).joinToString(", ") +
            ", paid with Monero. Monokular will notice when it's done."

    /** "I've refunded it" asked, and the point of sale doesn't show it. */
    fun refundNotSeen(posName: String, where: String): String = "$posName doesn't show a refund yet. Finish it in $where, then tap again."

    const val REFUNDED_IT = "I've refunded it"
    const val NOT_YET = "Not yet"
    const val LOOKING = "Looking…"
}
