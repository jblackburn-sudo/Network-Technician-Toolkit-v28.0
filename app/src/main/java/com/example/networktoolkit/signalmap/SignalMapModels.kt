package com.example.networktoolkit.signalmap

import androidx.compose.ui.graphics.Color
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Property / venue classification for scaling and labeling the signal map survey.
 */
enum class PropertyType(
    val title: String,
    val subtitle: String,
    val description: String,
    val estimatedSqFt: Int,
    val scaleLabel: String,
    val aspectRatio: Float,
    val defaultRoomCount: Int
) {
    APARTMENT(
        title = "Apartment",
        subtitle = "Compact Space",
        description = "Single-level flat / condo with dense partition walls",
        estimatedSqFt = 850,
        scaleLabel = "600 – 1,200 sq ft",
        aspectRatio = 1.15f,
        defaultRoomCount = 5
    ),
    HOUSE(
        title = "House",
        subtitle = "Expansive Space",
        description = "Multi-room residential home with yard & perimeter",
        estimatedSqFt = 2800,
        scaleLabel = "2,000 – 3,800 sq ft",
        aspectRatio = 1.35f,
        defaultRoomCount = 8
    ),
    OFFICE(
        title = "Office",
        subtitle = "Workspace / Suite",
        description = "Commercial office, conference rooms & server closets",
        estimatedSqFt = 3200,
        scaleLabel = "1,500 – 5,000+ sq ft",
        aspectRatio = 1.30f,
        defaultRoomCount = 6
    ),
    OTHER(
        title = "Other",
        subtitle = "Custom Property",
        description = "Flexible custom venue (retail, studio, warehouse, campus)",
        estimatedSqFt = 1800,
        scaleLabel = "Custom sq ft",
        aspectRatio = 1.25f,
        defaultRoomCount = 4
    );

    companion object {
        fun fromString(raw: String?): PropertyType {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: APARTMENT
        }
    }
}

/**
 * Standard selectable room / survey area types. Technicians can also specify a custom label.
 */
enum class RoomType(
    val displayName: String,
    val defaultShortName: String,
    val defaultSqFt: Int
) {
    LIVING_ROOM("Living Room", "Living", 300),
    KITCHEN("Kitchen", "Kitchen", 180),
    BEDROOM("Bedroom", "Bedroom", 160),
    MASTER_BEDROOM("Master Bedroom", "Master", 240),
    BATHROOM("Bathroom", "Bath", 70),
    HALLWAY("Hallway", "Hallway", 90),
    CLOSET("Closet", "Closet", 45),
    OFFICE("Office", "Office", 180),
    BASEMENT("Basement", "Basement", 450),
    GARAGE("Garage", "Garage", 400),
    UTILITY_ROOM("Utility Room", "Utility", 80),
    STAIRWAY("Stairway", "Stairs", 60),
    DINING("Dining Room", "Dining", 180),
    PORCH("Porch / Patio", "Porch", 140),
    OTHER("Other", "Area", 150);

    companion object {
        fun fromString(raw: String?): RoomType {
            if (raw.isNullOrBlank()) return OTHER
            return entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.displayName.equals(raw, ignoreCase = true)
            } ?: OTHER
        }

        fun inferFromName(name: String): RoomType {
            val lower = name.lowercase(Locale.US)
            return when {
                lower.contains("master") -> MASTER_BEDROOM
                lower.contains("bed") -> BEDROOM
                lower.contains("living") || lower.contains("great") || lower.contains("lounge") -> LIVING_ROOM
                lower.contains("kitchen") || lower.contains("nook") -> KITCHEN
                lower.contains("bath") || lower.contains("restroom") -> BATHROOM
                lower.contains("hall") || lower.contains("entry") || lower.contains("foyer") || lower.contains("corridor") -> HALLWAY
                lower.contains("closet") || lower.contains("pantry") -> CLOSET
                lower.contains("office") || lower.contains("study") || lower.contains("conference") -> OFFICE
                lower.contains("basement") -> BASEMENT
                lower.contains("garage") -> GARAGE
                lower.contains("utility") || lower.contains("laundry") || lower.contains("mechanical") -> UTILITY_ROOM
                lower.contains("stair") -> STAIRWAY
                lower.contains("dining") -> DINING
                lower.contains("porch") || lower.contains("balcony") || lower.contains("patio") -> PORCH
                else -> OTHER
            }
        }
    }
}

