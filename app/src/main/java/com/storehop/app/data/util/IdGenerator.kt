package com.storehop.app.data.util

import java.util.UUID
import javax.inject.Inject

interface IdGenerator {
    fun newId(): String
}

class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
