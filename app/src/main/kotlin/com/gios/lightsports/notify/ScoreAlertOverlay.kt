package com.gios.lightsports.notify

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gios.lightsports.MainActivity
import com.gios.lightsports.ui.ScoreBox
import com.gios.lightsports.ui.theme.LightSportsTheme

/**
 * The score box as a real overlay window, used whenever the screen is already on.
 *
 * The point of it being a window rather than [ScoreAlertActivity] is that **nothing else
 * is interrupted**. An activity — floating, translucent, whatever — pauses the activity
 * underneath it, so a goal arriving while you were reading something would stop that
 * something for four and a half seconds. A `TYPE_APPLICATION_OVERLAY` window doesn't: the
 * app below keeps running, and `FLAG_NOT_FOCUSABLE` (which implies `FLAG_NOT_TOUCH_MODAL`)
 * means every touch outside this box goes straight to it.
 *
 * What the window can't do is wake the panel or draw above the keyguard, which is why the
 * activity still exists for the screen-off case. See [ScoreAlert] for the split.
 */
object ScoreAlertOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    // Read by the composition, so a second score swaps the text in place rather than
    // tearing the window down and building another one.
    private val headline = mutableStateOf("")
    private val detail = mutableStateOf("")
    private var gameId: String? = null
    private var leagueId: String? = null

    /**
     * Puts the box up, or swaps the text of the one already up, and arms the timer.
     *
     * Safe to call from the poll thread: `addView` needs a Looper, so everything here is
     * posted to the main one — which is also why there is no success to report back. If
     * the window manager refuses, the notification has already gone out regardless.
     */
    fun show(context: Context, entry: PendingQueue.Entry) {
        val app = context.applicationContext
        handler.post {
            headline.value = entry.title
            detail.value = entry.body.trim().take(200)
            gameId = entry.gameId
            leagueId = entry.leagueId
            // Screen-off box already up and the user has since unlocked: the activity is
            // still there, pausing whatever is underneath. Replace it with this.
            ScoreAlertActivity.dismissLive()
            if (view == null) attach(app)
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, VISIBLE_MS)
        }
    }

    fun hide() {
        // Posted for the same reason as show: removeView is main-thread only, and this is
        // also called from the tap handler and from the activity.
        handler.post {
            handler.removeCallbacks(autoHide)
            val current = view ?: return@post
            val manager = current.context.getSystemService(WindowManager::class.java)
            runCatching { manager?.removeView(current) }
            current.disposeComposition()
            owner?.destroy()
            owner = null
            view = null
        }
    }

    private fun attach(app: Context) {
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        // A ComposeView outside an Activity has none of the owners Compose looks for in
        // the view tree. The lifecycle and saved-state owners are required — it throws on
        // first composition without them; the ViewModelStore one is set for completeness.
        val treeOwner = OverlayOwner().apply { create() }
        val composeView = ComposeView(app).apply {
            // The extensions, not ViewTreeLifecycleOwner.set: that class is a JVM file
            // facade whose `set` is a @JvmName alias only Java can call.
            setViewTreeLifecycleOwner(treeOwner)
            setViewTreeViewModelStoreOwner(treeOwner)
            setViewTreeSavedStateRegistryOwner(treeOwner)
            setContent {
                LightSportsTheme {
                    ScoreBox(
                        title = headline.value,
                        text = detail.value,
                        onClick = { openGame(app) },
                        onDismiss = { hide() },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE is the whole trick: no IME focus taken, no key events, and it
            // implies NOT_TOUCH_MODAL so touches outside our bounds reach the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Activity.attach injects this; a hand-built LayoutParams doesn't get it,
                // and without it ViewRootImpl draws the whole box on the software path.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        val added = runCatching { manager.addView(composeView, params) }.isSuccess
        if (!added) {
            composeView.disposeComposition()
            treeOwner.destroy()
            return
        }
        treeOwner.resume()
        owner = treeOwner
        view = composeView
    }

    private fun openGame(app: Context) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        gameId?.let { intent.putExtra(MainActivity.EXTRA_GAME_ID, it) }
        leagueId?.let { intent.putExtra(MainActivity.EXTRA_LEAGUE_ID, it) }
        runCatching { app.startActivity(intent) }
        hide()
    }

    /**
     * The three owners a `ComposeView` needs when it isn't inside an Activity. Minimal on
     * purpose: nothing here is ever saved or restored — the box is transient, and if the
     * process dies mid-box there is nothing worth bringing back.
     */
    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    /** Matches ScoreAlertActivity, so the box behaves the same either way it's shown. */
    private const val VISIBLE_MS = 4_500L
}
