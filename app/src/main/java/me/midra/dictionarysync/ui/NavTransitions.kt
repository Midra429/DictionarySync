package me.midra.dictionarysync.ui

import androidx.compose.animation.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import kotlin.math.abs

/**
 * Screen transitions after the Material 3 Expressive motion guidance, on the theme's [MotionScheme] springs:
 * slow spatial springs because every transition moves a whole screen, and fast effects for whatever leaves so the two never read as overlapping.
 * - Top-level destinations (navigation bar or rail, and leaving the first-launch setup): fade through.
 * - Screens opened from another (word list on narrow screens, diagnostics): shared axis X, reversed on back.
 * - Predictive back: the screen being left shrinks and leans toward the gesture over the one underneath.
 * - The same screen in a new layout (folding, unfolding, resizing): a plain crossfade, so it does not look like navigation.
 *
 * The word list beside the source list on wide screens stays in one scene; the list-detail scaffold animates its panes itself.
 */
@Immutable
internal class NavTransitions(private val motion: MotionScheme, private val axisOffset: Int) {
    fun fadeThrough(initial: Scene<*>, target: Scene<*>): ContentTransform =
        if (sameScreen(initial, target)) crossfade()
        else fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.slowSpatialSpec(), initialScale = 0.92f) togetherWith
            fadeOut(motion.fastEffectsSpec())

    /** [axisOffset] is signed for the layout direction, so forward always moves toward the end edge. */
    fun sharedAxisX(initial: Scene<*>, target: Scene<*>, forward: Boolean): ContentTransform {
        if (sameScreen(initial, target)) return crossfade()
        val offset = if (forward) axisOffset else -axisOffset
        return slideInHorizontally(motion.slowSpatialSpec()) { offset } + fadeIn(motion.defaultEffectsSpec()) togetherWith
            slideOutHorizontally(motion.slowSpatialSpec()) { -offset } + fadeOut(motion.fastEffectsSpec())
    }

    fun predictiveBack(@NavigationEvent.SwipeEdge swipeEdge: Int): ContentTransform {
        // The finger moves away from the edge it started on; the back button has no edge and only shrinks the screen.
        val lean = when (swipeEdge) {
            NavigationEvent.EDGE_LEFT -> abs(axisOffset)
            NavigationEvent.EDGE_RIGHT -> -abs(axisOffset)
            else -> 0
        }
        return fadeIn(motion.defaultEffectsSpec()) togetherWith
            scaleOut(motion.defaultSpatialSpec(), targetScale = 0.9f) + slideOutHorizontally(motion.defaultSpatialSpec()) { lean } +
            fadeOut(motion.defaultEffectsSpec())
    }

    /** Entry metadata for a screen that is opened from another one and goes back to it. */
    fun hierarchical(): Map<String, Any> =
        NavDisplay.transitionSpec { sharedAxisX(initialState, targetState, forward = true) } +
            NavDisplay.popTransitionSpec { sharedAxisX(initialState, targetState, forward = false) }

    private fun crossfade() = fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())

    // Navigation always changes the top entry, so the same top entry in both scenes means only the layout changed.
    private fun sameScreen(initial: Scene<*>, target: Scene<*>) =
        initial.entries.lastOrNull()?.contentKey == target.entries.lastOrNull()?.contentKey
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberNavTransitions(): NavTransitions {
    val motion = MaterialTheme.motionScheme
    // Shared axis moves by 30dp, not a full screen width.
    val offset = with(LocalDensity.current) { 30.dp.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(motion, offset, rtl) { NavTransitions(motion, if (rtl) -offset else offset) }
}
