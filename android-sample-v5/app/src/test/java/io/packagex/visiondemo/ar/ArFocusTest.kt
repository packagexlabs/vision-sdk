package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class ArFocusTest {
    // A 4000 x 3000 active array: regions of 500 x 375
    @Test fun theCentreIsTheArraysCentreWhateverTheRotation() {
        for (o in listOf(0, 90, 180, 270)) assertEquals(Region(1750, 1312, 500, 375), meteringRegion(0.5f, 0.5f, o, 0, 4000, 3000))
    }

    @Test fun aPortraitViewOnANinetyDegreeSensorTurnsTheTap() {
        // View top centre: the sensor's left edge, half way down (view y runs along the sensor's +x, view x along its -y)
        assertEquals(Region(0, 1312, 500, 375), meteringRegion(0.5f, 0f, 90, 0, 4000, 3000))
        // View right, a quarter down: sensor x a quarter in, sensor y at the top
        assertEquals(Region(750, 0, 500, 375), meteringRegion(1f, 0.25f, 90, 0, 4000, 3000))
        // View left, three quarters down
        assertEquals(Region(2750, 2625, 500, 375), meteringRegion(0f, 0.75f, 90, 0, 4000, 3000))
    }

    @Test fun otherRotationsAndTheDisplaysOwn() {
        assertEquals(Region(250, 2625, 500, 375), meteringRegion(0.125f, 1f, 0, 0, 4000, 3000))
        assertEquals(Region(3250, 0, 500, 375), meteringRegion(0.125f, 1f, 180, 0, 4000, 3000))
        assertEquals(Region(0, 187, 500, 375), meteringRegion(0.125f, 1f, 270, 0, 4000, 3000))
        // Display turned 90: sensor 90 - display 90 = 0
        assertEquals(meteringRegion(0.3f, 0.6f, 0, 0, 4000, 3000), meteringRegion(0.3f, 0.6f, 90, 90, 4000, 3000))
    }
}
