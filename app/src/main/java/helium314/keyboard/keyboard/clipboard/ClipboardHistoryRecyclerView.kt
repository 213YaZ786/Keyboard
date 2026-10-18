// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.abs
import androidx.core.view.isVisible
import androidx.core.view.isInvisible

class ClipboardHistoryRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var placeholderView: View? = null
    val historyManager: ClipboardHistoryManager? get() = (adapter as? ClipboardAdapter?)?.clipboardHistoryManager
    @Suppress("unused")
    // Keyboard: a swipe right pins or unpins the card and a swipe left deletes it (HeliBoard's request #1313,
    // https://github.com/HeliBorg/HeliBoard/issues/1313); HeliBoard's callback, which only deleted on a swipe left
    // (https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardHistoryRecyclerView.kt),
    // takes both directions as AndroidX's ItemTouchHelper.SimpleCallback
    // (https://github.com/androidx/androidx/blob/androidx-main/recyclerview/recyclerview/src/main/java/androidx/recyclerview/widget/ItemTouchHelper.java)
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        // the last offset of the finger on the card being swiped: tells a swipe that goes through from one that is given up
        private var releaseDx = 0f

        override fun onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) = false

        // ItemTouchHelper measures the threshold on the whole list's width, far more than a card on a tablet: a share of the card
        override fun getSwipeThreshold(viewHolder: ViewHolder) = SWIPE_FRACTION * viewHolder.itemView.width / width.coerceAtLeast(1)
        // Keyboard: the entry's index in the history. With the headers of the Glass style (ClipboardAdapter.dataIndex,
        // after FlorisBoard's ClipCategoryTitle items in ClipboardInputLayout.kt,
        // https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardInputLayout.kt)
        // it is not the position
        private fun indexOf(viewHolder: ViewHolder) =
            (adapter as? ClipboardAdapter)?.dataIndex(viewHolder.absoluteAdapterPosition) ?: viewHolder.absoluteAdapterPosition

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: ViewHolder): Int {
            if (indexOf(viewHolder) < 0) return 0 // a header
            if (historyManager?.canRemove(indexOf(viewHolder)) == false)
                return ItemTouchHelper.RIGHT // Keyboard: a pinned card is not deleted by a swipe, only unpinned (swipe right)
            return super.getSwipeDirs(recyclerView, viewHolder)
        }
        override fun onSwiped(viewHolder: ViewHolder, dir: Int) {
            val index = indexOf(viewHolder)
            val manager = historyManager ?: return
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, viewHolder.itemView, HapticEvent.KEY_PRESS)
            if (dir == ItemTouchHelper.RIGHT) {
                // Keyboard: pin or unpin, as the long press does; the dao reports the move and the view sorts itself again
                manager.getHistoryEntry(index)?.let { manager.toggleClipPinned(it.id) }
                return
            }
            manager.removeEntry(index)
            if ((adapter as? ClipboardAdapter)?.glass == true) adapter?.notifyDataSetChanged() // the headers change too
            else adapter?.notifyItemRemoved(viewHolder.absoluteAdapterPosition)
        }

        // Keyboard: ItemTouchHelper's onChildDraw (the same override as in the AndroidX sample of swipe actions,
        // https://developer.android.com/reference/androidx/recyclerview/widget/ItemTouchHelper.Callback#onChildDraw):
        // under the card an icon appears, the pin on a swipe right, the bin on a swipe left, clearer as the card
        // goes. A swipe right is no deletion: the card follows the finger up to SWIPE_FRACTION of its width and, once
        // let go past it, slides back into place while its pin changes.
        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE || dX == 0f) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                return
            }
            val view = viewHolder.itemView
            val travel = SWIPE_FRACTION * view.width
            var shown = dX
            if (dX > 0f) {
                if (isCurrentlyActive) releaseDx = dX
                shown = if (isCurrentlyActive || dX < releaseDx) minOf(dX, travel) // following the finger, or given up and going back
                    else minOf(releaseDx, travel) * (1f - ((dX - releaseDx) / (view.width - releaseDx).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            drawActionIcon(c, view, shown, travel, dX > 0f)
            super.onChildDraw(c, recyclerView, viewHolder, shown, dY, actionState, isCurrentlyActive)
        }

        // the icon under the card, drawn as ItemTouchHelper's own sample does in onChildDraw (see above)
        private fun drawActionIcon(c: Canvas, view: View, shown: Float, travel: Float, pin: Boolean) {
            val density = resources.displayMetrics.density
            val size = (ICON_DP * density).toInt()
            val pad = (ICON_PAD_DP * density).toInt()
            val icon = (if (pin) (adapter as? ClipboardAdapter)?.pinnedIconResId?.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }
                else KeyboardIconsSet.instance.getIconDrawable(KeyboardIconsSet.NAME_BIN))?.mutate() ?: return
            Settings.getValues().mColors.setColor(icon, if (pin) ColorType.CLIPBOARD_PIN else ColorType.KEY_TEXT)
            icon.alpha = (255 * (abs(shown) / travel).coerceIn(0f, 1f)).toInt()
            val top = view.top + (view.height - size) / 2
            val left = if (pin) view.left + pad else view.right - pad - size
            icon.setBounds(left, top, left + size, top + size)
            c.save()
            // only the part the card has left uncovered
            if (pin) c.clipRect(view.left.toFloat(), view.top.toFloat(), view.left + shown, view.bottom.toFloat())
            else c.clipRect(view.right + shown, view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
            icon.draw(c)
            c.restore()
        }
    }).attachToRecyclerView(this)

    private val adapterDataObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            checkAdapterContentChange()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }
    }

    private fun checkAdapterContentChange() {
        if (placeholderView == null) return
        val adapterIsEmpty = adapter == null || adapter?.itemCount == 0
        if (isVisible && adapterIsEmpty) {
            placeholderView!!.visibility = VISIBLE
            visibility = INVISIBLE
        } else if (isInvisible && !adapterIsEmpty) {
            placeholderView!!.visibility = INVISIBLE
            visibility = VISIBLE
        }
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(adapterDataObserver)
        super.setAdapter(adapter)
        checkAdapterContentChange()
        adapter?.registerAdapterDataObserver(adapterDataObserver)
    }

    private companion object {
        const val SWIPE_FRACTION = 0.4f // of the card's width: where a swipe goes through, and how far a pin swipe shows
        const val ICON_DP = 24f
        const val ICON_PAD_DP = 16f
    }

}
