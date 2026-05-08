# CSV templates for Storehop import

Two ready-to-edit templates. Open in Google Sheets, Excel, or Numbers,
replace the example rows with your data, save as CSV, then import via
**Settings → Data → Import items** (or **Import categories**) on your
phone.

## Items — `storehop-items-template.csv`

| Column | Required | Notes |
|---|---|---|
| `name` | yes | The item, e.g. `Shredded Mozzarella`. Non-empty. |
| `category` | no | Plain text. If you use a name that already exists, the import links to your existing category. If it's new, the import creates it. |
| `stores` | no | Comma-separated list of store names. **Quote the cell** when you list more than one store, e.g. `"Aldi,Lidl,Pingo Doce"`. New store names get auto-created. |
| `brand` | no | Free text. |
| `notes` | no | Free text. |
| `quantity` | no | Free text — `2 L`, `500 g`, `1 dozen`, whatever. |
| `isStaple` | no | `true` or `false`. Blank = `false`. Staples stay visible after you mark them purchased. |
| `isPriority` | no | `true` or `false`. Blank = `false`. Highlights the item across every store's list. |

## Categories — `storehop-categories-template.csv`

| Column | Required | Notes |
|---|---|---|
| `name` | yes | The category name, e.g. `Greek Specialties`. |
| `icon` | no | A single emoji (or blank). |

## Tips

- **Storehop never overwrites your existing items.** If you re-import a CSV that contains a name you already have, that row is skipped and your existing item stays as it is. The snackbar after import tells you how many duplicates were skipped.
- **You can undo an import.** A 5-second snackbar appears after every import with an Undo action — that soft-deletes only the rows that were just inserted.
- **Quote any cell that contains a comma** (e.g. an item name like `Olive oil, extra virgin` or a multi-store list `"Aldi,Lidl"`). A spreadsheet does this automatically when you save as CSV.
- **You don't need to import categories first.** If your items CSV references categories that don't exist yet, the import creates them on the fly.
- **Same for stores.** Referencing a new store name in `stores` auto-creates that store.
