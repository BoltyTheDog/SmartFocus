package com.example.msdksample.ar

import dji.sdk.keyvalue.value.common.Attitude
import org.junit.Assert.*
import org.junit.Test

class ProjectionUtilsTest {

    @Test
    fun testGPSToMeters() {
        val homeLat = 37.7749
        val homeLon = -122.4194
        
        // Move approx 111m North (1 degree of lat is ~111km, so 0.001 is ~111m)
        val pos = ProjectionUtils.gpsToMeters(37.7759, -122.4194, homeLat, homeLon)
        
        // pos[0] is East, pos[1] is North
        assertEquals(0.0, pos[0], 1.0)
        assertTrue(pos[1] > 110.0 && pos[1] < 112.0)
    }

    @Test
    fun testProjectToScreen_StraightDown() {
        // Drone at (0,0, 100m AGL)
        // Camera looking straight down (-90 degrees)
        // Target at (0,0, 0) - directly below
        
        val attitude = Attitude(0.0, 0.0, 0.0) // Yaw 0 (North)
        val screenPoint = ProjectionUtils.projectToScreen(
            0.0, 0.0, 0.0, // Target (at Home)
            0.0, 0.0, 100.0, // Drone (at Home, 100m up)
            attitude, -90.0, // Gimbal down
            1920, 1080
        )
        
        assertNotNull(screenPoint)
        // Should be center of screen
        assertEquals(960f, screenPoint!!.x, 1f)
        assertEquals(540f, screenPoint.y, 1f)
    }

    @Test
    fun testProjectToScreen_LookingForward() {
        // Drone at (0,0, 10m AGL)
        // Camera looking forward (0 degrees)
        // Target at (0, 50, 10) - 50m ahead, same altitude
        
        val attitude = Attitude(0.0, 0.0, 0.0)
        val screenPoint = ProjectionUtils.projectToScreen(
            0.0, 50.0, 10.0, 
            0.0, 0.0, 10.0,
            attitude, 0.0,
            1920, 1080
        )
        
        assertNotNull(screenPoint)
        assertEquals(960f, screenPoint!!.x, 1f)
        assertEquals(540f, screenPoint.y, 1f)
    }
}
