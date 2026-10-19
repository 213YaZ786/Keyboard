package helium314.keyboard.latin.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Back without the preview. Predictive back is off in these apps, but the
 * manifest's opt out is ignored for apps targeting API 37 on Android 17, and
 * the NavHost then plays its own predictive pop: the page shrinks and follows
 * the thumb over the one behind.
 *
 * A plain handler takes the gesture instead. Placed after the NavHost, it is
 * registered after the NavHost's own and so asked first: nothing moves while
 * the thumb is down, and on release the page leaves with the NavHost's pop
 * transition, like a tap on the back arrow. Off on the first screen, where
 * that screen's own handlers and then the system decide.
 */
@Composable
fun PlainBack(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    BackHandler(enabled = entry != null && navController.previousBackStackEntry != null) {
        navController.popBackStack()
    }
}
