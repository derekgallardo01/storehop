package com.storehop.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.storehop.app.R
import com.storehop.app.data.db.relations.ShoppingRow

/**
 * Plain-text formatter for the "Share list" action on Shop-at-Store. Groups
 * items by section in the order they're handed in, so the share payload reads
 * as if it were copy-pasted from the on-screen list.
 *
 * Pure formatter: takes already-localized strings (header line, section
 * labels) so the function is locale-agnostic and unit-testable without a
 * Context. Resource resolution happens in [launchShareList].
 *
 * Intentionally minimal: no aisle numbers, no prices, no quantities. The
 * recipient is expected to be a human in WhatsApp or SMS, not a parser.
 */
fun buildShareListText(
    header: String,
    sections: List<Pair<String, List<ShoppingRow>>>,
): String {
    val sb = StringBuilder()
    sb.appendLine(header)
    sb.appendLine()
    sections.forEach { (label, rows) ->
        sb.appendLine(label.uppercase())
        rows.forEach { row ->
            val brand = row.brand?.takeIf { it.isNotBlank() }
            sb.appendLine(if (brand != null) "- ${row.itemName} ($brand)" else "- ${row.itemName}")
        }
        sb.appendLine()
    }
    return sb.toString().trimEnd()
}

/**
 * Fires [Intent.ACTION_SEND] with the formatted shopping list. Android brings
 * up the system share sheet so the user picks WhatsApp, SMS, Gmail, etc.
 *
 * Resolves the header, subject, chooser title, and seeded category labels via
 * [Context.getString] so the share payload is localized to the active locale.
 */
fun launchShareList(context: Context, storeName: String, sections: List<CategorySection>) {
    val header = context.getString(R.string.share_list_header, storeName)
    val labelled = sections.map { section ->
        section.localizedLabel(context) to section.rows
    }
    val text = buildShareListText(header, labelled)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_list_subject, storeName))
    }
    val chooser = Intent.createChooser(
        intent,
        context.getString(R.string.share_list_chooser_title),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/**
 * Resolve a section's display label from its seeded `nameKey` (e.g.
 * `cat_produce` -> the active-locale `cat_produce` string resource).
 * Falls back to the raw [CategorySection.categoryName] when the section is
 * a user-added category (no nameKey) or the resource doesn't exist.
 *
 * `getIdentifier` is intentional here for the same reason as
 * [com.storehop.app.ui.util.localizedLabel]: keep the seed-pack key string
 * loosely coupled to a generated R reference. The R8 keep rule in
 * `res/raw/keep.xml` prevents the resources from being tree-shaken.
 */
@SuppressLint("DiscouragedApi")
private fun CategorySection.localizedLabel(context: Context): String {
    val key = categoryNameKey ?: return categoryName
    val resId = context.resources.getIdentifier(key, "string", context.packageName)
    return if (resId != 0) context.getString(resId) else categoryName
}