/**
 * Property-level metadata for a Signal Mapper survey.
 */
data class PropertySurveyConfig(
    val surveyId: String = UUID.randomUUID().toString(),
    val propertyType: PropertyType = PropertyType.APARTMENT,
    val propertyName: String = "",
    val floorCount: Int = 1,
    val configuredRoomCount: Int = propertyType.defaultRoomCount,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val displayPropertyTitle: String
        get() = if (propertyName.isNotBlank()) {
            "$propertyName (${propertyType.title})"
        } else {
            propertyType.title
        }
}

/**
 * Summary entry for saved surveys list (used in Continue Survey workflow).
 */
data class SavedSurveySummary(
    val surveyId: String,
    val propertyName: String,
    val propertyType: PropertyType,
    val floorCount: Int,
    val roomCount: Int,
    val sampleCount: Int,
    val updatedAtMs: Long
) {
    val displayTitle: String
        get() = propertyName.ifBlank { "${propertyType.title} Survey" }
}

/**
 * Architectural room / survey zone inside the dynamic floor plan blueprint.
 * Uses a stable [id] so renaming, retyping, or rearranging rooms preserves all signal measurements.
 */
data class RoomZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val shortName: String = deriveShortName(name),
    val relX: Float = 0.04f,
    val relY: Float = 0.04f,
    val relWidth: Float = 0.30f,
    val relHeight: Float = 0.30f,
    val isPrimaryApLocation: Boolean = false,
    val approxSqFt: Int = 150,
    val roomType: RoomType = RoomType.inferFromName(name),
    val customTypeLabel: String? = null,
    val floorNumber: Int = 1,
    val orderIndex: Int = 0
) {
    val centerX: Float get() = relX + relWidth / 2f
    val centerY: Float get() = relY + relHeight / 2f

    val effectiveRoomType: String
        get() = customTypeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: roomType.displayName

    fun containsPoint(px: Float, py: Float): Boolean {
        return px in relX..(relX + relWidth) && py in relY..(relY + relHeight)
    }

    companion object {
        fun deriveShortName(fullName: String): String {
            val trimmed = fullName.trim()
            if (trimmed.isEmpty()) return "Area"
            return if (trimmed.length <= 12) trimmed else trimmed.take(11) + "…"
        }
    }
}

/**
 * Qualitative Wi-Fi signal rating aligned with RF ceiling (-30 dBm) and floor (-100 dBm) bounds.
 */
enum class SignalRating(
    val label: String,
    val ceilingFloorKind: String,
    val ceilingDbm: Int,
    val floorDbm: Int,
    val scoreOutOfFive: Int,
    val hexColor: Long,
    val recommendation: String
) {
    EXCELLENT(
        label = "Excellent",
        ceilingFloorKind = "Ceiling Tier (Peak)",
        ceilingDbm = -30,
        floorDbm = -55,
        scoreOutOfFive = 5,
        hexColor = 0xFF00E676,
        recommendation = "Optimal for 4K/8K streaming, competitive gaming & ultra-low latency"
    ),
    GOOD(
        label = "Good",
        ceilingFloorKind = "Upper-Mid (Strong)",
        ceilingDbm = -56,
        floorDbm = -67,
        scoreOutOfFive = 4,
        hexColor = 0xFF00E5FF,
        recommendation = "Reliable for HD video conferencing, high-throughput downloads"
    ),
    FAIR(
        label = "Fair",
        ceilingFloorKind = "Mid-Range (Moderate)",
        ceilingDbm = -68,
        floorDbm = -75,
        scoreOutOfFive = 3,
        hexColor = 0xFFFFD600,
        recommendation = "Acceptable for light web & email; potential buffering under load"
    ),
    POOR(
        label = "Weak",
        ceilingFloorKind = "Low-Margin (Near Floor)",
        ceilingDbm = -76,
        floorDbm = -82,
        scoreOutOfFive = 2,
        hexColor = 0xFFFF9100,
        recommendation = "Frequent packet drops & reduced speed; borderline coverage"
    ),
    DEAD_ZONE(
        label = "Dead Zone",
        ceilingFloorKind = "Noise Floor (Unusable)",
        ceilingDbm = -83,
        floorDbm = -100,
        scoreOutOfFive = 1,
        hexColor = 0xFFFF1744,
        recommendation = "Unusable signal; mesh satellite or Wi-Fi extender required"
    );

    val color: Color get() = Color(hexColor)
    val rangeLabel: String get() = "$floorDbm..$ceilingDbm dBm"

    companion object {
        const val RF_CEILING_DBM = -30
        const val RF_FLOOR_DBM = -100

        fun fromDbm(dbm: Int): SignalRating {
            return when {
                dbm >= -55 -> EXCELLENT
                dbm >= -67 -> GOOD
                dbm >= -75 -> FAIR
                dbm >= -82 -> POOR
                else -> DEAD_ZONE
            }
        }
    }
}

