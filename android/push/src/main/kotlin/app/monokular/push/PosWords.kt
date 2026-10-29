package app.monokular.push

import app.monokular.core.PointOfSale

/**
 * The words Wake-ups' statuses give a point of sale, in the cashier's words
 * (acceptance T5.2): one exhaustive `when` each, so a point of sale added
 * later doesn't compile until it has them. The app's `PosText` holds the
 * screens' own words; these are the reasons a [WakeUpStatus] carries.
 */
object PosWords {
    /** The Bell runs no adapter for [pointOfSale]: refused for good. */
    fun notRung(pointOfSale: PointOfSale): String = "it doesn't ring ${pointOfSale.displayName} tills"

    /**
     * The Bell's [environment] for [pointOfSale] in words ("it's for Clover in Europe"),
     * or null for one the point of sale doesn't have: an answer this build can't read.
     */
    fun environment(pointOfSale: PointOfSale, environment: String): String? = when (pointOfSale) {
        PointOfSale.Square -> when (environment) {
            "sandbox" -> "it's for Square's sandbox"
            "production" -> "it's for Square's production accounts"
            else -> null
        }
        PointOfSale.Clover -> when (environment) {
            "sandbox" -> "it's for Clover's sandbox"
            "north_america" -> "it's for Clover in North America"
            "europe" -> "it's for Clover in Europe"
            "latin_america" -> "it's for Clover in Latin America"
            else -> null
        }
    }

    /** The point of sale didn't confirm the place the till registered for (`{pos}-refused`). */
    fun refused(pointOfSale: PointOfSale): String =
        "${pointOfSale.displayName} didn't confirm this ${place(pointOfSale)}. Sign in to ${pointOfSale.displayName} again."

    /** What a registration is for at [pointOfSale]: a Square location, a Clover merchant (its only location). */
    fun place(pointOfSale: PointOfSale?): String = when (pointOfSale) {
        PointOfSale.Square, null -> "location"
        PointOfSale.Clover -> "merchant"
    }

    /** Whose account another registration of this device is under. */
    fun account(pointOfSale: PointOfSale?): String = when (pointOfSale) {
        null -> "account"
        PointOfSale.Square, PointOfSale.Clover -> "${pointOfSale.displayName} account"
    }
}
