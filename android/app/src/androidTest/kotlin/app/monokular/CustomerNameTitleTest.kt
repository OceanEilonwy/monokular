package app.monokular

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.monokular.OneClockTest.Companion.noActions
import app.monokular.core.SalePhase
import app.monokular.ui.SaleScreen
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * A sale whose order names whom it's for is titled by the name on its
 * live page and its detail page, the sale's number kept on the line under
 * it, and TalkBack reads both as the page's heading.
 */
class CustomerNameTitleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aLiveSaleIsTitledByTheCustomer() = titled(testSale("m2Qe").copy(customerName = "Ana López"))

    @Test
    fun aPaidSaleIsTitledByTheCustomer() = titled(testSale("m2Qe", phase = SalePhase.Paid, paidAt = 1_761_000_100).copy(customerName = "Ana López"))

    @Test
    fun aSaleNamingNoOneKeepsItsNumber() {
        compose.setContent { Till { SaleScreen(testSale("m2Qe"), "Front", refunding = false, refundProblem = null, refundSheet = null, actions = noActions) } }
        compose.onNodeWithText("Sale #m2Qe", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Front · this till", useUnmergedTree = true).assertExists()
    }

    private fun titled(sale: app.monokular.sales.TillSale) {
        compose.setContent { Till { SaleScreen(sale, "Front", refunding = false, refundProblem = null, refundSheet = null, actions = noActions) } }
        compose.onNodeWithText("Ana López", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Sale #m2Qe · Front · this till", substring = true, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Sale #m2Qe", useUnmergedTree = true).assertCountEquals(0)
        // The bar is one heading for TalkBack: the name, then the number and where it's from.
        val heading = compose.onNodeWithText("Ana López", substring = true).fetchSemanticsNode()
        val said = heading.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ")
        assertTrue(heading.config.contains(SemanticsProperties.Heading), "the bar is a heading")
        assertTrue(said.startsWith("Ana López") && "Sale #m2Qe" in said, said)
    }
}
