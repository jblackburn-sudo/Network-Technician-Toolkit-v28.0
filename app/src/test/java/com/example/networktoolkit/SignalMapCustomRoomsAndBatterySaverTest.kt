package com.example.networktoolkit

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.signalmap.DynamicFloorPlanLayoutEngine
import com.example.networktoolkit.signalmap.PropertySurveyConfig
import com.example.networktoolkit.signalmap.PropertyType
import com.example.networktoolkit.signalmap.RoomType
import com.example.networktoolkit.signalmap.RoomZone
import com.example.networktoolkit.signalmap.SignalMapEngine
import com.example.networktoolkit.signalmap.SignalMapScreen
import com.example.networktoolkit.signalmap.SignalMapSurveyRepository
import com.example.networktoolkit.signalmap.SignalMapViewModel
import com.example.networktoolkit.signalmap.SignalSample
import com.example.ui.theme.NetworkToolkitTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalMapCustomRoomsAndBatterySaverTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SignalMapSurveyRepository(app).clearAllStoredSurveys()
        AppBatterySaverManager.resetForTesting()
        app.getSharedPreferences(AppBatterySaverManager.PREFS_NAME, 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppBatterySaverManager.resetForTesting()
        SignalMapSurveyRepository(app).clearAllStoredSurveys()
    }

    private inline fun withSignalMapViewModel(block: (SignalMapViewModel) -> Unit) {
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SignalMapViewModel(app) as T
                    }
                }
            )[SignalMapViewModel::class.java]
            block(vm)
        } finally {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun testCustomPropertyConfigurationSupportsAnyTypeCountAndFloors() {
        withSignalMapViewModel { vm ->
            vm.startNewSurvey(
                propertyType = PropertyType.OFFICE,
                propertyName = "Acme Suite 402",
                roomCount = 12,
                floorCount = 3,
                useStarterRooms = true
            )

            assertEquals(PropertyType.OFFICE, vm.propertyType.value)
            assertEquals("Acme Suite 402", vm.propertyConfig.value.propertyName)
            assertEquals("Acme Suite 402 (Office)", vm.propertyConfig.value.displayPropertyTitle)
            assertEquals(3, vm.propertyConfig.value.floorCount)
            assertEquals(12, vm.rooms.value.size)
            assertEquals(setOf(1, 2, 3), vm.rooms.value.map { it.floorNumber }.toSet())
        }
    }

    @Test
    fun testMultipleDuplicateRoomTypesAndCustomRoomTypesAllowed() {
        withSignalMapViewModel { vm ->
            vm.startNewSurvey(
                propertyType = PropertyType.HOUSE,
                propertyName = "Oakwood Residence",
                roomCount = 1,
                floorCount = 2,
                useStarterRooms = false
            )

            val bed1 = vm.addRoom("Bedroom 1", RoomType.BEDROOM, floorNumber = 2)
            val bed2 = vm.addRoom("Bedroom 2", RoomType.BEDROOM, floorNumber = 2)
            val hall1 = vm.addRoom("Main Hallway", RoomType.HALLWAY, floorNumber = 1)
            val hall2 = vm.addRoom("Upstairs Hallway", RoomType.HALLWAY, floorNumber = 2)
            val serverCloset = vm.addRoom(
                name = "IDF Rack Closet",
                roomType = RoomType.OTHER,
                customTypeLabel = "Server Closet",
                floorNumber = 1
            )

            val allRooms = vm.rooms.value
            assertEquals(6, allRooms.size)
            assertEquals(2, allRooms.count { it.roomType == RoomType.BEDROOM })
            assertEquals(2, allRooms.count { it.roomType == RoomType.HALLWAY })
            assertNotEquals(bed1.id, bed2.id)
            assertNotEquals(hall1.id, hall2.id)
            assertEquals("Server Closet", serverCloset.effectiveRoomType)
        }
    }

    @Test
    fun testMeasurementsRemainAssociatedAcrossRenameRetypeReorderAndAdd() {
        withSignalMapViewModel { vm ->
            vm.startNewSurvey(
                propertyType = PropertyType.OTHER,
                propertyName = "Field Lab",
                roomCount = 1,
                floorCount = 2,
                useStarterRooms = false
            )

            val initialRoom = vm.rooms.value.first()
            val roomTwo = vm.addRoom("Back Storage", RoomType.OTHER, floorNumber = 1)

            // Confirm and record measurements in roomTwo
            vm.confirmCurrentRoom(roomTwo.id)
            assertEquals(roomTwo.id, vm.confirmedRoomId.value)
            vm.recordSampleInSelectedRoom()
            vm.recordSampleInSelectedRoom()

            // Also record one in initialRoom
            vm.confirmCurrentRoom(initialRoom.id)
            vm.recordSampleInSelectedRoom()
            assertEquals(3, vm.samples.value.size)

            // Rename, re-type, move floor, and reorder roomTwo without losing or overwriting its samples
            vm.updateRoom(
                roomId = roomTwo.id,
                name = "Upstairs Home Office",
                roomType = RoomType.OFFICE,
                customTypeLabel = "Engineering Office",
                floorNumber = 2
            )
            vm.moveRoomOrder(roomTwo.id, moveUp = true)
            vm.addRoom("Garage Bay", RoomType.GARAGE, floorNumber = 1)

            val updatedRoomTwo = vm.rooms.value.first { it.id == roomTwo.id }
            val roomTwoSamples = vm.samples.value.filter { it.roomId == roomTwo.id }
            val initialRoomSamples = vm.samples.value.filter { it.roomId == initialRoom.id }

            assertEquals(2, roomTwoSamples.size)
            assertEquals(1, initialRoomSamples.size)
            assertTrue(roomTwoSamples.all { it.roomName == "Upstairs Home Office" })
            assertTrue(roomTwoSamples.all { it.floorNumber == 2 })
            assertTrue(roomTwoSamples.all { it.roomTypeLabel == "Engineering Office" })
            // Reflowed coordinates stay inside the updated room box
            assertTrue(roomTwoSamples.all { updatedRoomTwo.containsPoint(it.relX, it.relY) })

            // Verify reports carry custom property & renamed room accurately
            val textReport = vm.generateTextReport()
            assertTrue(textReport.contains("Field Lab"))
            assertTrue(textReport.contains("Upstairs Home Office"))
            assertTrue(textReport.contains("Engineering Office"))
        }
    }

    @Test
    fun testSafeRoomRemovalPreservesUnrelatedRoomsAndArchivesOrDeletesCorrectly() {
        withSignalMapViewModel { vm ->
            vm.startNewSurvey(
                propertyType = PropertyType.APARTMENT,
                propertyName = "Unit 9B",
                roomCount = 1,
                floorCount = 1,
                useStarterRooms = false
            )
            val mainRoom = vm.rooms.value.first()
            val guestRoom = vm.addRoom("Guest Nook", RoomType.BEDROOM)
            val hallway = vm.addRoom("Hallway", RoomType.HALLWAY)

            vm.selectRoom(mainRoom)
            vm.recordSampleInSelectedRoom()
            vm.selectRoom(guestRoom)
            vm.recordSampleInSelectedRoom()
            vm.selectRoom(hallway)
            vm.recordSampleInSelectedRoom()

            // Remove guestRoom keeping archived samples: unrelated rooms stay untouched
            vm.removeRoom(guestRoom.id, deleteAssociatedSamples = false)
            assertEquals(2, vm.rooms.value.size)
            assertEquals(3, vm.samples.value.size)
            assertEquals(1, vm.samples.value.count { it.roomId == mainRoom.id })
            assertEquals(1, vm.samples.value.count { it.roomId == hallway.id })
            assertTrue(vm.samples.value.any { it.roomName.contains("Guest Nook") && it.roomName.contains("Archived") })

            // Remove hallway deleting its own samples: mainRoom still keeps its sample
            vm.removeRoom(hallway.id, deleteAssociatedSamples = true)
            assertEquals(1, vm.rooms.value.size)
            assertEquals(1, vm.samples.value.count { it.roomId == mainRoom.id })
            assertEquals(0, vm.samples.value.count { it.roomId == hallway.id })
        }
    }

    @Test
    fun testDynamicFloorPlanLayoutEngineNonOverlappingBoxesAndBounds() {
        val rooms = (1..14).map { idx ->
            RoomZone(
                name = "Zone $idx",
                roomType = if (idx % 2 == 0) RoomType.BEDROOM else RoomType.OFFICE,
                floorNumber = if (idx <= 7) 1 else 2,
                orderIndex = idx - 1,
                isPrimaryApLocation = idx == 1
            )
        }
        val laidOut = DynamicFloorPlanLayoutEngine.layoutRooms(rooms)
        assertEquals(14, laidOut.size)

        // All coordinates remain inside normalized blueprint canvas [0..1]
        laidOut.forEach { room ->
            assertTrue("relX out of bounds: ${room.relX}", room.relX >= 0f && room.relX < 1f)
            assertTrue("relY out of bounds: ${room.relY}", room.relY >= 0f && room.relY < 1f)
            assertTrue("right out of bounds", room.relX + room.relWidth <= 1.001f)
            assertTrue("bottom out of bounds", room.relY + room.relHeight <= 1.001f)
            assertTrue("positive width", room.relWidth > 0.05f)
            assertTrue("positive height", room.relHeight > 0.05f)
        }

        // Verify pairs of rooms do not overlap
        for (i in laidOut.indices) {
            for (j in i + 1 until laidOut.size) {
                val a = laidOut[i]
                val b = laidOut[j]
                val horizOverlap = a.relX < (b.relX + b.relWidth - 0.001f) && (a.relX + a.relWidth - 0.001f) > b.relX
                val vertOverlap = a.relY < (b.relY + b.relHeight - 0.001f) && (a.relY + a.relHeight - 0.001f) > b.relY
                assertFalse("Rooms ${a.name} and ${b.name} overlap!", horizOverlap && vertOverlap)
            }
        }
    }

    @Test
    fun testSurveyPersistenceAcrossNavigationAndLegacySurveyMigration() {
        val repo = SignalMapSurveyRepository(app)

        // 1. Test ViewModel auto-save + reload ("Continue Survey")
        var basementId = ""
        var firstSurveyId = ""
        withSignalMapViewModel { vm1 ->
            vm1.startNewSurvey(PropertyType.HOUSE, "123 Maple St", roomCount = 4, floorCount = 2)
            val basement = vm1.addRoom("Basement Rack", RoomType.BASEMENT, customTypeLabel = "Network Core", floorNumber = 1)
            basementId = basement.id
            vm1.selectRoom(basement)
            vm1.recordSampleInSelectedRoom()
            firstSurveyId = vm1.propertyConfig.value.surveyId
            vm1.persistActiveSurvey()
        }

        // Reload via fresh ViewModel instance (simulates leaving Signal Mapper and returning)
        withSignalMapViewModel { vm2 ->
            assertEquals(firstSurveyId, vm2.propertyConfig.value.surveyId)
            assertEquals("123 Maple St", vm2.propertyConfig.value.propertyName)
            assertEquals(5, vm2.rooms.value.size)
            assertEquals(1, vm2.samples.value.size)
            assertEquals(basementId, vm2.samples.value.first().roomId)
        }

        // 2. Test legacy v1 payload migration without data loss
        val legacyJson = """
            {
              "propertyType": "APARTMENT",
              "rooms": [
                {"id": "legacy_r1", "name": "Living Room", "relX": 0.1, "relY": 0.1, "relWidth": 0.4, "relHeight": 0.4, "isPrimaryApLocation": true},
                {"id": "legacy_r2", "name": "Bedroom", "relX": 0.55, "relY": 0.1, "relWidth": 0.4, "relHeight": 0.4, "isPrimaryApLocation": false}
              ],
              "samples": [
                {"id": "s_old", "roomName": "Bedroom", "relX": 0.7, "relY": 0.3, "rssiDbm": -71, "linkSpeedMbps": 240}
              ]
            }
        """.trimIndent()

        val migrated = repo.parseOrMigrate(legacyJson)
        assertTrue(migrated.migratedFromLegacy)
        assertEquals(SignalMapSurveyRepository.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(2, migrated.rooms.size)
        assertEquals(RoomType.LIVING_ROOM, migrated.rooms[0].roomType)
        assertEquals(RoomType.BEDROOM, migrated.rooms[1].roomType)
        assertEquals(1, migrated.samples.size)
        assertEquals("legacy_r2", migrated.samples[0].roomId)
        assertEquals(-71, migrated.samples[0].rssiDbm)
    }

    @Test
    fun testGlobalBatterySaverManagerModesAndLowBatteryThreshold() {
        val manager = AppBatterySaverManager.getInstance(app)

        // Default AUTO_LOW_BATTERY: 80% discharging -> animations active
        assertEquals(BatterySaverAnimationMode.AUTO_LOW_BATTERY, manager.getMode())
        manager.updateBatteryStatus(batteryLevelPercent = 80, isCharging = false, isSystemPowerSave = false)
        assertFalse(manager.shouldPauseAnimations.value)

        // Drop to 15% discharging -> animations paused
        manager.updateBatteryStatus(batteryLevelPercent = 15, isCharging = false, isSystemPowerSave = false)
        assertTrue(manager.shouldPauseAnimations.value)

        // Plugged in charging at 15% (no OS power save) -> animations active again
        manager.updateBatteryStatus(batteryLevelPercent = 15, isCharging = true, isSystemPowerSave = false)
        assertFalse(manager.shouldPauseAnimations.value)

        // OS system power save active at 70% -> animations paused
        manager.updateBatteryStatus(batteryLevelPercent = 70, isCharging = false, isSystemPowerSave = true)
        assertTrue(manager.shouldPauseAnimations.value)

        // ALWAYS_PAUSE pauses even at 100% charging
        manager.setMode(BatterySaverAnimationMode.ALWAYS_PAUSE)
        manager.updateBatteryStatus(batteryLevelPercent = 100, isCharging = true, isSystemPowerSave = false)
        assertTrue(manager.shouldPauseAnimations.value)

        // OFF allows animations even at 5% battery
        manager.setMode(BatterySaverAnimationMode.OFF)
        manager.updateBatteryStatus(batteryLevelPercent = 5, isCharging = false, isSystemPowerSave = true)
        assertFalse(manager.shouldPauseAnimations.value)

        // Verify preference persistence across singleton reset
        manager.setMode(BatterySaverAnimationMode.ALWAYS_PAUSE)
        AppBatterySaverManager.resetForTesting()
        val reloaded = AppBatterySaverManager.getInstance(app)
        assertEquals(BatterySaverAnimationMode.ALWAYS_PAUSE, reloaded.getMode())
        assertTrue(reloaded.shouldPauseAnimations.value)
    }

    @Test
    fun testBatterySaverStatusReflectedInSignalGraphAndSignalMapScreenRenders() {
        val manager = AppBatterySaverManager.getInstance(app)
        manager.setMode(BatterySaverAnimationMode.AUTO_LOW_BATTERY)
        manager.updateBatteryStatus(batteryLevelPercent = 12, isCharging = false, isSystemPowerSave = false)
        assertTrue(manager.shouldPauseAnimations.value)

        composeTestRule.setContent {
            NetworkToolkitTheme {
                SignalMapScreen(onNavigateBack = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Signal Mapper").assertIsDisplayed()
        composeTestRule.onNodeWithTag("new_survey_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("configure_rooms_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("saved_surveys_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery Saver", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("signal_map_list").performScrollToIndex(1)
        composeTestRule.onNodeWithTag("record_sample_button").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("signal_map_list").performScrollToIndex(2)
        composeTestRule.onNodeWithTag("confirm_room_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_room_quick_button").performScrollTo().assertIsDisplayed()
    }
}