/**
 * Measurement sample recorded by technician at a physical coordinate on the floor plan.
 * Associates with a stable [roomId] as well as the room's [roomName] and [surveyId].
 */
data class SignalSample(
    val id: String = UUID.randomUUID().toString(),
    val roomName: String,
    val relX: Float,
    val relY: Float,
    val rssiDbm: Int,
    val snrDb: Int? = null,
    val noiseFloorDbm: Int? = null,
    val linkSpeedMbps: Int = 0,
    val frequencyMhz: Int = 0,
    val channel: Int = 0,
    val bssid: String = "",
    val ssid: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val notes: String = "",
    val roomId: String = "",
    val surveyId: String = "",
    val floorNumber: Int = 1,
    val roomTypeLabel: String = "",
    val isSimulated: Boolean = false
) {
    val rating: SignalRating get() = SignalRating.fromDbm(rssiDbm)

    fun belongsToRoom(room: RoomZone): Boolean {
        return if (roomId.isNotBlank()) {
            roomId == room.id
        } else {
            roomName == room.name
        }
    }
}

/**
 * Real-time telemetry snapshot of currently connected Wi-Fi.
 */
data class LiveConnectedTelemetry(
    val isConnected: Boolean = false,
    val ssid: String = "",
    val bssid: String = "",
    val currentRssiDbm: Int = -60,
    val snrDb: Int? = null,
    val noiseFloorDbm: Int? = null,
    val linkSpeedMbps: Int = 0,
    val frequencyMhz: Int = 0,
    val channel: Int = 0,
    val isWifiEnabled: Boolean = true,
    val isSimulatedDemo: Boolean = false
) {
    val rating: SignalRating get() = SignalRating.fromDbm(currentRssiDbm)
}

/**
 * Statistical analysis of the entire mapped space.
 */
data class CoverageAnalysis(
    val propertyType: PropertyType,
    val totalSamples: Int,
    val averageDbm: Int,
    val coverageScorePercent: Int,
    val deadZonesCount: Int,
    val weakZonesCount: Int,
    val goodZonesCount: Int,
    val bestRoom: String,
    val weakestRoom: String,
    val recommendedApPlacement: String,
    val summaryRecommendation: String,
    val realSamplesCount: Int = totalSamples,
    val simulatedSamplesCount: Int = 0,
    val hasSimulatedData: Boolean = false,
    val isSimulatedOnly: Boolean = false
)

/**
 * Dynamic floor plan layout engine capable of generating and adjusting non-overlapping architectural
 * layouts for any number of rooms, duplicate room types, custom names, and multi-floor buildings.
 */
object DynamicFloorPlanLayoutEngine {

