package com.purrfectbytes.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitTransformationTest {

    @Test
    fun `a photo as wide as the view is only scaled`() {
        val fit = fitTransformation(viewWidth = 1000, viewHeight = 750, imageWidth = 4000, imageHeight = 3000)!!

        assertEquals(0.25f, fit.scale, 1e-6f)
        assertEquals(0f, fit.offsetX, 1e-3f)
        assertEquals(0f, fit.offsetY, 1e-3f)
    }

    @Test
    fun `a photo taken upright is placed by its upright size`() {
        // The camera stores 4000 x 3000 with a note to turn it; upright it is 3000 x 4000.
        // Measured by the stored size, the boxes were drawn at 0.25 with a gap of 291 above.
        val fit = fitTransformation(viewWidth = 1000, viewHeight = 1333, imageWidth = 3000, imageHeight = 4000)!!

        assertEquals(1000f / 3000f, fit.scale, 1e-4f)
        assertEquals(0f, fit.offsetX, 1f)
        assertEquals(0f, fit.offsetY, 1f)
        assertEquals(999, fit.displayWidth)
        assertEquals(1333, fit.displayHeight)
    }

    @Test
    fun `a narrow photo is centered sideways`() {
        val fit = fitTransformation(viewWidth = 1000, viewHeight = 500, imageWidth = 500, imageHeight = 1000)!!

        assertEquals(0.5f, fit.scale, 1e-6f)
        assertEquals(375f, fit.offsetX, 1e-3f)
        assertEquals(0f, fit.offsetY, 1e-3f)
    }

    @Test
    fun `a flat photo is centered up and down`() {
        val fit = fitTransformation(viewWidth = 1000, viewHeight = 1000, imageWidth = 2000, imageHeight = 500)!!

        assertEquals(0.5f, fit.scale, 1e-6f)
        assertEquals(0f, fit.offsetX, 1e-3f)
        assertEquals(375f, fit.offsetY, 1e-3f)
    }

    @Test
    fun `nothing is placed before the sizes are known`() {
        assertNull(fitTransformation(0, 0, 4000, 3000))
        assertNull(fitTransformation(1000, 750, 0, 0))
    }
}
