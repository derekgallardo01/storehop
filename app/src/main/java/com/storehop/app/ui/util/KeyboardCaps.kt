package com.storehop.app.ui.util

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * Shared KeyboardOptions for any user-facing name field — Item name, brand,
 * category name, store name, the Shop-at-Store quick-add bar. Tells the IME
 * to start with shift on so the first letter capitalizes automatically;
 * Mike-reported feedback that having to manually shift each new entry was
 * grating. Sentence (vs. Words) capitalization matches the literal request
 * — only the first letter, not every word.
 *
 * Search/filter inputs deliberately do NOT use this: capitalizing a search
 * query is friction with no payoff.
 */
val SentenceCaps: KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
)