    /**
     * Generates a flexible starter room list matching any requested [roomCount] (>= 1) and [floorCount] (>= 1).
     * Never forces an arbitrary room cap and distributes rooms sensibly across configured floors.
     */
    fun generateStarterRooms(
        propertyType: PropertyType,
        roomCount: Int,
        floorCount: Int = 1
    ): List<RoomZone> {
        val count = roomCount.coerceAtLeast(1)
        val floors = floorCount.coerceAtLeast(1)
        val baseCatalog = buildStarterCatalog(propertyType)

        val rawRooms = mutableListOf<RoomZone>()
        var bedroomCounter = 1
        var bathCounter = 1
        var hallCounter = 1
        var officeCounter = 1

        for (index in 0 until count) {
            val floorNum = ((index * floors) / count) + 1
            val room = if (index < baseCatalog.size) {
                val template = baseCatalog[index]
                when (template.roomType) {
                    RoomType.BEDROOM -> bedroomCounter++
                    RoomType.BATHROOM -> bathCounter++
                    RoomType.HALLWAY -> hallCounter++
                    RoomType.OFFICE -> officeCounter++
                    else -> Unit
                }
                template.copy(
                    id = UUID.randomUUID().toString(),
                    isPrimaryApLocation = (index == 0),
                    floorNumber = floorNum,
                    orderIndex = index
                )
            } else {
                // Generate sensible additional rooms (Bedroom 2, Bedroom 3, Hallway 2, Bath 2, etc.)
                when ((index - baseCatalog.size) % 4) {
                    0 -> {
                        val n = ++bedroomCounter
                        val name = "Bedroom $n"
                        RoomZone(
                            name = name,
                            shortName = "Bed $n",
                            approxSqFt = RoomType.BEDROOM.defaultSqFt,
                            roomType = RoomType.BEDROOM,
                            floorNumber = floorNum,
                            orderIndex = index
                        )
                    }
                    1 -> {
                        val n = ++bathCounter
                        val name = "Bathroom $n"
                        RoomZone(
                            name = name,
                            shortName = "Bath $n",
                            approxSqFt = RoomType.BATHROOM.defaultSqFt,
                            roomType = RoomType.BATHROOM,
                            floorNumber = floorNum,
                            orderIndex = index
                        )
                    }
                    2 -> {
                        val n = ++hallCounter
                        val name = if (floors > 1 && floorNum > 1) "Floor $floorNum Hallway" else "Hallway $n"
                        RoomZone(
                            name = name,
                            shortName = "Hall $n",
                            approxSqFt = RoomType.HALLWAY.defaultSqFt,
                            roomType = RoomType.HALLWAY,
                            floorNumber = floorNum,
                            orderIndex = index
                        )
                    }
                    else -> {
                        val n = ++officeCounter
                        val name = "Room / Area ${index + 1}"
                        RoomZone(
                            name = name,
                            shortName = "Area ${index + 1}",
                            approxSqFt = RoomType.OTHER.defaultSqFt,
                            roomType = RoomType.OTHER,
                            floorNumber = floorNum,
                            orderIndex = index
                        )
                    }
                }
            }
            rawRooms.add(room)
        }

        // Ensure exactly one primary AP location exists if list is non-empty
        val normalizedAp = if (rawRooms.none { it.isPrimaryApLocation } && rawRooms.isNotEmpty()) {
            rawRooms.mapIndexed { idx, room -> room.copy(isPrimaryApLocation = idx == 0) }
        } else {
            rawRooms
        }

        return layoutRooms(normalizedAp)
    }

