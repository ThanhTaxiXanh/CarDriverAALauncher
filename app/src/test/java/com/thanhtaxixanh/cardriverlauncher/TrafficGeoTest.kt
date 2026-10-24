package com.thanhtaxixanh.cardriverlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficGeoTest {
    @Test fun parseExplicitLimitAndRejectImplicitTags() {
        assertEquals(60, TrafficGeo.speed("60"))
        assertEquals(60, TrafficGeo.speed("60 km/h"))
        assertEquals(56, TrafficGeo.speed("35 mph"))
        assertNull(TrafficGeo.speed("urban"))
        assertNull(TrafficGeo.speed("rural"))
        assertNull(TrafficGeo.speed("signals"))
        assertNull(TrafficGeo.speed("none"))
        assertNull(TrafficGeo.speed("invalid"))
    }

    @Test fun rejectUnsupportedSpeedValues() {
        assertNull(TrafficGeo.speed("-1"))
        assertNull(TrafficGeo.speed("0"))
        assertNull(TrafficGeo.speed("250"))
        assertEquals(100, TrafficGeo.speed("100"))
    }

    @Test fun onlyUniformLaneLimitsCanBeShownWithoutKnowingLane() {
        assertEquals(60, TrafficGeo.uniformLaneLimit("60|60|60"))
        assertNull(TrafficGeo.uniformLaneLimit("40|60|80"))
        assertNull(TrafficGeo.uniformLaneLimit("60|"))
        assertNull(TrafficGeo.uniformLaneLimit(""))
    }

    @Test fun compassBearingAndDifference() {
        assertEquals(90.0, TrafficGeo.bearing(21.0,106.0,21.0,106.001),2.0)
        assertEquals(0.0, TrafficGeo.angle(359.0,1.0),3.0)
        assertEquals(180.0, TrafficGeo.angle(0.0,180.0),0.1)
    }

    @Test fun mapMatcherGeometryIsMeterBased() {
        val here = TrafficGeo.pointSegmentDistance(
            21.0,106.0005,21.0,106.0,21.0,106.001)
        assertTrue(here < 0.5)
        val nearby = TrafficGeo.pointSegmentDistance(
            21.0005,106.0005,21.0,106.0,21.0,106.001)
        assertTrue(nearby > 40.0)
        assertTrue(nearby < 70.0)
    }
}
