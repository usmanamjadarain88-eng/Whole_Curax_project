package com.curax.app

import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Lets horizontal strips (e.g. medicine chips) scroll inside vertical [android.widget.ScrollView] /
 * [androidx.core.widget.NestedScrollView] / [androidx.viewpager2.widget.ViewPager2] without the parent stealing the gesture.
 */
fun View.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown: Boolean = false) {
    val start = FloatArray(2)
    fun disallowAllParents(v: View, disallow: Boolean) {
        var p: ViewParent? = v.parent
        var depth = 0
        while (p != null && depth < 24) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
            depth++
        }
    }
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start[0] = ev.x
                start[1] = ev.y
                if (immediateDisallowOnDown) {
                    disallowAllParents(v, true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (immediateDisallowOnDown) {
                    disallowAllParents(v, true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (immediateDisallowOnDown) {
                    disallowAllParents(v, true)
                } else {
                    val dx = kotlin.math.abs(ev.x - start[0])
                    val dy = kotlin.math.abs(ev.y - start[1])
                    if (dx > dy + 4f) {
                        disallowAllParents(v, true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disallowAllParents(v, false)
            }
        }
        false
    }
}

/**
 * When a [ViewPager2] sits inside [androidx.swiperefreshlayout.widget.SwipeRefreshLayout], horizontal page swipes
 * can be mistaken for pull-to-refresh. Attach this to the pager after layout so the inner RecyclerView hands off
 * horizontal drags to the pager instead of the refresh layout intercepting them.
 */
fun ViewPager2.attachSwipeRefreshNestedHandoff() {
    post {
        (getChildAt(0) as? RecyclerView)?.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = false)
    }
}
