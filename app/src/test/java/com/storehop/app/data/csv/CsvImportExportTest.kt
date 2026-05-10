package com.storehop.app.data.csv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-function tests for the CSV parser + serializer. No DB, no DI.
 */
class CsvImportExportTest {

    @Test fun `empty input returns empty result with no errors`() {
        val r = parseItemCsv("")
        assertThat(r.rows).isEmpty()
        assertThat(r.errors).isEmpty()
    }

    @Test fun `header without name column reports a single header error`() {
        val r = parseItemCsv("foo,bar\nx,y\n")
        assertThat(r.rows).isEmpty()
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors.first()).contains("name")
    }

    @Test fun `parse handles all documented columns and header is case insensitive`() {
        val csv = """
            NAME,Category,Stores,Brand,Notes,Quantity,IsStaple,IsPriority
            Milk,Dairy & Eggs,"Aldi,Lidl",Mimosa,Skim,1 L,true,false
        """.trimIndent()
        val r = parseItemCsv(csv)
        assertThat(r.errors).isEmpty()
        val row = r.rows.single()
        assertThat(row.name).isEqualTo("Milk")
        assertThat(row.category).isEqualTo("Dairy & Eggs")
        assertThat(row.storeNames).containsExactly("Aldi", "Lidl").inOrder()
        assertThat(row.brand).isEqualTo("Mimosa")
        assertThat(row.notes).isEqualTo("Skim")
        assertThat(row.quantity).isEqualTo("1 L")
        assertThat(row.isStaple).isTrue()
        assertThat(row.isPriority).isFalse()
    }

    @Test fun `quoted field with embedded comma is parsed as a single field`() {
        val csv = "name,notes\nMilk,\"low-fat, organic\"\n"
        val r = parseItemCsv(csv)
        assertThat(r.rows.single().notes).isEqualTo("low-fat, organic")
    }

    @Test fun `escaped double quote is unescaped`() {
        val csv = "name,brand\nMilk,\"Mike\"\"s Dairy\"\n"
        val r = parseItemCsv(csv)
        assertThat(r.rows.single().brand).isEqualTo("Mike\"s Dairy")
    }

    @Test fun `crlf and lf line endings are both supported`() {
        val crlf = parseItemCsv("name\r\nMilk\r\nBread\r\n").rows.map { it.name }
        val lf = parseItemCsv("name\nMilk\nBread\n").rows.map { it.name }
        assertThat(crlf).containsExactly("Milk", "Bread").inOrder()
        assertThat(lf).containsExactly("Milk", "Bread").inOrder()
    }

    @Test fun `row with empty name is reported as an error and other rows still parse`() {
        val csv = "name,brand\nMilk,Mimosa\n,Brandless\nBread,Bimbo\n"
        val r = parseItemCsv(csv)
        // Two valid rows, one error.
        assertThat(r.rows.map { it.name }).containsExactly("Milk", "Bread").inOrder()
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors.single()).contains("Line 3")
    }

    @Test fun `unknown columns are ignored for forward compatibility`() {
        val csv = "name,unknown,brand\nMilk,whatever,Mimosa\n"
        val r = parseItemCsv(csv)
        assertThat(r.errors).isEmpty()
        assertThat(r.rows.single().name).isEqualTo("Milk")
        assertThat(r.rows.single().brand).isEqualTo("Mimosa")
    }

    @Test fun `entirely-blank rows are skipped silently`() {
        val csv = "name\nMilk\n\n\nBread\n"
        val r = parseItemCsv(csv)
        assertThat(r.errors).isEmpty()
        assertThat(r.rows.map { it.name }).containsExactly("Milk", "Bread").inOrder()
    }

    @Test fun `parse stores splits on comma and trims whitespace`() {
        val csv = "name,stores\nMilk,\" Aldi , Lidl \"\n"
        val r = parseItemCsv(csv)
        assertThat(r.rows.single().storeNames).containsExactly("Aldi", "Lidl").inOrder()
    }

    @Test fun `boolean fields accept TRUE FALSE case-insensitively, blank is false`() {
        val csv = "name,isStaple,isPriority\nA,TRUE,false\nB,True,\nC,nope,FALSE\n"
        val r = parseItemCsv(csv)
        assertThat(r.rows[0].isStaple).isTrue()
        assertThat(r.rows[0].isPriority).isFalse()
        assertThat(r.rows[1].isStaple).isTrue()
        assertThat(r.rows[1].isPriority).isFalse()
        // "nope" is not "true", so isStaple is false
        assertThat(r.rows[2].isStaple).isFalse()
    }

    @Test fun `parseCategoryCsv requires name column`() {
        val r = parseCategoryCsv("foo\nBakery\n")
        assertThat(r.rows).isEmpty()
        assertThat(r.errors).hasSize(1)
    }

    @Test fun `parseCategoryCsv handles name and optional icon`() {
        val csv = """
            name,icon
            Bakery,
            Greek,🇬🇷
        """.trimIndent()
        val r = parseCategoryCsv(csv)
        assertThat(r.errors).isEmpty()
        assertThat(r.rows.map { it.name to it.icon })
            .containsExactly("Bakery" to null, "Greek" to "🇬🇷").inOrder()
    }

    @Test fun `toItemsCsv emits header plus one row per entry`() {
        val csv = listOf(
            ItemCsvRow(name = "Milk", category = "Dairy & Eggs",
                storeNames = listOf("Aldi", "Lidl"), brand = "Mimosa",
                isStaple = true),
            ItemCsvRow(name = "Apples", category = "Produce"),
        ).toItemsCsv()
        val lines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        assertThat(lines[0]).isEqualTo("name,category,stores,brand,notes,quantity,isStaple,isPriority")
        assertThat(lines).hasSize(3)
    }

    @Test fun `toItemsCsv quotes fields containing commas and roundtrips`() {
        val original = listOf(
            ItemCsvRow(name = "Sauce, special", category = null,
                storeNames = listOf("Aldi", "Lidl"),
                notes = "Has a, comma"),
            ItemCsvRow(name = "Plain"),
        )
        val csv = original.toItemsCsv()
        val parsed = parseItemCsv(csv).rows
        assertThat(parsed).hasSize(2)
        assertThat(parsed[0].name).isEqualTo("Sauce, special")
        assertThat(parsed[0].notes).isEqualTo("Has a, comma")
        assertThat(parsed[0].storeNames).containsExactly("Aldi", "Lidl").inOrder()
        assertThat(parsed[1].name).isEqualTo("Plain")
    }

    @Test fun `toItemsCsv escapes embedded quotes and roundtrips`() {
        val original = listOf(ItemCsvRow(name = "Mike\"s Pizza"))
        val csv = original.toItemsCsv()
        val parsed = parseItemCsv(csv).rows
        assertThat(parsed.single().name).isEqualTo("Mike\"s Pizza")
    }

    @Test fun `toCategoriesCsv roundtrips name and icon`() {
        val original = listOf(
            CategoryCsvRow(name = "Bakery", icon = null),
            CategoryCsvRow(name = "Greek", icon = "🇬🇷"),
        )
        val parsed = parseCategoryCsv(original.toCategoriesCsv()).rows
        assertThat(parsed).isEqualTo(original)
    }

    @Test fun `parseItemCsv reports an error when the name column is empty on a row`() {
        // Header includes name; data row has an empty name field.
        val csv = "name,brand\n,Sara Lee\nMilk,\n"
        val r = parseItemCsv(csv)
        // Two rows seen: row 1 has empty name -> error; row 2 is valid.
        assertThat(r.rows.map { it.name }).containsExactly("Milk")
        assertThat(r.errors).isNotEmpty()
        assertThat(r.errors[0]).contains("name")
    }

    @Test fun `parseCategoryCsv reports an error when the name column is empty on a row`() {
        // Same shape as the items test, but on the category-parser branch.
        val csv = "name,icon\n,🥖\nBakery,\n"
        val r = parseCategoryCsv(csv)
        assertThat(r.rows.map { it.name }).containsExactly("Bakery")
        assertThat(r.errors).isNotEmpty()
        assertThat(r.errors[0]).contains("name")
    }
}
