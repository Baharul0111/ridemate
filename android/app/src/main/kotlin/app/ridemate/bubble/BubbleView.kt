package app.ridemate.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import app.ridemate.R
import app.ridemate.core.config.CancelStep
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bubble's content, built from plain views (overlay windows have no Compose lifecycle). The round head is the
 * drag handle and opens or closes the panel; the panel holds the status line, the instruction card, the "Paste
 * drop" chip and the action buttons. Every control is at least [MIN_TOUCH_DP] dp and has a spoken label; states are
 * shown with icons and text, never by colour alone.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val onAction: (BubbleAction) -> Unit,
    private val onHeadTap: () -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
) : LinearLayout(context) {

    val head: ImageButton = ImageButton(context)
    val panel: LinearLayout = LinearLayout(context)

    /** The actions currently shown, in order (tests and the notification mirror read it). */
    var shownActions: List<BubbleAction> = emptyList()
        private set

    var expanded: Boolean = true
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setupHead()
        panel.orientation = VERTICAL
        panel.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(ContextCompat.getColor(context, R.color.bubble_panel_background))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.bubble_panel_outline))
        }
        val pad = dp(12)
        panel.setPadding(pad, pad, pad, pad)
        panel.minimumWidth = dp(PANEL_MIN_WIDTH_DP)
        addView(head, LayoutParams(dp(HEAD_DP), dp(HEAD_DP)))
        addView(panel, LayoutParams(dp(PANEL_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHead() {
        head.id = R.id.bubble_head
        head.setImageResource(R.drawable.ic_notification)
        head.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.rm_on_brand))
        head.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.rm_brand))
        }
        head.minimumWidth = dp(HEAD_DP)
        head.minimumHeight = dp(HEAD_DP)
        head.scaleType = ImageView.ScaleType.CENTER_INSIDE
        head.contentDescription = context.getString(R.string.bubble_head_description)
        head.setOnClickListener { onHeadTap() }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false
        head.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY; dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                        dragging = true
                        v.isPressed = false
                    }
                    if (dragging) {
                        onDrag((event.rawX - lastX).roundToInt(), (event.rawY - lastY).roundToInt())
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) onDragEnd() else v.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) onDragEnd()
                    dragging = false
                }
            }
            true
        }
    }

    /** Re-renders the panel for [state]; [status]/[busy] are the merged status line. */
    fun render(state: BubbleState, status: BubbleStatus?, busy: Boolean, expanded: Boolean) {
        this.expanded = expanded
        head.contentDescription = context.getString(R.string.bubble_head_description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            head.stateDescription = context.getString(if (expanded) R.string.bubble_head_open else R.string.bubble_head_closed)
        }
        panel.removeAllViews()
        panel.visibility = if (expanded) VISIBLE else GONE
        if (status != null || busy) panel.addView(statusRow(status, busy))
        state.card?.let { addCard(it) }
        val actions = state.actions.distinct()
        if (BubbleAction.PASTE_DROP in actions) panel.addView(chip(BubbleAction.PASTE_DROP))
        actions.filter { it != BubbleAction.PASTE_DROP }.forEach { action ->
            panel.addView(actionButton(action, enabled = !(busy && action == BubbleAction.READ_FARES), label = state.labelFor(action)))
        }
        shownActions = actions
    }

    /** The control for [action], or null when it isn't shown. */
    fun controlFor(action: BubbleAction): View? = panel.findViewWithTag(action)

    private fun statusRow(status: BubbleStatus?, busy: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = R.id.bubble_status
            minimumHeight = dp(MIN_TOUCH_DP)
        }
        if (busy) {
            row.addView(ProgressBar(context).apply {
                isIndeterminate = true
                contentDescription = context.getString(R.string.bubble_status_working)
            }, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        } else if (status != null) {
            row.addView(ImageView(context).apply {
                setImageResource(iconFor(status.kind))
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bubble_text))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        }
        val text = status?.text ?: context.getString(R.string.bubble_status_reading)
        val kindLabel = context.getString(kindLabelFor(if (busy && status == null) StatusKind.WORKING else status?.kind ?: StatusKind.WORKING))
        row.addView(TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.bubble_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentDescription = context.getString(R.string.bubble_status_spoken, kindLabel, text)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun addCard(card: InstructionCard) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            id = R.id.bubble_card
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(TextView(context).apply {
            text = card.title
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.bubble_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isFocusable = true
        })
        card.steps.forEachIndexed { i, step ->
            box.addView(TextView(context).apply {
                text = numbered(i + 1, step)
                setTextColor(ContextCompat.getColor(context, R.color.bubble_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(2), 0, 0)
            })
        }
        card.note?.let { note ->
            box.addView(TextView(context).apply {
                text = note
                setTextColor(ContextCompat.getColor(context, R.color.bubble_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        panel.addView(box)
    }

    private fun numbered(n: Int, step: CancelStep): CharSequence {
        val out = SpannableStringBuilder("$n. ")
        for (segment in step.segments) {
            val start = out.length
            out.append(segment.text)
            if (segment.bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun chip(action: BubbleAction): View = Button(context).apply {
        tag = action
        text = context.getString(action.labelRes)
        contentDescription = context.getString(action.descriptionRes)
        isAllCaps = false
        minimumHeight = dp(MIN_TOUCH_DP)
        minHeight = dp(MIN_TOUCH_DP)
        minimumWidth = dp(MIN_TOUCH_DP)
        minWidth = dp(MIN_TOUCH_DP)
        setTextColor(ContextCompat.getColor(context, R.color.rm_brand))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(ContextCompat.getColor(context, R.color.bubble_chip_background))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.rm_brand))
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(action.iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.rm_brand)))
        setOnClickListener { onAction(action) }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun actionButton(action: BubbleAction, enabled: Boolean, label: String? = null): View = Button(context).apply {
        tag = action
        text = label ?: context.getString(action.labelRes)
        // A label that names the app says everything; the generic description would contradict it ("this app").
        contentDescription = label ?: context.getString(action.descriptionRes)
        isAllCaps = false
        isEnabled = enabled
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = dp(MIN_TOUCH_DP)
        minHeight = dp(MIN_TOUCH_DP)
        minimumWidth = dp(MIN_TOUCH_DP)
        minWidth = dp(MIN_TOUCH_DP)
        setTextColor(ContextCompat.getColor(context, R.color.bubble_button_text))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(ContextCompat.getColor(context, R.color.bubble_button_background))
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(action.iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(10)
        TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bubble_button_text)))
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onAction(action) }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Goal 27: comfortable touch targets. */
        const val MIN_TOUCH_DP = 48
        const val HEAD_DP = 56
        const val PANEL_WIDTH_DP = 248
        const val PANEL_MIN_WIDTH_DP = 200

        fun iconFor(kind: StatusKind): Int = when (kind) {
            StatusKind.INFO -> R.drawable.ic_bubble_info
            StatusKind.WORKING -> R.drawable.ic_bubble_info
            StatusKind.PROBLEM -> R.drawable.ic_bubble_problem
            StatusKind.REMINDER -> R.drawable.ic_bubble_reminder
        }

        fun kindLabelFor(kind: StatusKind): Int = when (kind) {
            StatusKind.INFO -> R.string.bubble_status_kind_info
            StatusKind.WORKING -> R.string.bubble_status_working
            StatusKind.PROBLEM -> R.string.bubble_status_kind_problem
            StatusKind.REMINDER -> R.string.bubble_status_kind_reminder
        }
    }
}
