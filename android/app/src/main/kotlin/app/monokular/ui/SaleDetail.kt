package app.monokular.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monokular.Fact
import app.monokular.Ground
import app.monokular.PageAction
import app.monokular.PosText
import app.monokular.SalePage
import app.monokular.SaleText
import app.monokular.sales.TillSale

/**
 * A sale's page when its code isn't up (the design's row 2b): paid, paid
 * late, closed, expired, reversed, refunded, or another till's. What it
 * says comes from [SalePage]; a refund's answer turns it into the
 * Refunded page in place, or shows [refundProblem] above its buttons. At a
 * point of sale the till can't refund through, Refund opens [refundSheet]
 * (decision 130) until the sale shows refunded.
 */
@Composable
fun SaleDetail(sale: TillSale, location: String?, refunding: Boolean, refundProblem: String?, refundSheet: RefundSheet?, actions: TillActions) {
    val pos = LocalPointOfSale.current
    val page = SalePage.of(sale, pos, now = System.currentTimeMillis() / 1000)
    val subtitle = SaleText.place(sale, location) + sale.createdAt.takeIf { it > 0 }?.let { " · " + SaleText.time(it) }.orEmpty()
    Surface(Modifier.fillMaxSize(), color = mk.ground, contentColor = mk.ink) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                SaleBar(sale, subtitle, actions)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StateBadge(sale, page.icon, page.ground, 72.dp, 38.dp)
                        Text(
                            page.heading, style = display(26, 0.0), textAlign = TextAlign.Center,
                            color = if (page.headingWarn) mk.warn else mk.ink,
                            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                        )
                        Text(SaleText.amount(sale), style = display(40, -0.8), color = if (page.amountMuted) mk.muted else mk.ink)
                        Text(page.line, fontSize = 15.sp, color = mk.muted, textAlign = TextAlign.Center, modifier = Modifier.offset(y = (-8).dp))
                    }
                    page.note?.let { note ->
                        val danger = note.ground == Ground.Danger
                        Text(
                            note.text, fontSize = 14.sp, lineHeight = 21.sp, color = if (danger) mk.onDangerGround else mk.ink,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(groundColor(note.ground))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                    if (page.order) sale.receipt?.let { OrderCard(it, withTime = false) }
                    if (page.facts.isNotEmpty()) Facts(page.facts)
                }
                if (page.actions.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Notice(refundProblem)
                        for (action in page.actions) {
                            when (action) {
                                PageAction.Refund -> PrimaryButton(
                                    PosText.refundButton(pos, SaleText.amount(sale)), { actions.onRefund(sale.orderId) }, Modifier.fillMaxWidth(),
                                    enabled = !refunding, height = 56.dp, size = 16,
                                )
                                PageAction.ShowCodeAgain -> OutlineButton(
                                    "Show the code again", { actions.onReopen(sale.orderId) }, Modifier.fillMaxWidth(), height = 52.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (refundSheet != null && refundSheet.orderId == sale.orderId && !sale.refunded) {
        RefundInPosSheet(sale, refundSheet, onRefunded = { actions.onRefundDone(sale.orderId) }, onNotYet = actions.onRefundSheetDismissed)
    }
}

/** The timeline card: when it was rung up, seen, closed, paid; another till's sale's till. */
@Composable
private fun Facts(facts: List<Fact>) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(mk.card).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    for (fact in facts) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fact.label, fontSize = 14.sp, color = mk.muted, modifier = Modifier.weight(1f))
            Text(fact.value, fontSize = 14.sp, textAlign = TextAlign.End)
        }
    }
}
