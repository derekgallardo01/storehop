package com.storehop.app.ui.nav

/**
 * Central route constants for `NavHost`. Two tabs ([SHOP] and [ITEMS]) sit at
 * the top of the stack; their roots are `shop` and `items`. Screens nested
 * under them use slash-paths so the tab tracking in [com.storehop.app.MainActivity]
 * can match by the tab root prefix.
 */
object Routes {
    const val SHOP = "shop"
    const val ITEMS = "items"
    const val SETTINGS = "settings"

    const val SHOP_AT_STORE = "shop/store/{storeId}"
    fun shopAtStore(storeId: String) = "shop/store/$storeId"

    /** Per-store aisle reorder, reachable from the Store Picker overflow menu. */
    const val EDIT_AISLE_ORDER = "shop/store/{storeId}/aisles"
    fun editAisleOrder(storeId: String) = "shop/store/$storeId/aisles"

    const val ITEM_ADD = "items/add"
    const val ITEM_EDIT = "items/edit/{itemId}"
    fun itemEdit(itemId: String) = "items/edit/$itemId"

    /**
     * Manage Categories screen, reachable from the Items tab top-bar overflow.
     * Slash-prefixed under `items/` so it counts as an Items-tab route for the
     * bottom-nav selection logic.
     */
    const val ITEMS_CATEGORIES = "items/categories"

    /** Routes nested under the Shop tab. Used by the bottom-nav selection logic. */
    fun isShopTabRoute(route: String?): Boolean =
        route != null && (route == SHOP || route.startsWith("shop/"))

    /** Routes nested under the Items tab. */
    fun isItemsTabRoute(route: String?): Boolean =
        route != null && (route == ITEMS || route.startsWith("items/"))
}
