package dev.quietube.ui

import org.junit.Assert.*
import org.junit.Test

class SmoothRefreshRateTest {
    @Test fun `Pixel modes request 120 Hz`() {
        assertEquals(120.00001f,smoothRefreshRate(listOf(60f,120.00001f)),.001f)
    }
    @Test fun `fractional 120 Hz mode is preferred over a faster mode`() {
        assertEquals(119.88f,smoothRefreshRate(listOf(59.94f,119.88f,144f)),.001f)
    }
    @Test fun `90 and 60 Hz displays use their available maximum`() {
        assertEquals(90f,smoothRefreshRate(listOf(60f,90f)),0f)
        assertEquals(60f,smoothRefreshRate(listOf(60f)),0f)
    }
    @Test fun `higher rate display uses nearest supported mode above 120`() {
        assertEquals(144f,smoothRefreshRate(listOf(60f,144f,165f)),0f)
    }
    @Test fun `invalid or missing modes leave the platform in control`() {
        assertEquals(0f,smoothRefreshRate(emptyList()),0f)
        assertEquals(0f,smoothRefreshRate(listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-60f)),0f)
    }
}
