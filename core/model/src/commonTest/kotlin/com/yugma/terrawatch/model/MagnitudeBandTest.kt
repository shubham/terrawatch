package com.yugma.terrawatch.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MagnitudeBandTest {
    @Test fun `null magnitude is unknown`() = assertEquals(MagnitudeBand.UNKNOWN, magnitudeBand(null))
    @Test fun `below three is low`() = assertEquals(MagnitudeBand.LOW, magnitudeBand(2.99))
    @Test fun `three is moderate`() = assertEquals(MagnitudeBand.MODERATE, magnitudeBand(3.0))
    @Test fun `just under four point five is moderate`() = assertEquals(MagnitudeBand.MODERATE, magnitudeBand(4.49))
    @Test fun `four point five is strong`() = assertEquals(MagnitudeBand.STRONG, magnitudeBand(4.5))
    @Test fun `six is major`() = assertEquals(MagnitudeBand.MAJOR, magnitudeBand(6.0))
    @Test fun `negative magnitude is low`() = assertEquals(MagnitudeBand.LOW, magnitudeBand(-0.4))
}