    /**
     * Recomputes normalized non-overlapping bounding boxes (relX, relY, relWidth, relHeight in [0.04..0.96])
     * for an arbitrary list of [RoomZone] entries grouped cleanly by floor and ordered by [orderIndex].
     */
    fun layoutRooms(rooms: List<RoomZone>): List<RoomZone> {
        if (rooms.isEmpty()) return emptyList()

        val sorted = rooms
            .sortedWith(compareBy<RoomZone> { it.floorNumber }.thenBy { it.orderIndex })
            .mapIndexed { idx, room -> room.copy(orderIndex = idx) }

        val groupedByFloor = sorted.groupBy { it.floorNumber }.toSortedMap()
        val floorCount = groupedByFloor.size.coerceAtLeast(1)

        val outerPad = 0.04f
        val availWidth = 1f - 2 * outerPad
        val availHeight = 1f - 2 * outerPad
        val floorGap = if (floorCount > 1) 0.03f else 0f
        val cellGap = if (sorted.size > 12) 0.015f else 0.022f

        // Allocate vertical slice to each floor proportional to how many grid rows that floor needs
        val floorRowCounts = groupedByFloor.mapValues { (_, floorRooms) ->
            val cols = computeColumnCount(floorRooms.size)
            ceil(floorRooms.size / cols.toFloat()).toInt().coerceAtLeast(1)
        }
        val totalGridRows = floorRowCounts.values.sum().coerceAtLeast(1)
        val totalFloorGaps = floorGap * (floorCount - 1)
        val usableFloorHeight = (availHeight - totalFloorGaps).coerceAtLeast(0.2f)

        val result = mutableListOf<RoomZone>()
        var currentFloorTop = outerPad

        groupedByFloor.entries.forEachIndexed { floorIdx, (_, floorRooms) ->
            val rowsInFloor = floorRowCounts[floorRooms.first().floorNumber] ?: 1
            val floorHeight = usableFloorHeight * (rowsInFloor.toFloat() / totalGridRows.toFloat())
            val cols = computeColumnCount(floorRooms.size)

            val totalRowGaps = cellGap * (rowsInFloor - 1)
            val rowHeight = ((floorHeight - totalRowGaps) / rowsInFloor.toFloat()).coerceAtLeast(0.06f)

            for (row in 0 until rowsInFloor) {
                val startIdx = row * cols
                val endIdx = minOf(startIdx + cols, floorRooms.size)
                val rowRooms = floorRooms.subList(startIdx, endIdx)
                val roomsInThisRow = rowRooms.size.coerceAtLeast(1)
                val rowY = currentFloorTop + row * (rowHeight + cellGap)

                val totalColGaps = cellGap * (roomsInThisRow - 1)
                val colWidth = ((availWidth - totalColGaps) / roomsInThisRow.toFloat()).coerceAtLeast(0.08f)

                rowRooms.forEachIndexed { colIdx, room ->
                    val cellX = outerPad + colIdx * (colWidth + cellGap)
                    result.add(
                        room.copy(
                            relX = cellX,
                            relY = rowY,
                            relWidth = colWidth,
                            relHeight = rowHeight,
                            shortName = if (room.shortName.isBlank()) RoomZone.deriveShortName(room.name) else room.shortName
                        )
                    )
                }
            }

            currentFloorTop += floorHeight + (if (floorIdx < floorCount - 1) floorGap else 0f)
        }

        return result
    }

    /**
     * Remaps signal samples smoothly into their matching room's updated bounding box whenever a room
     * layout reflows (e.g., adding, removing, or rearranging rooms) so coordinates remain inside the room.
     */
    fun remapSamplesToUpdatedRooms(
        samples: List<SignalSample>,
        oldRooms: List<RoomZone>,
        newRooms: List<RoomZone>
    ): List<SignalSample> {
        if (samples.isEmpty()) return emptyList()
        val oldById = oldRooms.associateBy { it.id }
        val newById = newRooms.associateBy { it.id }

        return samples.map { sample ->
            val targetNewRoom = if (sample.roomId.isNotBlank()) {
                newById[sample.roomId]
            } else {
                newRooms.firstOrNull { it.name == sample.roomName }
            }
            if (targetNewRoom == null) {
                sample
            } else {
                val previousRoom = oldById[targetNewRoom.id]
                val (localX, localY) = if (previousRoom != null && previousRoom.relWidth > 0.01f && previousRoom.relHeight > 0.01f) {
                    val lx = ((sample.relX - previousRoom.relX) / previousRoom.relWidth).coerceIn(0.10f, 0.90f)
                    val ly = ((sample.relY - previousRoom.relY) / previousRoom.relHeight).coerceIn(0.10f, 0.90f)
                    lx to ly
                } else {
                    0.5f to 0.5f
                }
                sample.copy(
                    roomId = targetNewRoom.id,
                    roomName = targetNewRoom.name,
                    floorNumber = targetNewRoom.floorNumber,
                    roomTypeLabel = targetNewRoom.effectiveRoomType,
                    relX = targetNewRoom.relX + localX * targetNewRoom.relWidth,
                    relY = targetNewRoom.relY + localY * targetNewRoom.relHeight
                )
            }
        }
    }

    /**
     * Recommends a legible canvas aspect ratio based on the number of displayed rooms and floors
     * so labels and sample pins stay usable on compact phone screens.
     */
    fun recommendedAspectRatio(propertyType: PropertyType, rooms: List<RoomZone>): Float {
        if (rooms.isEmpty()) return propertyType.aspectRatio
        val floorGroups = rooms.groupBy { it.floorNumber }
        val totalRows = floorGroups.values.sumOf { floorRooms ->
            val cols = computeColumnCount(floorRooms.size)
            ceil(floorRooms.size / cols.toFloat()).toInt().coerceAtLeast(1)
        }
        return when {
            totalRows <= 2 -> propertyType.aspectRatio
            totalRows == 3 -> (propertyType.aspectRatio * 0.85f).coerceIn(0.85f, 1.25f)
            totalRows == 4 -> 0.78f
            totalRows == 5 -> 0.68f
            else -> max(0.50f, 3.0f / totalRows.toFloat())
        }
    }

