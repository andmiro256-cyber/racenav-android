package com.andreykoff.racenav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadParserTest {

    @Test
    fun parsesFlatPayloadAndSkipsInvalidCoordinates() {
        val payload = SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "waypoints": [
                    {"name":"Start","lat":59.9,"lng":30.3,"radius":25},
                    {"name":"Broken","lat":120,"lng":30.3}
                  ],
                  "tracks": [{
                    "name":"Trail",
                    "points":[
                      {"lat":59.9,"lng":30.3},
                      {"lat":59.91,"lng":30.31},
                      {"lat":"bad","lng":30.32}
                    ]
                  }],
                  "routes": [{
                    "name":"Race",
                    "labels":["CP1","CP2"],
                    "pointRadii":[75,100],
                    "points":[
                      {"lat":59.92,"lng":30.32},
                      {"lat":0,"lng":0}
                    ]
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, payload.waypoints.size)
        assertEquals("Start", payload.waypoints.single().name)
        assertEquals(2, payload.trackPointCount)
        assertEquals("CP1", payload.routes.single().points.single().name)
        assertEquals(75.0, payload.routes.single().points.single().proximity, 0.0)
        assertEquals(3, payload.skippedCoordinates)
    }

    @Test
    fun parsesModernNestedPayload() {
        val payload = SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "data": {
                    "waypoints": {"items":[{"name":"Camp","lat":60.1,"lng":30.5}]},
                    "tracks": {"items":[{"name":"Track","points":[{"lat":60.1,"lng":30.5}]}]},
                    "routes": {"items":[{"name":"Route","points":[{"name":"Finish","lat":60.2,"lng":30.6}]}]}
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("Camp", payload.waypoints.single().name)
        assertEquals("Track", payload.tracks.single().name)
        assertEquals("Finish", payload.routes.single().points.single().name)
        assertEquals(0, payload.skippedCoordinates)
    }

    @Test(expected = SyncPayloadException::class)
    fun emptyPayloadIsRejected() {
        SyncPayloadParser.parse(JSONObject("{}"))
    }

    @Test(expected = SyncPayloadException::class)
    fun serverErrorPayloadIsRejectedEvenWithArrays() {
        SyncPayloadParser.parse(
            JSONObject("""{"ok":false,"waypoints":[],"tracks":[],"routes":[]}""")
        )
    }

    @Test(expected = SyncPayloadException::class)
    fun incompleteSnapshotIsRejected() {
        SyncPayloadParser.parse(JSONObject("""{"waypoints":[],"routes":[]}"""))
    }

    @Test
    fun explicitEmptyFlatSnapshotIsAccepted() {
        val payload = SyncPayloadParser.parse(
            JSONObject("""{"ok":true,"waypoints":[],"tracks":[],"routes":[]}""")
        )

        assertTrue(payload.waypoints.isEmpty())
        assertTrue(payload.tracks.isEmpty())
        assertTrue(payload.routes.isEmpty())
    }

    @Test
    fun explicitEmptyNestedSnapshotIsAccepted() {
        val payload = SyncPayloadParser.parse(
            JSONObject(
                """
                {"ok":true,"data":{
                  "waypoints":{"items":[]},
                  "tracks":{"items":[]},
                  "routes":{"items":[]}
                }}
                """.trimIndent()
            )
        )

        assertTrue(payload.waypoints.isEmpty())
        assertTrue(payload.tracks.isEmpty())
        assertTrue(payload.routes.isEmpty())
    }

    @Test
    fun equivalentDualViewSnapshotIsAcceptedIgnoringObjectKeyOrder() {
        val payload = SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "ok":true,
                  "waypoints":[{"name":"A","lat":59.9,"lng":30.3}],
                  "tracks":[],
                  "routes":[],
                  "data":{
                    "waypoints":{"items":[{"lng":30.3,"lat":59.9,"name":"A"}]},
                    "tracks":{"items":[]},
                    "routes":{"items":[]}
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("A", payload.waypoints.single().name)
    }

    @Test(expected = SyncPayloadException::class)
    fun mismatchedDualViewSnapshotIsRejected() {
        SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "ok":true,
                  "waypoints":[],"tracks":[],"routes":[],
                  "data":{
                    "waypoints":{"items":[{"name":"A","lat":59.9,"lng":30.3}]},
                    "tracks":{"items":[]},
                    "routes":{"items":[]}
                  }
                }
                """.trimIndent()
            )
        )
    }

    @Test(expected = SyncPayloadException::class)
    fun partialNestedViewIsRejectedAlongsideCompleteFlatView() {
        SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "waypoints":[],"tracks":[],"routes":[],
                  "data":{"waypoints":{"items":[]}}
                }
                """.trimIndent()
            )
        )
    }

    @Test(expected = SyncPayloadException::class)
    fun partialFlatViewIsRejectedAlongsideCompleteNestedView() {
        SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "waypoints":[],
                  "data":{
                    "waypoints":{"items":[]},
                    "tracks":{"items":[]},
                    "routes":{"items":[]}
                  }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun unrelatedNestedSettingsDoNotInvalidateCompleteFlatView() {
        val payload = SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "waypoints":[],"tracks":[],"routes":[],
                  "data":{"settings":{"settings":{"theme":"dark"}}}
                }
                """.trimIndent()
            )
        )

        assertTrue(payload.waypoints.isEmpty())
        assertTrue(payload.tracks.isEmpty())
        assertTrue(payload.routes.isEmpty())
    }

    @Test(expected = SyncPayloadException::class)
    fun invalidOnlyPayloadIsRejected() {
        SyncPayloadParser.parse(
            JSONObject(
                """
                {
                  "waypoints":[{"lat":null,"lng":30}],
                  "tracks":[{"points":[{"lat":91,"lng":0}]}],
                  "routes":[{"points":[{"lat":10,"lng":181}]}]
                }
                """.trimIndent()
            )
        )
    }

    @Test(expected = SyncPayloadException::class)
    fun nestedOnlyInvalidTypeIsRejected() {
        SyncPayloadParser.parse(
            JSONObject(
                """
                {"data":{
                  "waypoints":{"items":[{"lat":0,"lng":0}]},
                  "tracks":{"items":[]},
                  "routes":{"items":[]}
                }}
                """.trimIndent()
            )
        )
    }

    @Test
    fun parsesCurrentDesktopSnapshotShape() {
        val waypoints = JSONArray()
        repeat(59) { index ->
            waypoints.put(
                JSONObject()
                    .put("name", "Point ${index + 1}")
                    .put("lat", 59.0 + index * 0.001)
                    .put("lng", 30.0 + index * 0.001)
            )
        }
        val routePoints = JSONArray()
        val routeLabels = JSONArray()
        val routeRadii = JSONArray()
        repeat(60) { index ->
            routePoints.put(JSONObject().put("lat", 59.0 + index * 0.001).put("lng", 30.0 + index * 0.001))
            routeLabels.put("WP${index + 1}")
            routeRadii.put(50 + index)
        }
        val root = JSONObject()
            .put("waypoints", waypoints)
            .put("tracks", JSONArray())
            .put(
                "routes",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Race route")
                        .put("points", routePoints)
                        .put("labels", routeLabels)
                        .put("pointRadii", routeRadii)
                )
            )

        val payload = SyncPayloadParser.parse(root)

        assertEquals(59, payload.waypoints.size)
        assertTrue(payload.tracks.isEmpty())
        assertEquals(60, payload.routes.single().points.size)
        assertEquals(109.0, payload.routes.single().points.last().proximity, 0.0)
        assertEquals(0, payload.skippedCoordinates)
    }
}
