package org.citra.citra_emu.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulationSurfacePolicyTest {
    @Test
    fun allowsNativeRunOnlyWhenSurfaceIsPresentAndValid() {
        assertFalse(EmulationSurfacePolicy.canRunWithSurface(surfacePresent = false, surfaceValid = false))
        assertFalse(EmulationSurfacePolicy.canRunWithSurface(surfacePresent = true, surfaceValid = false))
        assertTrue(EmulationSurfacePolicy.canRunWithSurface(surfacePresent = true, surfaceValid = true))
    }
}
