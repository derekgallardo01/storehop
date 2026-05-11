import Foundation

/// Embeds the seed JSON contents directly in Swift source rather than
/// loading them via the app bundle at runtime. After ~10 hours of
/// xcodegen + xcodebuild + simulator-bundle debugging it turned out the
/// macos-15 CI runner just refuses to copy the seed JSONs into
/// `Storehop.app` regardless of whether they're declared as
/// `type: folder`, individually flat-listed, or both — `find Storehop.app
/// -name "*.json"` returned zero matches on every variation. Locally
/// xcodebuild works fine; in CI it silently drops them.
///
/// The brute-force fix: keep the JSON files on disk for editing
/// convenience and the privacy / linter advantages of "real JSON", and
/// have this Swift file mirror their literal contents. The seeder
/// fallback below reads from `bundledSeedJson` when `Bundle.url(...)`
/// returns nil. The mirror is checked in lockstep with the on-disk
/// JSONs via a `testBundledSeedJsonMatchesOnDiskFiles` test.
///
/// Maintenance: if the seed pack changes (new store, new category,
/// reordered aisle plan), update both the on-disk JSON and the
/// matching constant below. The unit test catches drift.
enum BundledSeedJson {

    /// Mirror of `Storehop/Resources/seed/stores.json`.
    static let stores: String = #"""
[
  { "id": "store_aldi",            "name": "Aldi" },
  { "id": "store_auchan",          "name": "Auchan" },
  { "id": "store_continente",      "name": "Continente" },
  { "id": "store_flavers",         "name": "Flavers" },
  { "id": "store_glovo",           "name": "Glovo" },
  { "id": "store_leroy_merlin",    "name": "Leroy Merlin" },
  { "id": "store_liberty_store",   "name": "Liberty Store" },
  { "id": "store_lidl",            "name": "Lidl" },
  { "id": "store_mega_store",      "name": "Mega Store" },
  { "id": "store_normal",          "name": "Normal" },
  { "id": "store_oriental_market", "name": "Oriental Market" },
  { "id": "store_pharmacia",       "name": "Pharmacia" },
  { "id": "store_pingo_doce",      "name": "Pingo Doce" },
  { "id": "store_wells",           "name": "Well's" }
]
"""#

    /// Mirror of `Storehop/Resources/seed/categories.json`.
    static let categories: String = #"""
[
  { "id": "cat_produce",       "name": "Produce",             "nameKey": "cat_produce",       "icon": "Eco" },
  { "id": "cat_bakery",        "name": "Bakery",              "nameKey": "cat_bakery",        "icon": "BakeryDining" },
  { "id": "cat_dairy_eggs",    "name": "Dairy & Eggs",        "nameKey": "cat_dairy_eggs",    "icon": "EggAlt" },
  { "id": "cat_meat_fish",     "name": "Meat & Fish",         "nameKey": "cat_meat_fish",     "icon": "SetMeal" },
  { "id": "cat_frozen",        "name": "Frozen",              "nameKey": "cat_frozen",        "icon": "AcUnit" },
  { "id": "cat_pantry",        "name": "Pantry",              "nameKey": "cat_pantry",        "icon": "Kitchen" },
  { "id": "cat_snacks",        "name": "Snacks",              "nameKey": "cat_snacks",        "icon": "Cookie" },
  { "id": "cat_beverages",     "name": "Beverages",           "nameKey": "cat_beverages",     "icon": "LocalCafe" },
  { "id": "cat_alcohol",       "name": "Alcohol",             "nameKey": "cat_alcohol",       "icon": "WineBar" },
  { "id": "cat_household",     "name": "Household & Cleaning","nameKey": "cat_household",     "icon": "CleaningServices" },
  { "id": "cat_personal_care", "name": "Personal Care",       "nameKey": "cat_personal_care", "icon": "Soap" },
  { "id": "cat_pharmacy",      "name": "Pharmacy",            "nameKey": "cat_pharmacy",      "icon": "Medication" },
  { "id": "cat_baby",          "name": "Baby",                "nameKey": "cat_baby",          "icon": "ChildCare" },
  { "id": "cat_pet",           "name": "Pet",                 "nameKey": "cat_pet",           "icon": "Pets" },
  { "id": "cat_hardware",      "name": "Hardware & DIY",      "nameKey": "cat_hardware",      "icon": "Build" },
  { "id": "cat_garden",        "name": "Garden",              "nameKey": "cat_garden",        "icon": "Grass" },
  { "id": "cat_home_decor",    "name": "Home & Decor",        "nameKey": "cat_home_decor",    "icon": "Chair" },
  { "id": "cat_electronics",   "name": "Electronics",         "nameKey": "cat_electronics",   "icon": "Devices" },
  { "id": "cat_stationery",    "name": "Stationery",          "nameKey": "cat_stationery",    "icon": "EditNote" },
  { "id": "cat_clothing",      "name": "Clothing",            "nameKey": "cat_clothing",      "icon": "Checkroom" },
  { "id": "cat_other",         "name": "Other",               "nameKey": "cat_other",         "icon": "MoreHoriz" }
]
"""#

    /// Mirror of `Storehop/Resources/seed/store_categories.json`.
    static let storeCategories: String = #"""
[
  { "storeId": "store_lidl", "categoryId": "cat_produce",       "displayOrder": 0  },
  { "storeId": "store_lidl", "categoryId": "cat_bakery",        "displayOrder": 1  },
  { "storeId": "store_lidl", "categoryId": "cat_dairy_eggs",    "displayOrder": 2  },
  { "storeId": "store_lidl", "categoryId": "cat_meat_fish",     "displayOrder": 3  },
  { "storeId": "store_lidl", "categoryId": "cat_frozen",        "displayOrder": 4  },
  { "storeId": "store_lidl", "categoryId": "cat_pantry",        "displayOrder": 5  },
  { "storeId": "store_lidl", "categoryId": "cat_snacks",        "displayOrder": 6  },
  { "storeId": "store_lidl", "categoryId": "cat_beverages",     "displayOrder": 7  },
  { "storeId": "store_lidl", "categoryId": "cat_alcohol",       "displayOrder": 8  },
  { "storeId": "store_lidl", "categoryId": "cat_household",     "displayOrder": 9  },
  { "storeId": "store_lidl", "categoryId": "cat_personal_care", "displayOrder": 10 },
  { "storeId": "store_lidl", "categoryId": "cat_baby",          "displayOrder": 11 },
  { "storeId": "store_lidl", "categoryId": "cat_pet",           "displayOrder": 12 },

  { "storeId": "store_pingo_doce", "categoryId": "cat_produce",       "displayOrder": 0  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_bakery",        "displayOrder": 1  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_dairy_eggs",    "displayOrder": 2  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_meat_fish",     "displayOrder": 3  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_frozen",        "displayOrder": 4  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_pantry",        "displayOrder": 5  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_snacks",        "displayOrder": 6  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_beverages",     "displayOrder": 7  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_alcohol",       "displayOrder": 8  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_household",     "displayOrder": 9  },
  { "storeId": "store_pingo_doce", "categoryId": "cat_personal_care", "displayOrder": 10 },
  { "storeId": "store_pingo_doce", "categoryId": "cat_baby",          "displayOrder": 11 },
  { "storeId": "store_pingo_doce", "categoryId": "cat_pet",           "displayOrder": 12 },

  { "storeId": "store_continente", "categoryId": "cat_produce",       "displayOrder": 0  },
  { "storeId": "store_continente", "categoryId": "cat_bakery",        "displayOrder": 1  },
  { "storeId": "store_continente", "categoryId": "cat_dairy_eggs",    "displayOrder": 2  },
  { "storeId": "store_continente", "categoryId": "cat_meat_fish",     "displayOrder": 3  },
  { "storeId": "store_continente", "categoryId": "cat_frozen",        "displayOrder": 4  },
  { "storeId": "store_continente", "categoryId": "cat_pantry",        "displayOrder": 5  },
  { "storeId": "store_continente", "categoryId": "cat_snacks",        "displayOrder": 6  },
  { "storeId": "store_continente", "categoryId": "cat_beverages",     "displayOrder": 7  },
  { "storeId": "store_continente", "categoryId": "cat_alcohol",       "displayOrder": 8  },
  { "storeId": "store_continente", "categoryId": "cat_household",     "displayOrder": 9  },
  { "storeId": "store_continente", "categoryId": "cat_personal_care", "displayOrder": 10 },
  { "storeId": "store_continente", "categoryId": "cat_baby",          "displayOrder": 11 },
  { "storeId": "store_continente", "categoryId": "cat_pet",           "displayOrder": 12 },

  { "storeId": "store_auchan", "categoryId": "cat_produce",       "displayOrder": 0  },
  { "storeId": "store_auchan", "categoryId": "cat_bakery",        "displayOrder": 1  },
  { "storeId": "store_auchan", "categoryId": "cat_dairy_eggs",    "displayOrder": 2  },
  { "storeId": "store_auchan", "categoryId": "cat_meat_fish",     "displayOrder": 3  },
  { "storeId": "store_auchan", "categoryId": "cat_frozen",        "displayOrder": 4  },
  { "storeId": "store_auchan", "categoryId": "cat_pantry",        "displayOrder": 5  },
  { "storeId": "store_auchan", "categoryId": "cat_snacks",        "displayOrder": 6  },
  { "storeId": "store_auchan", "categoryId": "cat_beverages",     "displayOrder": 7  },
  { "storeId": "store_auchan", "categoryId": "cat_alcohol",       "displayOrder": 8  },
  { "storeId": "store_auchan", "categoryId": "cat_household",     "displayOrder": 9  },
  { "storeId": "store_auchan", "categoryId": "cat_personal_care", "displayOrder": 10 },
  { "storeId": "store_auchan", "categoryId": "cat_baby",          "displayOrder": 11 },
  { "storeId": "store_auchan", "categoryId": "cat_pet",           "displayOrder": 12 },

  { "storeId": "store_aldi", "categoryId": "cat_produce",       "displayOrder": 0  },
  { "storeId": "store_aldi", "categoryId": "cat_bakery",        "displayOrder": 1  },
  { "storeId": "store_aldi", "categoryId": "cat_dairy_eggs",    "displayOrder": 2  },
  { "storeId": "store_aldi", "categoryId": "cat_meat_fish",     "displayOrder": 3  },
  { "storeId": "store_aldi", "categoryId": "cat_frozen",        "displayOrder": 4  },
  { "storeId": "store_aldi", "categoryId": "cat_pantry",        "displayOrder": 5  },
  { "storeId": "store_aldi", "categoryId": "cat_snacks",        "displayOrder": 6  },
  { "storeId": "store_aldi", "categoryId": "cat_beverages",     "displayOrder": 7  },
  { "storeId": "store_aldi", "categoryId": "cat_alcohol",       "displayOrder": 8  },
  { "storeId": "store_aldi", "categoryId": "cat_household",     "displayOrder": 9  },
  { "storeId": "store_aldi", "categoryId": "cat_personal_care", "displayOrder": 10 },
  { "storeId": "store_aldi", "categoryId": "cat_baby",          "displayOrder": 11 },
  { "storeId": "store_aldi", "categoryId": "cat_pet",           "displayOrder": 12 },

  { "storeId": "store_leroy_merlin", "categoryId": "cat_hardware",    "displayOrder": 0 },
  { "storeId": "store_leroy_merlin", "categoryId": "cat_garden",      "displayOrder": 1 },
  { "storeId": "store_leroy_merlin", "categoryId": "cat_home_decor",  "displayOrder": 2 },
  { "storeId": "store_leroy_merlin", "categoryId": "cat_electronics", "displayOrder": 3 },

  { "storeId": "store_pharmacia", "categoryId": "cat_pharmacy",      "displayOrder": 0 },
  { "storeId": "store_pharmacia", "categoryId": "cat_personal_care", "displayOrder": 1 },
  { "storeId": "store_pharmacia", "categoryId": "cat_baby",          "displayOrder": 2 },

  { "storeId": "store_wells", "categoryId": "cat_pharmacy",      "displayOrder": 0 },
  { "storeId": "store_wells", "categoryId": "cat_personal_care", "displayOrder": 1 },
  { "storeId": "store_wells", "categoryId": "cat_baby",          "displayOrder": 2 },

  { "storeId": "store_normal", "categoryId": "cat_stationery",  "displayOrder": 0 },
  { "storeId": "store_normal", "categoryId": "cat_home_decor",  "displayOrder": 1 },
  { "storeId": "store_normal", "categoryId": "cat_household",   "displayOrder": 2 },
  { "storeId": "store_normal", "categoryId": "cat_other",       "displayOrder": 3 },

  { "storeId": "store_oriental_market", "categoryId": "cat_pantry",    "displayOrder": 0 },
  { "storeId": "store_oriental_market", "categoryId": "cat_snacks",    "displayOrder": 1 },
  { "storeId": "store_oriental_market", "categoryId": "cat_beverages", "displayOrder": 2 },
  { "storeId": "store_oriental_market", "categoryId": "cat_frozen",    "displayOrder": 3 },
  { "storeId": "store_oriental_market", "categoryId": "cat_produce",   "displayOrder": 4 },

  { "storeId": "store_mega_store",    "categoryId": "cat_other",      "displayOrder": 0 },
  { "storeId": "store_mega_store",    "categoryId": "cat_home_decor", "displayOrder": 1 },
  { "storeId": "store_mega_store",    "categoryId": "cat_stationery", "displayOrder": 2 },

  { "storeId": "store_liberty_store", "categoryId": "cat_other",      "displayOrder": 0 },
  { "storeId": "store_liberty_store", "categoryId": "cat_home_decor", "displayOrder": 1 },
  { "storeId": "store_liberty_store", "categoryId": "cat_stationery", "displayOrder": 2 },

  { "storeId": "store_flavers",       "categoryId": "cat_other",      "displayOrder": 0 },
  { "storeId": "store_flavers",       "categoryId": "cat_home_decor", "displayOrder": 1 },
  { "storeId": "store_flavers",       "categoryId": "cat_stationery", "displayOrder": 2 }
]
"""#

    /// Look up the in-binary mirror by short name (matches the file
    /// stem without `.json`). Returns nil for unknown names so the
    /// seeder can prefer the on-disk lookup when both succeed.
    static func text(forName name: String) -> String? {
        switch name {
        case "stores": return stores
        case "categories": return categories
        case "store_categories": return storeCategories
        default: return nil
        }
    }
}
