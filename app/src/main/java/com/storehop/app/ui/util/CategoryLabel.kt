package com.storehop.app.ui.util

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
fun Category.localizedLabel(): String {
    val ctx = LocalContext.current
    val key = nameKey ?: return name
    val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
    return if (resId != 0) ctx.getString(resId) else name
}
