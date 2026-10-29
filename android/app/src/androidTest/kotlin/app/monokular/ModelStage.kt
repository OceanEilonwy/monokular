package app.monokular

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * The view model as the stage sees it (`AppViewModel.back`, `backTarget`,
 * `back(from)`): a screen, where Back goes from it, and a Back that acts
 * only from the screen still up. [commits] records each Back the stage
 * delivered, with the screen it said it left.
 */
class TestModel(start: Screen) {
    val screen = mutableStateOf(start)
    val commits = mutableListOf<Screen>()
    var cameUpByItself = false

    /** Where Back goes from here, as `AppViewModel.backTarget` says. */
    var backTarget: () -> Screen? = {
        when (val s = screen.value) {
            is Screen.Sale -> Screen.Till
            is Screen.Code -> Screen.Sale(s.orderId)
            is Screen.Order -> Screen.Sale(s.orderId)
            else -> null
        }
    }

    fun go(to: Screen, byItself: Boolean = false) {
        cameUpByItself = byItself
        screen.value = to
    }

    /** Back from [from] only if it's still up (decision 77). */
    fun back(from: Screen) {
        commits += from
        if (screen.value == from) backTarget()?.let { go(it) }
    }

    /** The stage, once [ModelStage] has made it. */
    lateinit var stage: SeekableTransitionState<Screen>
}

/** The activity's stage over [model]: the one driver, Android's back, and the screens. */
@Composable
fun ModelStage(model: TestModel, content: @Composable (Screen) -> Unit) {
    val state = remember { SeekableTransitionState(model.screen.value).also { model.stage = it } }
    val scrub = remember { BackScrub() }
    FollowScreen(state, scrub, model.screen)
    StageBack(
        enabled = model.screen.value != Screen.Till, state = state, scrub = scrub,
        current = { model.screen.value }, target = { model.backTarget() }, onCommit = model::back,
    )
    TillStage(state, cameUpByItself = { model.cameUpByItself }, scrub, content)
}
