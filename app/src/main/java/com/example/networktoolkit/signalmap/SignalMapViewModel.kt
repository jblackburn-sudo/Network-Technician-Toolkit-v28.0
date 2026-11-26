package com.example.networktoolkit.signalmap

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.TextReportExportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.sqrt

/**
 * ViewModel managing customizable property/room configuration, dynamic floor plan layout,
 * measurement association by stable room IDs, survey persistence, and report generation.
 */
class SignalMapViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = SignalMapEngine(application)
    private val repository = SignalMapSurveyRepository(application)

    val liveTelemetry: StateFlow<LiveConnectedTelemetry> = engine.liveTelemetry

    private val _propertyConfig = MutableStateFlow(PropertySurveyConfig())
    val propertyConfig: StateFlow<PropertySurveyConfig> = _propertyConfig.asStateFlow()

    private val _propertyType = MutableStateFlow(PropertyType.APARTMENT)
    val propertyType: StateFlow<PropertyType> = _propertyType.asStateFlow()

    private val _rooms = MutableStateFlow<List<RoomZone>>(emptyList())
    val rooms: StateFlow<List<RoomZone>> = _rooms.asStateFlow()

    private val _selectedRoom = MutableStateFlow<RoomZone?>(null)
    val selectedRoom: StateFlow<RoomZone?> = _selectedRoom.asStateFlow()

    private val _confirmedRoomId = MutableStateFlow<String?>(null)
    val confirmedRoomId: StateFlow<String?> = _confirmedRoomId.asStateFlow()

    private val _samples = MutableStateFlow<List<SignalSample>>(emptyList())
    val samples: StateFlow<List<SignalSample>> = _samples.asStateFlow()

    private val _selectedSampleForDetail = MutableStateFlow<SignalSample?>(null)
    val selectedSampleForDetail: StateFlow<SignalSample?> = _selectedSampleForDetail.asStateFlow()

    private val _activeFloorFilter = MutableStateFlow<Int?>(null)
    val activeFloorFilter: StateFlow<Int?> = _activeFloorFilter.asStateFlow()

    private val _savedSurveys = MutableStateFlow<List<SavedSurveySummary>>(emptyList())
    val savedSurveys: StateFlow<List<SavedSurveySummary>> = _savedSurveys.asStateFlow()

    private val _coverageAnalysis = MutableStateFlow(
        engine.calculateCoverageAnalysis(
            PropertyType.APARTMENT,
            FloorPlanTemplates.getRoomsForProperty(PropertyType.APARTMENT),
            emptyList()
        )
    )
    val coverageAnalysis: StateFlow<CoverageAnalysis> = _coverageAnalysis.asStateFlow()

    init {
        engine.startLiveTelemetryPolling()
        val restored = repository.loadActiveSurvey()
        if (restored != null) {
            applySurveyDocument(restored, persistSnapshot = false)
        } else {
            val defaultRooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.APARTMENT)
            val initialConfig = PropertySurveyConfig(
                propertyType = PropertyType.APARTMENT,
                propertyName = "",
                floorCount = 1,
                configuredRoomCount = defaultRooms.size
            )
            applyState(
                config = initialConfig,
                newRooms = defaultRooms,
                newSamples = emptyList(),
                selectedId = defaultRooms.firstOrNull()?.id,
                persistSnapshot = false
            )
        }
        refreshSavedSurveysList()
    }

    override fun onCleared() {
        super.onCleared()
        persistActiveSurvey()
        engine.stopLiveTelemetryPolling()
    }

    /**
     * Switches property type to default template rooms (preserves existing behavior & tests).
     * Updates state synchronously so both UI and ViewModel StateFlows reflect changes immediately.
     */
    fun setPropertyType(type: PropertyType) {
        if (_propertyType.value != type) {
            val newRooms = FloorPlanTemplates.getRoomsForProperty(type)
            val updatedConfig = _propertyConfig.value.copy(
                surveyId = if (_samples.value.isNotEmpty()) UUID.randomUUID().toString() else _propertyConfig.value.surveyId,
                propertyType = type,
                floorCount = 1,
                configuredRoomCount = newRooms.size,
                updatedAtMs = System.currentTimeMillis()
            )
            _activeFloorFilter.value = null
            _selectedSampleForDetail.value = null
            applyState(
                config = updatedConfig,
                newRooms = newRooms,
                newSamples = emptyList(),
                selectedId = newRooms.firstOrNull()?.id
            )
        }
    }

    /**
     * Starts a brand new configurable survey with optional custom property name, room count,
     * floor count, and starter or single-room layout.
     */
    fun startNewSurvey(
        propertyType: PropertyType,
        propertyName: String = "",
        roomCount: Int = propertyType.defaultRoomCount,
        floorCount: Int = 1,
        useStarterRooms: Boolean = true
    ) {
        val safeFloors = floorCount.coerceAtLeast(1)
        val safeCount = if (useStarterRooms) roomCount.coerceAtLeast(1) else 1
        val generatedRooms = if (useStarterRooms) {
            DynamicFloorPlanLayoutEngine.generateStarterRooms(
                propertyType = propertyType,
                roomCount = safeCount,
                floorCount = safeFloors
            )
        } else {
            val firstRoom = RoomZone(
                name = "Main Area",
                shortName = "Main Area",
                roomType = RoomType.LIVING_ROOM,
                floorNumber = 1,
                orderIndex = 0,
                isPrimaryApLocation = true
            )
            DynamicFloorPlanLayoutEngine.layoutRooms(listOf(firstRoom))
        }

        val config = PropertySurveyConfig(
            surveyId = UUID.randomUUID().toString(),
            propertyType = propertyType,
            propertyName = propertyName.trim(),
            floorCount = safeFloors,
            configuredRoomCount = generatedRooms.size
        )
        _activeFloorFilter.value = null
        _selectedSampleForDetail.value = null
        applyState(
            config = config,
            newRooms = generatedRooms,
            newSamples = emptyList(),
            selectedId = generatedRooms.firstOrNull()?.id
        )
    }

    /**
     * Updates property metadata (name, type, floor count) WITHOUT wiping existing rooms or measurements.
     */
    fun updatePropertyMetadata(
        propertyType: PropertyType,
        propertyName: String,
        floorCount: Int
    ) {
        val updatedConfig = _propertyConfig.value.copy(
            propertyType = propertyType,
            propertyName = propertyName.trim(),
            floorCount = floorCount.coerceAtLeast(1),
            updatedAtMs = System.currentTimeMillis()
        )
        applyState(
            config = updatedConfig,
            newRooms = _rooms.value,
            newSamples = _samples.value,
            selectedId = _selectedRoom.value?.id
        )
    }

    /**
     * Adds a new room or area dynamically, recomputes non-overlapping floor plan boxes,
     * and remaps existing samples cleanly to their rooms' updated bounds without losing data.
     */
    fun addRoom(
        name: String,
        roomType: RoomType = RoomType.OTHER,
        customTypeLabel: String? = null,
        floorNumber: Int = _activeFloorFilter.value ?: 1,
        isPrimaryApLocation: Boolean = _rooms.value.isEmpty(),
        selectAfterAdd: Boolean = true
    ): RoomZone {
        val cleanName = name.trim().ifEmpty {
            val idx = _rooms.value.size + 1
            "${roomType.displayName} $idx"
        }
        val cleanCustomType = customTypeLabel?.trim()?.takeIf { it.isNotEmpty() }
        val targetFloor = floorNumber.coerceAtLeast(1)
        val oldRooms = _rooms.value

        val newRoomId = UUID.randomUUID().toString()
        val baseList = if (isPrimaryApLocation) {
            oldRooms.map { it.copy(isPrimaryApLocation = false) }
        } else {
            oldRooms
        }
        val addedRaw = RoomZone(
            id = newRoomId,
            name = cleanName,
            shortName = RoomZone.deriveShortName(cleanName),
            isPrimaryApLocation = isPrimaryApLocation || oldRooms.none { it.isPrimaryApLocation },
            approxSqFt = roomType.defaultSqFt,
            roomType = roomType,
            customTypeLabel = cleanCustomType,
            floorNumber = targetFloor,
            orderIndex = baseList.size
        )

        val laidOut = DynamicFloorPlanLayoutEngine.layoutRooms(baseList + addedRaw)
        val remappedSamples = DynamicFloorPlanLayoutEngine.remapSamplesToUpdatedRooms(
            samples = _samples.value,
            oldRooms = oldRooms,
            newRooms = laidOut
        )

        val updatedConfig = _propertyConfig.value.copy(
            floorCount = maxOf(_propertyConfig.value.floorCount, targetFloor),
            configuredRoomCount = laidOut.size,
            updatedAtMs = System.currentTimeMillis()
        )

        val chosenSelectedId = if (selectAfterAdd) newRoomId else _selectedRoom.value?.id
        applyState(
            config = updatedConfig,
            newRooms = laidOut,
            newSamples = remappedSamples,
            selectedId = chosenSelectedId
        )
        return laidOut.first { it.id == newRoomId }
    }

    /**
     * Renames, re-types, or moves a room across floors while keeping its stable [roomId] and
     * preserving/updating all signal measurements associated with that room.
     */
    fun updateRoom(
        roomId: String,
        name: String,
        roomType: RoomType,
        customTypeLabel: String? = null,
        floorNumber: Int? = null,
        isPrimaryApLocation: Boolean? = null
    ) {
        val oldRooms = _rooms.value
        val existing = oldRooms.firstOrNull { it.id == roomId } ?: return
        val cleanName = name.trim().ifEmpty { existing.name }
        val cleanCustomType = customTypeLabel?.trim()?.takeIf { it.isNotEmpty() }
        val nextFloor = (floorNumber ?: existing.floorNumber).coerceAtLeast(1)
        val floorChanged = nextFloor != existing.floorNumber

        val updatedRaw = oldRooms.map { room ->
            when {
                room.id == roomId -> room.copy(
                    name = cleanName,
                    shortName = RoomZone.deriveShortName(cleanName),
                    roomType = roomType,
                    customTypeLabel = cleanCustomType,
                    floorNumber = nextFloor,
                    isPrimaryApLocation = isPrimaryApLocation ?: room.isPrimaryApLocation
                )
                isPrimaryApLocation == true -> room.copy(isPrimaryApLocation = false)
                else -> room
            }
        }

        val finalRooms = if (floorChanged) {
            DynamicFloorPlanLayoutEngine.layoutRooms(updatedRaw)
        } else {
            updatedRaw
        }

        // Preserve all measurements tied to this stable roomId and keep display metadata in sync
        val afterTargetRoom = finalRooms.first { it.id == roomId }
        val updatedSamples = DynamicFloorPlanLayoutEngine.remapSamplesToUpdatedRooms(
            samples = _samples.value.map { sample ->
                if (sample.belongsToRoom(existing)) {
                    sample.copy(
                        roomId = afterTargetRoom.id,
                        roomName = afterTargetRoom.name,
                        floorNumber = afterTargetRoom.floorNumber,
                        roomTypeLabel = afterTargetRoom.effectiveRoomType
                    )
                } else {
                    sample
                }
            },
            oldRooms = oldRooms,
            newRooms = finalRooms
        )

        val updatedConfig = _propertyConfig.value.copy(
            floorCount = maxOf(_propertyConfig.value.floorCount, finalRooms.maxOfOrNull { it.floorNumber } ?: 1),
            updatedAtMs = System.currentTimeMillis()
        )
        applyState(
            config = updatedConfig,
            newRooms = finalRooms,
            newSamples = updatedSamples,
            selectedId = _selectedRoom.value?.id ?: roomId
        )
    }

    /**
     * Safely removes a configured room.
     * Unrelated rooms and their measurements are NEVER deleted.
     * By default ([deleteAssociatedSamples] = false), any measurements in the removed room are preserved
     * as archived measurements rather than silently destroyed.
     */
    fun removeRoom(roomId: String, deleteAssociatedSamples: Boolean = false) {
        val oldRooms = _rooms.value
        val target = oldRooms.firstOrNull { it.id == roomId } ?: return
        val remainingRaw = oldRooms.filterNot { it.id == roomId }

        val normalizedAp = if (remainingRaw.isNotEmpty() && remainingRaw.none { it.isPrimaryApLocation }) {
            remainingRaw.mapIndexed { idx, room -> room.copy(isPrimaryApLocation = idx == 0) }
        } else {
            remainingRaw
        }
        val laidOut = DynamicFloorPlanLayoutEngine.layoutRooms(normalizedAp)

        val updatedSamples = _samples.value.mapNotNull { sample ->
            if (sample.belongsToRoom(target)) {
                if (deleteAssociatedSamples) {
                    null
                } else {
                    sample.copy(
                        roomId = "",
                        roomName = "${target.name} (Archived)"
                    )
                }
            } else {
                sample
            }
        }
        val remappedSamples = DynamicFloorPlanLayoutEngine.remapSamplesToUpdatedRooms(
            samples = updatedSamples,
            oldRooms = oldRooms,
            newRooms = laidOut
        )

        val nextSelectedId = if (_selectedRoom.value?.id == roomId) {
            laidOut.firstOrNull()?.id
        } else {
            _selectedRoom.value?.id
        }
        if (_confirmedRoomId.value == roomId) {
            _confirmedRoomId.value = nextSelectedId
        }

        val updatedConfig = _propertyConfig.value.copy(
            configuredRoomCount = laidOut.size,
            updatedAtMs = System.currentTimeMillis()
        )
        applyState(
            config = updatedConfig,
            newRooms = laidOut,
            newSamples = remappedSamples,
            selectedId = nextSelectedId
        )
    }

    /**
     * Rearranges a room earlier or later in the floor plan ordering, reflowing layout bounds
     * and keeping all sample coordinates inside their rooms.
     */
    fun moveRoomOrder(roomId: String, moveUp: Boolean) {
        val oldRooms = _rooms.value.toMutableList()
        val index = oldRooms.indexOfFirst { it.id == roomId }
        if (index == -1) return
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in oldRooms.indices) return

        val moving = oldRooms.removeAt(index)
        oldRooms.add(targetIndex, moving)
        val reindexed = oldRooms.mapIndexed { idx, r -> r.copy(orderIndex = idx) }
        val laidOut = DynamicFloorPlanLayoutEngine.layoutRooms(reindexed)
        val remappedSamples = DynamicFloorPlanLayoutEngine.remapSamplesToUpdatedRooms(
            samples = _samples.value,
            oldRooms = _rooms.value,
            newRooms = laidOut
        )
        applyState(
            config = _propertyConfig.value,
            newRooms = laidOut,
            newSamples = remappedSamples,
            selectedId = _selectedRoom.value?.id
        )
    }

    /**
     * Adjusts the survey to have [targetCount] rooms without destroying existing rooms/measurements
     * when growing, and trimming extra empty slots from the end when shrinking if requested.
     */
    fun applyRoomCount(targetCount: Int, floorCount: Int = _propertyConfig.value.floorCount) {
        val desired = targetCount.coerceAtLeast(1)
        val oldRooms = _rooms.value
        val nextFloors = floorCount.coerceAtLeast(1)

        val rawNext = if (desired > oldRooms.size) {
            val extra = desired - oldRooms.size
            val additions = (0 until extra).map { offset ->
                val num = oldRooms.size + offset + 1
                val floorNum = ((num - 1) * nextFloors / desired) + 1
                RoomZone(
                    name = "Room $num",
                    shortName = "Room $num",
                    roomType = RoomType.OTHER,
                    floorNumber = floorNum,
                    orderIndex = oldRooms.size + offset
                )
            }
            oldRooms + additions
        } else {
            oldRooms.take(desired)
        }

        val normalizedAp = if (rawNext.isNotEmpty() && rawNext.none { it.isPrimaryApLocation }) {
            rawNext.mapIndexed { idx, r -> r.copy(isPrimaryApLocation = idx == 0) }
        } else {
            rawNext
        }
        val laidOut = DynamicFloorPlanLayoutEngine.layoutRooms(normalizedAp)
        val remappedSamples = DynamicFloorPlanLayoutEngine.remapSamplesToUpdatedRooms(
            samples = _samples.value,
            oldRooms = oldRooms,
            newRooms = laidOut
        )
        val updatedConfig = _propertyConfig.value.copy(
            floorCount = nextFloors,
            configuredRoomCount = laidOut.size,
            updatedAtMs = System.currentTimeMillis()
        )
        applyState(
            config = updatedConfig,
            newRooms = laidOut,
            newSamples = remappedSamples,
            selectedId = _selectedRoom.value?.id?.takeIf { id -> laidOut.any { it.id == id } } ?: laidOut.firstOrNull()?.id
        )
    }

    fun selectRoom(room: RoomZone?) {
        _selectedRoom.value = room
    }

    fun confirmCurrentRoom(roomId: String) {
        val room = _rooms.value.firstOrNull { it.id == roomId } ?: return
        _selectedRoom.value = room
        _confirmedRoomId.value = room.id
    }

    fun setFloorFilter(floor: Int?) {
        _activeFloorFilter.value = floor
        if (floor != null) {
            val currentSel = _selectedRoom.value
            if (currentSel == null || currentSel.floorNumber != floor) {
                _selectedRoom.value = _rooms.value.firstOrNull { it.floorNumber == floor } ?: currentSel
            }
        }
    }

    fun selectSampleForDetail(sample: SignalSample?) {
        _selectedSampleForDetail.value = sample
    }

    /**
     * Records a measurement sample inside the currently selected/confirmed room.
     */
    fun recordSampleInSelectedRoom() {
        val currentRoom = _selectedRoom.value ?: _rooms.value.firstOrNull() ?: return
        _confirmedRoomId.value = currentRoom.id
        recordSampleAtCoordinates(
            relX = currentRoom.centerX,
            relY = currentRoom.centerY,
            explicitRoomName = currentRoom.name,
            explicitRoom = currentRoom
        )
    }

    /**
     * Records a measurement sample at specific normalized canvas coordinates (relX, relY in 0..1)
     * and binds it stably to the matched or selected [RoomZone].
     */
    fun recordSampleAtCoordinates(
        relX: Float,
        relY: Float,
        explicitRoomName: String? = null,
        explicitRoom: RoomZone? = null
    ) {
        val telemetry = liveTelemetry.value
        val clampedX = relX.coerceIn(0.02f, 0.98f)
        val clampedY = relY.coerceIn(0.02f, 0.98f)

        val currentRooms = _rooms.value
        val floorFiltered = _activeFloorFilter.value?.let { fl ->
            currentRooms.filter { it.floorNumber == fl }
        } ?: currentRooms

        val matchedRoom = explicitRoom
            ?: floorFiltered.find { it.containsPoint(clampedX, clampedY) }
            ?: _selectedRoom.value

        val roomName = explicitRoomName
            ?: matchedRoom?.name
            ?: "Area (${String.format("%.0f%%, %.0f%%", clampedX * 100, clampedY * 100)})"
        val roomId = matchedRoom?.id ?: ""
        val floorNum = matchedRoom?.floorNumber ?: 1
        val roomTypeLabel = matchedRoom?.effectiveRoomType ?: "Area"

        val isRealConnected = telemetry.isConnected && !telemetry.isSimulatedDemo
        val rssiToRecord = if (isRealConnected) {
            telemetry.currentRssiDbm
        } else {
            val primaryAp = currentRooms.find { it.isPrimaryApLocation } ?: currentRooms.firstOrNull()
            val dist = if (primaryAp != null) {
                val dx = clampedX - primaryAp.centerX
                val dy = clampedY - primaryAp.centerY
                sqrt(dx * dx + dy * dy)
            } else {
                0.2f
            }
            val scaleFactor = when (_propertyType.value) {
                PropertyType.APARTMENT -> 42f
                PropertyType.HOUSE -> 65f
                PropertyType.OFFICE -> 70f
                PropertyType.OTHER -> 55f
            }
            val baseRssi = -42
            val calculated = (baseRssi - (dist * scaleFactor) + ((-3..3).random())).toInt()
            calculated.coerceIn(-92, -38)
        }

        val newSample = SignalSample(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            roomName = roomName,
            surveyId = _propertyConfig.value.surveyId,
            floorNumber = floorNum,
            roomTypeLabel = roomTypeLabel,
            relX = clampedX,
            relY = clampedY,
            rssiDbm = rssiToRecord,
            snrDb = if (isRealConnected) telemetry.snrDb else null,
            noiseFloorDbm = if (isRealConnected) telemetry.noiseFloorDbm else null,
            linkSpeedMbps = if (telemetry.linkSpeedMbps > 0) telemetry.linkSpeedMbps else 0,
            frequencyMhz = telemetry.frequencyMhz,
            channel = telemetry.channel,
            bssid = telemetry.bssid,
            ssid = if (isRealConnected) telemetry.ssid else telemetry.ssid.ifBlank { "Offline (Simulated)" },
            timestampMs = System.currentTimeMillis(),
            notes = if (isRealConnected) "" else "Simulated offline sample (no live Wi-Fi telemetry)",
            isSimulated = !isRealConnected
        )

        val nextSamples = _samples.value + newSample
        if (matchedRoom != null) {
            _selectedRoom.value = matchedRoom
            _confirmedRoomId.value = matchedRoom.id
        }
        applyState(
            config = _propertyConfig.value,
            newRooms = _rooms.value,
            newSamples = nextSamples,
            selectedId = _selectedRoom.value?.id
        )
    }

    fun deleteSample(sampleId: String) {
        val next = _samples.value.filterNot { it.id == sampleId }
        if (_selectedSampleForDetail.value?.id == sampleId) {
            _selectedSampleForDetail.value = null
        }
        applyState(
            config = _propertyConfig.value,
            newRooms = _rooms.value,
            newSamples = next,
            selectedId = _selectedRoom.value?.id
        )
    }

    fun clearSimulatedSamples() {
        val next = _samples.value.filterNot { it.isSimulated }
        if (_selectedSampleForDetail.value?.isSimulated == true) {
            _selectedSampleForDetail.value = null
        }
        applyState(
            config = _propertyConfig.value,
            newRooms = _rooms.value,
            newSamples = next,
            selectedId = _selectedRoom.value?.id
        )
    }

    fun clearAllSamples() {
        _selectedSampleForDetail.value = null
        applyState(
            config = _propertyConfig.value,
            newRooms = _rooms.value,
            newSamples = emptyList(),
            selectedId = _selectedRoom.value?.id
        )
    }

    /**
     * Seeds demo samples across all configured rooms so technicians can preview the heatmap.
     * Demo samples are strictly tagged [SignalSample.isSimulated] = true so they never masquerade
     * as real Wi-Fi RF measurements.
     */
    fun populateDemoSurvey() {
        val currentRooms = _rooms.value
        val newSamples = mutableListOf<SignalSample>()
        val primaryAp = currentRooms.find { it.isPrimaryApLocation } ?: currentRooms.firstOrNull() ?: return

        currentRooms.forEach { room ->
            val dx = room.centerX - primaryAp.centerX
            val dy = room.centerY - primaryAp.centerY
            val dist = sqrt(dx * dx + dy * dy)
            val drop = if (_propertyType.value == PropertyType.APARTMENT) dist * 38f else dist * 60f
            val sampleRssi = (-44 - drop).toInt().coerceIn(-88, -42)

            newSamples.add(
                SignalSample(
                    id = UUID.randomUUID().toString(),
                    roomId = room.id,
                    roomName = room.name,
                    surveyId = _propertyConfig.value.surveyId,
                    floorNumber = room.floorNumber,
                    roomTypeLabel = room.effectiveRoomType,
                    relX = room.centerX,
                    relY = room.centerY,
                    rssiDbm = sampleRssi,
                    linkSpeedMbps = if (sampleRssi > -60) 866 else if (sampleRssi > -75) 433 else 86,
                    frequencyMhz = 5180,
                    channel = 36,
                    bssid = "00:1A:2B:3C:4D:5E",
                    ssid = "Technician_Field_AP (Demo)",
                    notes = "Simulated demo walkthrough point",
                    isSimulated = true
                )
            )
        }
        applyState(
            config = _propertyConfig.value,
            newRooms = currentRooms,
            newSamples = newSamples,
            selectedId = _selectedRoom.value?.id ?: currentRooms.firstOrNull()?.id
        )
    }

    fun continueSurvey(surveyId: String): Boolean {
        val loaded = repository.loadSurvey(surveyId) ?: return false
        _activeFloorFilter.value = null
        _selectedSampleForDetail.value = null
        applySurveyDocument(loaded, persistSnapshot = true)
        return true
    }

    fun deleteSavedSurvey(surveyId: String) {
        repository.deleteSurvey(surveyId)
        refreshSavedSurveysList()
    }

    fun persistActiveSurvey() {
        repository.saveSurvey(
            SurveyDocument(
                config = _propertyConfig.value.copy(configuredRoomCount = _rooms.value.size),
                rooms = _rooms.value,
                samples = _samples.value,
                selectedRoomId = _selectedRoom.value?.id
            ),
            setActive = true
        )
        refreshSavedSurveysList()
    }

    fun refreshSavedSurveysList() {
        _savedSurveys.value = repository.listSavedSurveys()
    }

    fun collectSensitiveIdentifiers(context: Context): SensitiveWifiIdentifiers {
        val ssids = buildList {
            add(liveTelemetry.value.ssid)
            _samples.value.forEach { add(it.ssid) }
        }
        val bssids = buildList {
            add(liveTelemetry.value.bssid)
            _samples.value.forEach { add(it.bssid) }
        }
        return TextReportExportManager.collectIdentifiers(
            context = context,
            extraSsids = ssids,
            extraBssids = bssids
        )
    }

    fun getDefaultTxtFilename(): String {
        val label = _propertyConfig.value.propertyName.ifBlank { _propertyType.value.title }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return TextReportExportManager.suggestFilename("WiFi_Signal_Map_Report_$label", "rtf")
    }

    fun generateTextReport(): String {
        return engine.generateTextReport(
            propertyType = _propertyType.value,
            rooms = _rooms.value,
            samples = _samples.value,
            analysis = _coverageAnalysis.value,
            telemetry = liveTelemetry.value,
            propertyConfig = _propertyConfig.value
        )
    }

    fun generateJsonReport(): String {
        return engine.generateJsonReport(
            propertyType = _propertyType.value,
            rooms = _rooms.value,
            samples = _samples.value,
            analysis = _coverageAnalysis.value,
            telemetry = liveTelemetry.value,
            propertyConfig = _propertyConfig.value
        )
    }

    @JvmOverloads
    fun createShareIntent(
        context: Context,
        isJson: Boolean,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val extension = if (isJson) "json" else "rtf"
        val label = _propertyConfig.value.propertyName.ifBlank { _propertyType.value.title }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = TextReportExportManager.suggestFilename(
            prefix = "WiFi_Signal_Map_Report_$label",
            extension = extension
        )
        val rawContent = if (isJson) generateJsonReport() else generateTextReport()
        val identifiers = collectSensitiveIdentifiers(context)
        return TextReportExportManager.createShareIntentWithPrivacy(
            context = context,
            rawContent = rawContent,
            filename = filename,
            subject = "Wi-Fi Signal Map Survey - ${_propertyConfig.value.displayPropertyTitle}",
            chooserTitle = "Share Signal Map Survey",
            identifiers = identifiers,
            isJson = isJson,
            privacyChoice = privacyChoice
        )
    }

    private fun applySurveyDocument(doc: SurveyDocument, persistSnapshot: Boolean) {
        applyState(
            config = doc.config,
            newRooms = doc.rooms,
            newSamples = doc.samples,
            selectedId = doc.selectedRoomId,
            persistSnapshot = persistSnapshot
        )
    }

    private fun applyState(
        config: PropertySurveyConfig,
        newRooms: List<RoomZone>,
        newSamples: List<SignalSample>,
        selectedId: String?,
        persistSnapshot: Boolean = true
    ) {
        val normalizedConfig = config.copy(
            configuredRoomCount = newRooms.size,
            updatedAtMs = System.currentTimeMillis()
        )
        _propertyConfig.value = normalizedConfig
        _propertyType.value = normalizedConfig.propertyType
        _rooms.value = newRooms
        _samples.value = newSamples
        _selectedRoom.value = newRooms.firstOrNull { it.id == selectedId } ?: newRooms.firstOrNull()
        _coverageAnalysis.value = engine.calculateCoverageAnalysis(
            propertyType = normalizedConfig.propertyType,
            rooms = newRooms,
            samples = newSamples,
            propertyConfig = normalizedConfig
        )
        if (persistSnapshot) {
            persistActiveSurvey()
        }
    }
}
