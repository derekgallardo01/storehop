package com.storehop.app.ui.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.storehop.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Validates the same `getIdentifier` + `getString` lookup that `Category.localizedLabel()`
 * performs, plus the user-added fallback. We test the underlying lookup directly instead of
 * the @Composable to avoid pulling in Compose UI testing for a one-line resource read.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryLocalizationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `seeded category resolves to its English label by default`() {
        val resId = ctx.resources.getIdentifier("cat_produce", "string", ctx.packageName)
        assertThat(resId).isNotEqualTo(0)
        assertThat(ctx.getString(resId)).isEqualTo("Produce")
    }

    @Test fun `seeded category resolves to its European Portuguese label under pt-rPT`() {
        RuntimeEnvironment.setQualifiers("pt-rPT")
        val ptCtx: Context = ApplicationProvider.getApplicationContext()
        val resId = ptCtx.resources.getIdentifier("cat_produce", "string", ptCtx.packageName)
        assertThat(ptCtx.getString(resId)).isEqualTo("Frutas e Legumes")
    }

    @Test fun `several pt-rPT category translations are present and non-empty`() {
        RuntimeEnvironment.setQualifiers("pt-rPT")
        val ptCtx: Context = ApplicationProvider.getApplicationContext()
        listOf(
            "cat_dairy_eggs" to "Lacticínios e Ovos",
            "cat_household" to "Casa e Limpeza",
            "cat_pharmacy" to "Farmácia",
            "cat_pet" to "Animais",
        ).forEach { (key, expected) ->
            val resId = ptCtx.resources.getIdentifier(key, "string", ptCtx.packageName)
            assertThat(ptCtx.getString(resId)).isEqualTo(expected)
        }
    }

    @Test fun `user-added category (nameKey=null) falls back to the typed name`() {
        // Mirrors the localizedLabel() short-circuit: `val key = nameKey ?: return name`.
        // R.string.app_name is irrelevant; we just confirm the absence-of-key code path.
        val typed = "BBQ"
        val resolved: String = run {
            val key: String? = null
            if (key == null) return@run typed
            val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
            if (resId != 0) ctx.getString(resId) else typed
        }
        assertThat(resolved).isEqualTo("BBQ")
    }

    @Test fun `unknown nameKey falls back to the row's name field`() {
        // Mirrors the second short-circuit: `if (resId != 0) ctx.getString(resId) else name`.
        val typed = "Custom"
        val resolved: String = run {
            val key = "cat_does_not_exist"
            val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
            if (resId != 0) ctx.getString(resId) else typed
        }
        assertThat(resolved).isEqualTo("Custom")
    }
}
