package com.thanhtaxixanh.cardriverlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowGateTest {
    @Test fun afterFiveSecondsFromLastTouch() {
        var now=100_000L
        val gate=CameraFollowGate { now }
        assertTrue(gate.canFollow(5))
        gate.interactionStart()
        assertFalse(gate.canFollow(5))
        now+=3_000
        gate.interactionUpdate()
        now+=4_999
        assertFalse(gate.canFollow(5))
        gate.interactionEnd()
        now+=4_999
        assertFalse(gate.canFollow(5))
        now+=1
        assertTrue(gate.canFollow(5))
    }
    @Test fun tenSecondPresetRespectsNewGesturesAndManualReset() {
        var now=1_000L
        val gate=CameraFollowGate { now }
        gate.interactionEnd()
        now+=6_000
        gate.interactionEnd()
        now+=9_999
        assertFalse(gate.canFollow(10))
        now+=1
        assertTrue(gate.canFollow(10))
        gate.interactionEnd()
        assertFalse(gate.canFollow(10))
        gate.recenterNow()
        assertTrue(gate.canFollow(10))
    }
    @Test fun activeGestureNeverFollowsEvenAfterTimeout() {
        var now=0L
        val gate=CameraFollowGate { now }
        gate.interactionStart()
        now+=100_000L
        assertFalse(gate.canFollow(5))
        gate.interactionEnd()
        now+=5_000L
        assertTrue(gate.canFollow(5))
    }
}
