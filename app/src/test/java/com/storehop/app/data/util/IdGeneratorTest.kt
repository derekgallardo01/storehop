package com.storehop.app.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class IdGeneratorTest {

    private val gen = UuidIdGenerator()

    @Test fun `produces RFC-4122 UUID strings`() {
        val id = gen.newId()
        assertThat(id).matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        // Also confirm it round-trips through UUID.fromString without throwing.
        UUID.fromString(id)
    }

    @Test fun `successive IDs are distinct`() {
        val ids = (1..1000).map { gen.newId() }.toSet()
        assertThat(ids).hasSize(1000)
    }
}
