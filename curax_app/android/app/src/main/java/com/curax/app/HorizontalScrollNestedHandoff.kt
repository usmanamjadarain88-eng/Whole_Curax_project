package com.curax.app

import android.view.MotionEvent
import android.view.View
import android.view.ViewParent

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
                    if (dx > dy + 10f) {
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
