package com.storehop.app.ui.util

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.storehop.app.data.entity.Category
import java.util.concurrent.ConcurrentHashMap

private val categoryNameKeyResIdCache = ConcurrentHashMap<String, Int>()

@SuppressLint("DiscouragedApi")
fun resolveCategoryLabel(ctx: Context, nameKey: String?, fallbackName: String): String {
    val key = nameKey ?: return fallbackName
    val resId = categoryNameKeyResIdCache.getOrPut(key) {
        ctx.resources.getIdentifier(key, "string", ctx.packageName)
    }
    return if (resId != 0) ctx.getString(resId) else fallbackName
}

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
    return resolveCategoryLabel(ctx, nameKey, name)
}