    private fun computeColumnCount(roomCountOnFloor: Int): Int {
        return when {
            roomCountOnFloor <= 1 -> 1
            roomCountOnFloor <= 4 -> 2
            roomCountOnFloor <= 9 -> 3
            else -> minOf(4, ceil(sqrt(roomCountOnFloor.toDouble())).toInt())
        }
    }

    private fun buildStarterCatalog(propertyType: PropertyType): List<RoomZone> {
        return when (propertyType) {
            PropertyType.APARTMENT -> FloorPlanTemplates.getRoomsForProperty(PropertyType.APARTMENT)
            PropertyType.HOUSE -> FloorPlanTemplates.getRoomsForProperty(PropertyType.HOUSE)
            PropertyType.OFFICE -> listOf(
                RoomZone(id = "office_lobby", name = "Reception / Lobby", shortName = "Reception", approxSqFt = 350, roomType = RoomType.LIVING_ROOM, isPrimaryApLocation = true),
                RoomZone(id = "office_open", name = "Open Workspace", shortName = "Workspace", approxSqFt = 900, roomType = RoomType.OFFICE),
                RoomZone(id = "office_conf", name = "Conference Room", shortName = "Conf Room", approxSqFt = 320, roomType = RoomType.OFFICE),
                RoomZone(id = "office_break", name = "Break Room", shortName = "Break Rm", approxSqFt = 220, roomType = RoomType.KITCHEN),
                RoomZone(id = "office_server", name = "Server / IDF Closet", shortName = "IDF Closet", approxSqFt = 90, roomType = RoomType.CLOSET),
                RoomZone(id = "office_hall", name = "Main Corridor", shortName = "Corridor", approxSqFt = 180, roomType = RoomType.HALLWAY)
            )
            PropertyType.OTHER -> listOf(
                RoomZone(id = "other_main", name = "Main Area", shortName = "Main Area", approxSqFt = 500, roomType = RoomType.OTHER, isPrimaryApLocation = true),
                RoomZone(id = "other_entry", name = "Front Entry", shortName = "Entry", approxSqFt = 180, roomType = RoomType.HALLWAY),
                RoomZone(id = "other_back", name = "Back Room", shortName = "Back Room", approxSqFt = 320, roomType = RoomType.OTHER),
                RoomZone(id = "other_util", name = "Utility / Storage", shortName = "Utility", approxSqFt = 150, roomType = RoomType.UTILITY_ROOM)
            )
        }
    }
}

/**
 * Floor plan starter templates catalog preserving backward compatibility while supporting dynamic generation.
 */
object FloorPlanTemplates {

