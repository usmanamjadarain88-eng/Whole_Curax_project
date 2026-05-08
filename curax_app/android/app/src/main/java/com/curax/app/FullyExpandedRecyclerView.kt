package com.curax.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Expands vertically to fit all items when placed inside [HorizontalScrollView], [NestedScrollView],
 * or other parents that give [RecyclerView] an unbounded height — default RV often measures 0.
 */
class FullyExpandedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // UNSPECIFIED lets RV lay out all children inside nested scroll parents (AT_MOST often leaves 0 height).
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }
}
