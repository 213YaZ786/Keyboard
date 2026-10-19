// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.GlassKeyDrawable
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardPrivacy
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Job

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null
        set(value) {
            field = value
            rowsDirty = true
        }

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f
    var thumbnailSidePx = 0 // Keyboard: the width of a column, set by the history view

    // Keyboard: the Glass style shows cards under the headers "Pinned" and "Recent", as FlorisBoard's
    // ClipboardInputLayout.kt (HistoryMainView: clipboardItems, ClipCategoryTitle, StaggeredGridItemSpan.FullLine,
    // https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardInputLayout.kt).
    // rows holds, for each position, the index of the entry or HEADER_PINNED / HEADER_RECENT; it is built again
    // after every change of the data (the adapter's own observer below), which the view reports with notifyDataSetChanged.
    val glass = Settings.getValues().mColors.themeStyle == KeyboardTheme.STYLE_GLASS
    private var rows = IntArray(0)
    private var rowsDirty = true

    init {
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                rowsDirty = true
            }
        })
    }

    // Keyboard: the pinned entries first, then the others, each group under its header (FlorisBoard: items grouped by pinned)
    private fun updateRows() {
        if (!rowsDirty) return
        rowsDirty = false
        val manager = clipboardHistoryManager
        val size = manager?.getHistorySize() ?: 0
        val pinned = ArrayList<Int>()
        val others = ArrayList<Int>()
        for (i in 0 until size) if (manager?.getHistoryEntry(i)?.isPinned == true) pinned.add(i) else others.add(i)
        val list = ArrayList<Int>(size + 2)
        if (pinned.isNotEmpty()) { list.add(HEADER_PINNED); list.addAll(pinned) }
        if (others.isNotEmpty()) { list.add(HEADER_RECENT); list.addAll(others) }
        rows = list.toIntArray()
    }

    /** The index of the entry shown at [position] in the history, -1 for a header. */
    fun dataIndex(position: Int): Int {
        if (!glass) return position
        updateRows()
        return rows.getOrElse(position) { -1 }.coerceAtLeast(-1)
    }

    /** The position to scroll to for the entry [index] of the history: its header when it is the first of its group. */
    fun scrollPositionOf(index: Int): Int {
        if (!glass) return index
        updateRows()
        val position = rows.indexOf(index)
        return if (position > 0 && rows[position - 1] < 0) position - 1 else maxOf(position, 0)
    }

    override fun getItemViewType(position: Int) = if (glass && dataIndex(position) < 0) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) return HeaderHolder(createHeader(parent))
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    // Keyboard: ClipCategoryTitle with the clipboard-subheader style of FlorisBoard's stylesheet
    // (app/src/main/assets/extensions/org.florisboard.themes/stylesheets/floris_day.json): 14 sp, margin 6 10 6 6 dp,
    // the text in capitals, over the whole width, in the key text colour
    private fun createHeader(parent: ViewGroup): TextView {
        val density = parent.resources.displayMetrics.density
        return TextView(parent.context).apply {
            typeface = itemTypeFace
            setTextColor(itemTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = StaggeredGridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                isFullSpan = true
                setMargins((6 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val index = dataIndex(position)
        if (holder is HeaderHolder) {
            updateRows()
            val title = if (rows[position] == HEADER_PINNED) R.string.clipboard_group_pinned else R.string.clipboard_group_recent
            (holder.itemView as TextView).text = holder.itemView.context.getString(title).uppercase()
        } else if (holder is ViewHolder) {
            holder.setContent(getItem(index))
        }
    }

    // Keyboard: a card leaving the screen stops its decoding (https://developer.android.com/topic/performance/graphics/process-bitmap)
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? ViewHolder)?.cancelThumbnail()
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount(): Int {
        if (!glass) return clipboardHistoryManager?.getHistorySize() ?: 0
        updateRows()
        return rows.size
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentTextView = view.findViewById<TextView>(R.id.clipboard_entry_text_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            contentImageView = view.findViewById(R.id.clipboard_entry_image_content)
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
            if (glass) makeCard(view, colors)
        }

        // Keyboard: the job decoding this card's picture, cancelled when the card is bound to another entry
        private var thumbnailJob: Job? = null

        // stops the decoding of a card that leaves the screen (https://developer.android.com/topic/performance/graphics/process-bitmap)
        fun cancelThumbnail() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }

        // the longest side of a thumbnail: the card's column (160 dp when the view has not set it yet)
        private val thumbnailSide get() = thumbnailSidePx.takeIf { it > 0 } ?: (160 * itemView.resources.displayMetrics.density).toInt()

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            if (historyEntry == null) return
            itemView.tag = historyEntry.id
            thumbnailJob?.cancel()
            thumbnailJob = null
            contentImageView.setImageDrawable(null)
            contentImageView.minimumHeight = 0
            // Keyboard: a sensitive item shows "Sensitive content", never its text or picture; a tap still pastes it
            // (FlorisBoard's ClipboardItem.displayText, ClipboardPrivacy.shown)
            val masked = historyEntry.isSensitive
            if (masked) {
                contentTextView.text = ClipboardPrivacy.shown(null, true, itemView.context.getString(R.string.clipboard_sensitive_content))
            } else if (historyEntry.filename != null) {
                showFile(historyEntry)
            } else {
                contentTextView.text = historyEntry.text?.take(1000) // truncate displayed text for performance reasons
            }
            pinnedIconView.visibility = if (historyEntry.isPinned) View.VISIBLE else View.GONE
            contentImageView.visibility = if (historyEntry.filename != null && !masked) View.VISIBLE else View.GONE
            if (masked) contentImageView.setImageDrawable(null)
            updateTextVisibility()
        }

        // HeliBoard's line of setContent, kept as it was
        // (https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardAdapter.kt)
        private fun updateTextVisibility() {
            contentTextView.visibility = if (contentTextView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        // Keyboard: a picture is a thumbnail at the size of the card (FlorisBoard's ClipboardInputLayout IMAGE items,
        // https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardInputLayout.kt),
        // decoded off the main thread in the isolated decoder (ClipThumbnails, the recycled card not filled by an old job as
        // https://developer.android.com/topic/performance/graphics/process-bitmap); a file, or a "picture" whose bytes are none,
        // shows its type as in HeliBoard. While the decoder works the card keeps room for the picture.
        private fun showFile(entry: ClipboardHistoryEntry) {
            val side = thumbnailSide
            val cached = ClipThumbnails.cached(entry, side)
            if (cached != null) {
                contentImageView.setImageBitmap(cached)
                contentTextView.text = null
                return
            }
            if (entry.mimeTypes?.any { it.startsWith("image/") } != true || ClipThumbnails.isFile(entry)) {
                entry.setFileDescription(contentImageView, contentTextView)
                return
            }
            contentTextView.text = null
            contentImageView.minimumHeight = side * 3 / 4
            thumbnailJob = ClipThumbnails.load(itemView.context, entry, side) { bitmap ->
                contentImageView.minimumHeight = 0
                if (bitmap != null) contentImageView.setImageBitmap(bitmap) else entry.setFileDescription(contentImageView, contentTextView)
                updateTextVisibility()
            }
        }

        // Keyboard: the clipboard-item of FlorisBoard's stylesheet
        // (https://github.com/florisboard/florisboard/blob/main/app/src/main/assets/extensions/org.florisboard.themes/stylesheets/floris_day.json):
        // a glass pane (Kind.LETTER) with the corner of its shape, rounded-corner(12dp), margin 4 dp, padding 12 dp above
        // and below and 8 dp at the sides for a text, 14 sp, at most 10 lines with an ellipsis (text-max-lines, text-overflow)
        private fun makeCard(view: View, colors: Colors) {
            val density = view.resources.displayMetrics.density
            view.background = GlassKeyDrawable.forCard(view.context, colors, view)
            view.setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            (view.layoutParams as ViewGroup.MarginLayoutParams).setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            contentTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentTextView.maxLines = 10
            // the card has its own padding: the entry's margins and the pin's go
            (contentTextView.parent as View).let { box ->
                (box.layoutParams as ViewGroup.MarginLayoutParams).setMargins((6 * density).toInt(), 0, 0, 0)
            }
            (pinnedIconView.layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, 0)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            clipboardHistoryManager?.toggleClipPinned(view.tag as Long)
            return true
        }
    }

    companion object {
        private const val TYPE_ENTRY = 0
        private const val TYPE_HEADER = 1
        private const val HEADER_PINNED = -1
        private const val HEADER_RECENT = -2
    }
}
