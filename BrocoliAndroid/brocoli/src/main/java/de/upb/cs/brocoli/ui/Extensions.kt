package de.upb.cs.brocoli.ui

import android.support.annotation.LayoutRes
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater

/* Check https://kotlinlang.org/docs/reference/extensions.html to learn about extensions */

fun ViewGroup.inflate(@LayoutRes layoutRes: Int, attachToRoot: Boolean = false): View {
    return LayoutInflater.from(context).inflate(layoutRes, this, attachToRoot)
}
