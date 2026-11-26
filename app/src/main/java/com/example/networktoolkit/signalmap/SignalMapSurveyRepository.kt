package com.example.networktoolkit.signalmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Snapshot of an active or saved Wi-Fi signal survey.
 */
data class SurveyDocument(
    val schemaVersion: Int = SignalMapSurveyRepository.CURRENT_SCHEMA_VERSION,
    val config: PropertySurveyConfig,
    val rooms: List<RoomZone>,
    val samples: List<SignalSample>,
    val selectedRoomId: String? = null,
    val migratedFromLegacy: Boolean = false
) {
    fun toSummary(): SavedSurveySummary {
        return SavedSurveySummary(
            surveyId = config.surveyId,
            propertyName = config.propertyName,
            propertyType = config.propertyType,
            floorCount = config.floorCount,
            roomCount = rooms.size,
            sampleCount = samples.size,
            updatedAtMs = config.updatedAtMs
        )
    }
}

/**
 * Versioned local persistence & migration manager for Signal Mapper surveys.
 * Preserves custom property setups, room configurations, and measurements across navigation/restarts,
 * and safely migrates legacy v1 survey payloads without discarding measurements.
 */
class SignalMapSurveyRepository(context: Context) {

    companion object {
        const val PREFS_NAME = "SignalMapSurveyStore"
        const val CURRENT_SCHEMA_VERSION = 3
        private const val KEY_ACTIVE_SURVEY_ID = "active_survey_id"
        private const val KEY_SURVEYS_INDEX = "saved_survey_ids"
        private const val KEY_SURVEY_PREFIX = "survey_doc_"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadActiveSurvey(): SurveyDocument? {
        val activeId = prefs.getString(KEY_ACTIVE_SURVEY_ID, null) ?: return null
        return loadSurvey(activeId)
    }

    fun loadSurvey(surveyId: String): SurveyDocument? {
        val raw = prefs.getString("$KEY_SURVEY_PREFIX$surveyId", null) ?: return null
        return runCatching {
            val doc = parseOrMigrate(raw)
            if (doc.migratedFromLegacy) {
                saveSurvey(doc.copy(migratedFromLegacy = false), setActive = false)
            }
            doc
        }.getOrNull()
    }

    fun saveSurvey(document: SurveyDocument, setActive: Boolean = true) {
        val updatedConfig = document.config.copy(
            configuredRoomCount = document.rooms.size,
            updatedAtMs = System.currentTimeMillis()
        )
        val normalized = document.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            config = updatedConfig
        )
        val json = serialize(normalized).toString()

        val currentIds = getSavedSurveyIdSet().toMutableSet()
        currentIds.add(updatedConfig.surveyId)

        val editor = prefs.edit()
            .putString("$KEY_SURVEY_PREFIX${updatedConfig.surveyId}", json)
            .putStringSet(KEY_SURVEYS_INDEX, currentIds)
        if (setActive) {
            editor.putString(KEY_ACTIVE_SURVEY_ID, updatedConfig.surveyId)
        }
        editor.apply()
    }

    fun listSavedSurveys(): List<SavedSurveySummary> {
        val ids = getSavedSurveyIdSet()
        return ids.mapNotNull { id -> loadSurvey(id)?.toSummary() }
            .sortedByDescending { it.updatedAtMs }
    }

    fun deleteSurvey(surveyId: String) {
        val ids = getSavedSurveyIdSet().toMutableSet()
        ids.remove(surveyId)
        val editor = prefs.edit()
            .remove("$KEY_SURVEY_PREFIX$surveyId")
            .putStringSet(KEY_SURVEYS_INDEX, ids)
        if (prefs.getString(KEY_ACTIVE_SURVEY_ID, null) == surveyId) {
            editor.putString(KEY_ACTIVE_SURVEY_ID, ids.firstOrNull())
        }
        editor.apply()
    }

    fun clearAllStoredSurveys() {
        prefs.edit().clear().apply()
    }

