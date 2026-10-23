package app.monokular.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AmountsTest {
    @Test
    fun `an amount is written the way the till's locale writes money`() {
        assertEquals("$24.50", Amounts.fiat("24.50", "USD", Locale.US))
        assertEquals("€8.00", Amounts.fiat("8", "eur", Locale.UK))
        assertEquals("¥1,200", Amounts.fiat("1200", "JPY", Locale.US))
    }

    @Test
    fun `an amount that won't parse is shown as it came`() {
        assertEquals("lots XYZ", Amounts.fiat("lots", "XYZ", Locale.US))
        assertEquals("12 ???", Amounts.fiat("12", "???", Locale.US))
    }
}
