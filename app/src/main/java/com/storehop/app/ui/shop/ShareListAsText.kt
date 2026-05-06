package com.storehop.app.ui.shop

import android.content.Context
import android.content.Intent

/**
 * Plain-text formatter for the "Share list" action on Shop-at-Store. Groups
 * needed items by category in the same order the screen renders them, so the
 * share payload reads as if it were copy-pasted from the on-screen list.
 *
 * Intentionally minimal: no aisle numbers, no prices, no quantities. The
 * recipient is expected to be a human in WhatsApp or SMS, not a parser.
 */
fun buildShareListText(storeName: String, sections: List<CategorySection>): String {
    val sb = StringBuilder()
    sb.appendLine("StoreHop — shopping at $storeName")
    sb.appendLine()
    sections.forEach { section ->
        sb.appendLine(section.categoryName.uppercase())
        section.rows.forEach { row ->
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
 */
fun launchShareList(context: Context, storeName: String, sections: List<CategorySection>) {
    val text = buildShareListText(storeName, sections)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Shopping at $storeName")
    }
    val chooser = Intent.createChooser(intent, "Share shopping list").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