    /**
     * Parses a v2 survey JSON string or safely migrates a legacy v1 payload
     * (missing schemaVersion, propertyConfig, roomId, roomType, floorNumber).
     */
    fun parseOrMigrate(rawJson: String): SurveyDocument {
        val root = JSONObject(rawJson)
        val schemaVersion = root.optInt("schemaVersion", 1)
        val isLegacyV1 = schemaVersion < CURRENT_SCHEMA_VERSION || !root.has("config")

        val configObj = root.optJSONObject("config")
        val surveyId = configObj?.optString("surveyId")?.takeIf { it.isNotBlank() }
            ?: root.optString("surveyId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val propType = PropertyType.fromString(
            configObj?.optString("propertyType")?.takeIf { it.isNotBlank() }
                ?: root.optString("propertyType")
        )
        val propName = configObj?.optString("propertyName", "") ?: root.optString("propertyName", "")
        val floorCount = (configObj?.optInt("floorCount", 1) ?: 1).coerceAtLeast(1)

        val parsedRooms = mutableListOf<RoomZone>()
        val roomsArray = root.optJSONArray("rooms")
        if (roomsArray != null && roomsArray.length() > 0) {
            for (i in 0 until roomsArray.length()) {
                val obj = roomsArray.optJSONObject(i) ?: continue
                val name = obj.optString("name", "Room ${i + 1}")
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val customType = obj.optString("customTypeLabel", "").takeIf { it.isNotBlank() }
                parsedRooms.add(
                    RoomZone(
                        id = id,
                        name = name,
                        shortName = obj.optString("shortName").takeIf { it.isNotBlank() } ?: RoomZone.deriveShortName(name),
                        relX = obj.optDouble("relX", 0.04).toFloat(),
                        relY = obj.optDouble("relY", 0.04).toFloat(),
                        relWidth = obj.optDouble("relWidth", 0.30).toFloat(),
                        relHeight = obj.optDouble("relHeight", 0.30).toFloat(),
                        isPrimaryApLocation = obj.optBoolean("isPrimaryApLocation", i == 0),
                        approxSqFt = obj.optInt("approxSqFt", 150),
                        roomType = if (obj.has("roomType")) RoomType.fromString(obj.optString("roomType")) else RoomType.inferFromName(name),
                        customTypeLabel = customType,
                        floorNumber = obj.optInt("floorNumber", 1).coerceAtLeast(1),
                        orderIndex = obj.optInt("orderIndex", i)
                    )
                )
            }
        } else {
            parsedRooms.addAll(FloorPlanTemplates.getRoomsForProperty(propType))
        }

        // Parse samples and backfill missing stable roomId for legacy v1 records
        val roomsByName = parsedRooms.associateBy { it.name }.toMutableMap()
        val parsedSamples = mutableListOf<SignalSample>()
        val samplesArray = root.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until samplesArray.length()) {
            val obj = samplesArray.optJSONObject(i) ?: continue
            val sampleRoomName = obj.optString("roomName", "Unknown Area")
            var sampleRoomId = obj.optString("roomId", "")
            var matchedRoom = if (sampleRoomId.isNotBlank()) {
                parsedRooms.firstOrNull { it.id == sampleRoomId }
            } else {
                roomsByName[sampleRoomName]
            }

            // If legacy sample referenced a room name absent from templates, recover it as a custom room
            if (matchedRoom == null && sampleRoomName.isNotBlank()) {
                val recovered = RoomZone(
                    id = sampleRoomId.ifBlank { UUID.randomUUID().toString() },
                    name = sampleRoomName,
                    shortName = RoomZone.deriveShortName(sampleRoomName),
                    roomType = RoomType.inferFromName(sampleRoomName),
                    floorNumber = obj.optInt("floorNumber", 1).coerceAtLeast(1),
                    orderIndex = parsedRooms.size
                )
                parsedRooms.add(recovered)
                roomsByName[sampleRoomName] = recovered
                matchedRoom = recovered
            }

            if (matchedRoom != null) {
                sampleRoomId = matchedRoom.id
            }

            val snrVal = if (obj.has("snrDb") && !obj.isNull("snrDb")) obj.optInt("snrDb") else null
            val noiseVal = if (obj.has("noiseFloorDbm") && !obj.isNull("noiseFloorDbm")) obj.optInt("noiseFloorDbm") else null
            val sampleSsid = obj.optString("ssid", "")
            val sampleBssid = obj.optString("bssid", "")
            val inferredDemo = (sampleSsid.startsWith("Technician_Field_AP") && sampleBssid == "00:1A:2B:3C:4D:5E")
            val isSimulatedSample = if (obj.has("isSimulated")) {
                obj.optBoolean("isSimulated", false)
            } else {
                inferredDemo
            }

            parsedSamples.add(
                SignalSample(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    roomName = matchedRoom?.name ?: sampleRoomName,
                    relX = obj.optDouble("relX", (matchedRoom?.centerX ?: 0.5f).toDouble()).toFloat(),
                    relY = obj.optDouble("relY", (matchedRoom?.centerY ?: 0.5f).toDouble()).toFloat(),
                    rssiDbm = obj.optInt("rssiDbm", -70),
                    snrDb = snrVal,
                    noiseFloorDbm = noiseVal,
                    linkSpeedMbps = obj.optInt("linkSpeedMbps", 0),
                    frequencyMhz = obj.optInt("frequencyMhz", 0),
                    channel = obj.optInt("channel", 0),
                    bssid = sampleBssid,
                    ssid = sampleSsid,
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    notes = obj.optString("notes", ""),
                    roomId = sampleRoomId,
                    surveyId = obj.optString("surveyId").ifBlank { surveyId },
                    floorNumber = obj.optInt("floorNumber", matchedRoom?.floorNumber ?: 1).coerceAtLeast(1),
                    roomTypeLabel = obj.optString("roomTypeLabel").ifBlank { matchedRoom?.effectiveRoomType ?: "" },
                    isSimulated = isSimulatedSample
                )
            )
        }

