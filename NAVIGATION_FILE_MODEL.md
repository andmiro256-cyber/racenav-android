# Navigation File Model

RACE-CRITICAL: this contract protects checkpoint capture during a live race.
Do not rewrite these rules casually during UI/import refactors.

This file documents the contract for imported navigation data. Do not change these rules casually.

## Semantic split

- `WPT` or GPX `<wpt>` = point set.
- `RTE` or GPX `<rte>/<rtept>` = route.
- `PLT` or GPX `<trk>/<trkpt>` = track.
- GPX is only a container and may contain points, routes, tracks, or several of them at once.
- Imported coordinates must be finite, in lat/lon range, and not `0,0`; Null Island is treated as a bad import/header artifact, not as a race point.

## Proximity rules

- A point-set waypoint radius belongs to the point layer only.
- A route waypoint radius belongs to the route only and drives route auto-advance.
- For `RTE` flow in this app, the route capture/taken radius comes from device settings (`PREF_WP_TAKEN_RADIUS`), not from `WPT`.
- If a route point name matches a loaded point-set point, the route may reuse that point's coordinates, color, and symbol.
- A matched point-set point must not overwrite the route capture radius.
- When both a point set and a route are loaded for the same named point, both circles must stay visible as separate map layers.

## Map layers

- Route circles and point-set circles must use different `GeoJsonSource` / layer ids.
- Hiding route waypoints must not hide point-set circles.
- Hiding point sets must not hide route circles.
- Route circles must stay visually above point-set circles, because route capture is the race-critical layer.

## Current implementation anchors

- GPX/Ozi parsing: `GpxParser.kt`
- Unified import entry point: `NavigationFileImporter.kt`
- Point-set load path: `MapFragment.loadPointSet(...)`
- Route load path: `MapFragment.loadRoute(...)`
- Route name resolution: `MapFragment.resolveRouteWaypoints(...)`
- Route radius rendering: `MapFragment.updateRadiusCircles()`
- Point radius rendering: `MapFragment.updateUserMarkerRadiusCircles()`
- Route auto-advance: `MapFragment.handleLocationUpdate(...)`

## Regression guard

If a future refactor tries to collapse route waypoints and point-set waypoints into one radius model again, it is a bug.