    fun getRoomsForProperty(propertyType: PropertyType): List<RoomZone> {
        return when (propertyType) {
            PropertyType.APARTMENT -> listOf(
                RoomZone(
                    id = "apt_living",
                    name = "Living Room",
                    shortName = "Living",
                    relX = 0.05f,
                    relY = 0.05f,
                    relWidth = 0.50f,
                    relHeight = 0.44f,
                    isPrimaryApLocation = true,
                    approxSqFt = 340,
                    roomType = RoomType.LIVING_ROOM,
                    floorNumber = 1,
                    orderIndex = 0
                ),
                RoomZone(
                    id = "apt_kitchen",
                    name = "Kitchen & Dining",
                    shortName = "Kitchen",
                    relX = 0.58f,
                    relY = 0.05f,
                    relWidth = 0.37f,
                    relHeight = 0.44f,
                    approxSqFt = 200,
                    roomType = RoomType.KITCHEN,
                    floorNumber = 1,
                    orderIndex = 1
                ),
                RoomZone(
                    id = "apt_entry",
                    name = "Entry / Hallway",
                    shortName = "Entry",
                    relX = 0.05f,
                    relY = 0.52f,
                    relWidth = 0.22f,
                    relHeight = 0.43f,
                    approxSqFt = 80,
                    roomType = RoomType.HALLWAY,
                    floorNumber = 1,
                    orderIndex = 2
                ),
                RoomZone(
                    id = "apt_bath",
                    name = "Bathroom",
                    shortName = "Bath",
                    relX = 0.30f,
                    relY = 0.52f,
                    relWidth = 0.25f,
                    relHeight = 0.43f,
                    approxSqFt = 70,
                    roomType = RoomType.BATHROOM,
                    floorNumber = 1,
                    orderIndex = 3
                ),
                RoomZone(
                    id = "apt_bed",
                    name = "Master Bedroom",
                    shortName = "Bedroom",
                    relX = 0.58f,
                    relY = 0.52f,
                    relWidth = 0.37f,
                    relHeight = 0.43f,
                    approxSqFt = 240,
                    roomType = RoomType.MASTER_BEDROOM,
                    floorNumber = 1,
                    orderIndex = 4
                )
            )

            PropertyType.HOUSE -> listOf(
                RoomZone(
                    id = "house_porch",
                    name = "Front Porch",
                    shortName = "Porch",
                    relX = 0.04f,
                    relY = 0.04f,
                    relWidth = 0.23f,
                    relHeight = 0.26f,
                    approxSqFt = 160,
                    roomType = RoomType.PORCH,
                    floorNumber = 1,
                    orderIndex = 0
                ),
                RoomZone(
                    id = "house_living",
                    name = "Living / Great Room",
                    shortName = "Living",
                    relX = 0.30f,
                    relY = 0.04f,
                    relWidth = 0.42f,
                    relHeight = 0.38f,
                    isPrimaryApLocation = true,
                    approxSqFt = 520,
                    roomType = RoomType.LIVING_ROOM,
                    floorNumber = 1,
                    orderIndex = 1
                ),
                RoomZone(
                    id = "house_kitchen",
                    name = "Kitchen & Nook",
                    shortName = "Kitchen",
                    relX = 0.75f,
                    relY = 0.04f,
                    relWidth = 0.21f,
                    relHeight = 0.38f,
                    approxSqFt = 310,
                    roomType = RoomType.KITCHEN,
                    floorNumber = 1,
                    orderIndex = 2
                ),
                RoomZone(
                    id = "house_dining",
                    name = "Formal Dining",
                    shortName = "Dining",
                    relX = 0.04f,
                    relY = 0.33f,
                    relWidth = 0.23f,
                    relHeight = 0.30f,
                    approxSqFt = 220,
                    roomType = RoomType.DINING,
                    floorNumber = 1,
                    orderIndex = 3
                ),
                RoomZone(
                    id = "house_hall",
                    name = "Central Hallway",
                    shortName = "Hallway",
                    relX = 0.30f,
                    relY = 0.45f,
                    relWidth = 0.42f,
                    relHeight = 0.18f,
                    approxSqFt = 140,
                    roomType = RoomType.HALLWAY,
                    floorNumber = 1,
                    orderIndex = 4
                ),
                RoomZone(
                    id = "house_garage",
                    name = "Attached Garage",
                    shortName = "Garage",
                    relX = 0.75f,
                    relY = 0.45f,
                    relWidth = 0.21f,
                    relHeight = 0.51f,
                    approxSqFt = 480,
                    roomType = RoomType.GARAGE,
                    floorNumber = 1,
                    orderIndex = 5
                ),
                RoomZone(
                    id = "house_master",
                    name = "Master Suite",
                    shortName = "Master",
                    relX = 0.04f,
                    relY = 0.66f,
                    relWidth = 0.29f,
                    relHeight = 0.30f,
                    approxSqFt = 390,
                    roomType = RoomType.MASTER_BEDROOM,
                    floorNumber = 1,
                    orderIndex = 6
                ),
                RoomZone(
                    id = "house_office",
                    name = "Office / Study",
                    shortName = "Office",
                    relX = 0.36f,
                    relY = 0.66f,
                    relWidth = 0.36f,
                    relHeight = 0.30f,
                    approxSqFt = 280,
                    roomType = RoomType.OFFICE,
                    floorNumber = 1,
                    orderIndex = 7
                )
            )

            PropertyType.OFFICE, PropertyType.OTHER -> {
                DynamicFloorPlanLayoutEngine.generateStarterRooms(
                    propertyType = propertyType,
                    roomCount = propertyType.defaultRoomCount,
                    floorCount = 1
                )
            }
        }
    }
}