        val finalRooms = if (isLegacyV1 && roomsArray == null) {
            DynamicFloorPlanLayoutEngine.layoutRooms(parsedRooms)
        } else {
            parsedRooms
        }

        val config = PropertySurveyConfig(
            surveyId = surveyId,
            propertyType = propType,
            propertyName = propName,
            floorCount = maxOf(floorCount, finalRooms.maxOfOrNull { it.floorNumber } ?: 1),
            configuredRoomCount = finalRooms.size,
            createdAtMs = configObj?.optLong("createdAtMs", System.currentTimeMillis()) ?: System.currentTimeMillis(),
            updatedAtMs = configObj?.optLong("updatedAtMs", System.currentTimeMillis()) ?: System.currentTimeMillis()
        )

        val selectedId = root.optString("selectedRoomId").takeIf { id ->
            id.isNotBlank() && finalRooms.any { it.id == id }
        } ?: finalRooms.firstOrNull()?.id

        return SurveyDocument(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            config = config,
            rooms = finalRooms,
            samples = parsedSamples,
            selectedRoomId = selectedId,
            migratedFromLegacy = isLegacyV1
        )
    }

    fun serialize(doc: SurveyDocument): JSONObject {
        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("selectedRoomId", doc.selectedRoomId ?: "")

        val configObj = JSONObject().apply {
            put("surveyId", doc.config.surveyId)
            put("propertyType", doc.config.propertyType.name)
            put("propertyName", doc.config.propertyName)
            put("floorCount", doc.config.floorCount)
            put("configuredRoomCount", doc.rooms.size)
            put("createdAtMs", doc.config.createdAtMs)
            put("updatedAtMs", doc.config.updatedAtMs)
        }
        root.put("config", configObj)

        val roomsArr = JSONArray()
        doc.rooms.forEach { room ->
            roomsArr.put(JSONObject().apply {
                put("id", room.id)
                put("name", room.name)
                put("shortName", room.shortName)
                put("relX", room.relX.toDouble())
                put("relY", room.relY.toDouble())
                put("relWidth", room.relWidth.toDouble())
                put("relHeight", room.relHeight.toDouble())
                put("isPrimaryApLocation", room.isPrimaryApLocation)
                put("approxSqFt", room.approxSqFt)
                put("roomType", room.roomType.name)
                put("customTypeLabel", room.customTypeLabel ?: "")
                put("floorNumber", room.floorNumber)
                put("orderIndex", room.orderIndex)
            })
        }
        root.put("rooms", roomsArr)

        val samplesArr = JSONArray()
        doc.samples.forEach { s ->
            samplesArr.put(JSONObject().apply {
                put("id", s.id)
                put("roomId", s.roomId)
                put("roomName", s.roomName)
                put("surveyId", s.surveyId)
                put("floorNumber", s.floorNumber)
                put("roomTypeLabel", s.roomTypeLabel)
                put("relX", s.relX.toDouble())
                put("relY", s.relY.toDouble())
                put("rssiDbm", s.rssiDbm)
                if (s.snrDb != null) put("snrDb", s.snrDb)
                if (s.noiseFloorDbm != null) put("noiseFloorDbm", s.noiseFloorDbm)
                put("linkSpeedMbps", s.linkSpeedMbps)
                put("frequencyMhz", s.frequencyMhz)
                put("channel", s.channel)
                put("bssid", s.bssid)
                put("ssid", s.ssid)
                put("timestampMs", s.timestampMs)
                put("notes", s.notes)
                put("isSimulated", s.isSimulated)
            })
        }
        root.put("samples", samplesArr)

        return root
    }

    private fun getSavedSurveyIdSet(): Set<String> {
        return prefs.getStringSet(KEY_SURVEYS_INDEX, emptySet()) ?: emptySet()
    }
}
