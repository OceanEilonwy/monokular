package app.monokular.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AmountsTest {
    private val australia = Locale.forLanguageTag("en-AU")
    private val japan = Locale.JAPAN
    private val germany = Locale.GERMANY

    @Test
    fun `an amount is written the way the till's locale writes money`() {
        assertEquals("$24.50", Amounts.fiat("24.50", "USD", Locale.US))
        assertEquals("€8.00", Amounts.fiat("8", "eur", Locale.UK))
        assertEquals("¥1,200", Amounts.fiat("1200", "JPY", Locale.US))
        assertEquals("8,00\u00A0€", Amounts.fiat("8", "EUR", germany))
    }

    @Test
    fun `an amount carries the narrow symbol, never the currency's code`() {
        assertEquals("$4.50", Amounts.fiat("4.5", "USD", Locale.UK))
        assertEquals("¥1,200", Amounts.fiat("1200", "JPY", Locale.UK))
        assertEquals("¥1,200", Amounts.fiat("1200", "JPY", australia))
        assertEquals("€8.00", Amounts.fiat("8", "EUR", australia))
        assertEquals("$6.80", Amounts.fiat("6.8", "AUD", australia))
        assertEquals("¥1,200", Amounts.fiat("1200", "JPY", japan))
        assertEquals("$4.50", Amounts.fiat("4.5", "USD", japan))
        assertEquals("€8.00", Amounts.fiat("8", "EUR", japan))
        assertEquals("4,50\u00A0$", Amounts.fiat("4.5", "USD", germany))
        assertEquals("1.200\u00A0¥", Amounts.fiat("1200", "JPY", germany))
        assertEquals("£3.20", Amounts.fiat("3.2", "GBP", Locale.US))
    }

    @Test
    fun `a dollar the till's own dollar would be mistaken for says which it is`() {
        assertEquals("US$4.50", Amounts.fiat("4.5", "USD", australia))
        assertEquals("A$6.80", Amounts.fiat("6.8", "AUD", Locale.US))
        assertEquals("A$6.80", Amounts.fiat("6.8", "AUD", Locale.CANADA))
        assertEquals("CA$2.00", Amounts.fiat("2", "CAD", Locale.US))
        assertEquals("US$4.50", Amounts.fiat("4.5", "USD", Locale.CANADA))
    }

    @Test
    fun `a dollar no own dollar clashes with keeps the narrow symbol`() {
        assertEquals("$6.80", Amounts.fiat("6.8", "AUD", Locale.UK))
        assertEquals("6,80\u00A0$", Amounts.fiat("6.8", "AUD", germany))
        assertEquals("$6.80", Amounts.fiat("6.8", "AUD", japan))
        // A locale with no country has no currency of its own to clash with.
        assertEquals("$4.50", Amounts.fiat("4.5", "USD", Locale.ENGLISH))
    }

    @Test
    fun `fraction digits follow the currency`() {
        assertEquals("¥1,201", Amounts.fiat("1200.6", "JPY", Locale.US))
        assertEquals("$0.10", Amounts.fiat("0.1", "USD", Locale.US))
    }

    @Test
    fun `a currency with no symbol follows the number after a space`() {
        assertEquals("4.50 CHF", Amounts.fiat("4.5", "CHF", Locale.US))
        assertEquals("4,50 CHF", Amounts.fiat("4.5", "CHF", germany))
    }

    @Test
    fun `an amount that won't parse is shown as it came`() {
        assertEquals("4.5 XMR", Amounts.fiat("4.5", "XMR", Locale.US))
        assertEquals("lots XYZ", Amounts.fiat("lots", "XYZ", Locale.US))
        assertEquals("12 ???", Amounts.fiat("12", "???", Locale.US))
    }
}
