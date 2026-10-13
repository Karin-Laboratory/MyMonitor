package com.karinlab.mymonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGeometryTest {
    @Test fun landscapePhoneKeepsWideUvcCentered() {
        assertEquals(PreviewGeometry.Size(1778, 1000),
            PreviewGeometry.fit(1920, 1080, 2400, 1000))
    }
    @Test fun portraitPhoneLetterboxesWideUvc() {
        assertEquals(PreviewGeometry.Size(1080, 608),
            PreviewGeometry.fit(1920, 1080, 1080, 2200))
    }
    @Test fun smallLandscapeViewportFitsHeight() {
        assertEquals(PreviewGeometry.Size(1422, 800),
            PreviewGeometry.fit(1920, 1080, 1600, 800))
    }
    @Test fun fourByThreeUvcMaintainsAspectRatio() {
        assertEquals(PreviewGeometry.Size(1333, 1000),
            PreviewGeometry.fit(640, 480, 2400, 1000))
    }
    @Test fun screenshotLandscapeViewportShouldFillHeightAndCenter() {
        // Regression for Screenshot_20261014: 1080x482 phone screenshot,
        // ~54px toolbar leaves 1080x428 for video. v017 rendered a tiny,
        // offset ~480x270 rectangle rather than this large centered image.
        assertEquals(PreviewGeometry.Rect(159, 0, 761, 428),
            PreviewGeometry.centered(1920, 1080, 1080, 428))
    }
    @Test fun landscapeToPortraitThenBackHasNoAccumulatedScaling() {
        val original = PreviewGeometry.centered(1920,1080,1080,428)
        assertEquals(PreviewGeometry.Rect(0, 593, 1080, 608),
            PreviewGeometry.centered(1920,1080,1080,1794))
        assertEquals(original, PreviewGeometry.centered(1920,1080,1080,428))
    }
    @Test fun noStretchForExactRatio() {
        assertEquals(PreviewGeometry.Size(1920, 1080),
            PreviewGeometry.fit(1920, 1080, 1920, 1080))
    }
}
