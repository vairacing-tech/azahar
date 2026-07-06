package org.citra.citra_emu.fragments

internal object EmulationSurfacePolicy {
    fun canRunWithSurface(surfacePresent: Boolean, surfaceValid: Boolean): Boolean =
        surfacePresent && surfaceValid
}
