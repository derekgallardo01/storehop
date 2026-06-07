package com.storehop.app.ui.util

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * Shared KeyboardOptions for any user-facing name field — Item name, brand,
 * category name, store name, the Shop-at-Store quick-add bar. Auto-capitalizes
 * the first letter of every word the user types. v0.5.1 used Sentences (only
 * the first letter); Mike followed up asking for each-word capitalization
 * because product / brand / store / category names are typically Title Case
 * ("Shredded Mozzarella", "Pingo Doce", "Dairy & Eggs").
 *
 * Search/filter inputs deliberately do NOT use this: capitalizing a search
 * query is friction with no payoff.
 */
val WordCaps: KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Words,
)
