package app.monokular.core

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Amounts as people read them. */
object Amounts {
    /**
     * [amount] (a decimal string, as Monokulo sends it) in [currency], the
     * way [locale] writes money: "24.50" USD is "$24.50" in the US and
     * "US$24.50" in Britain. Anything that won't parse is shown as given.
     */
    fun fiat(amount: String, currency: String, locale: Locale = Locale.getDefault()): String = runCatching {
        val code = Currency.getInstance(currency.uppercase(Locale.ROOT))
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = code
            minimumFractionDigits = code.defaultFractionDigits.coerceAtLeast(0)
            maximumFractionDigits = code.defaultFractionDigits.coerceAtLeast(0)
        }
        format.format(BigDecimal(amount))
    }.getOrElse { "$amount $currency" }
}
