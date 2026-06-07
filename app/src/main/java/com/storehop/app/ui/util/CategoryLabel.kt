package com.storehop.app.ui.util

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.storehop.app.data.entity.Category

/**
 * Resolves a [Category] to its localized display label.
 *
 * Seeded categories carry a `nameKey` like `"cat_produce"` that maps to a
 * `<string name="cat_produce">...</string>` resource — translated per locale.
 * User-added categories have `nameKey = null` and just use whatever the user typed.
 */
@Composable
@SuppressLint("DiscouragedApi")
fun Category.localizedLabel(): String {
    // getIdentifier is intentional: seeded categories carry a string `nameKey` like
    // "cat_produce" that maps to a translated <string name="cat_produce"> resource at
    // runtime. A lookup table from key -> R.string.* would have to be regenerated every
    // time the seed pack adds a new category, which defeats the loose coupling. The
    // R8 keep rule in res/raw/keep.xml stops the strings being tree-shaken.
    val ctx = LocalContext.current
    val key = nameKey ?: return name
    val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
    return if (resId != 0) ctx.getString(resId) else name
}
