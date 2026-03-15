# Задача: добавить новые источники карт в RaceNav

## Контекст

Приложение: `/opt/racenav-android/`
Главный файл: `/opt/racenav-android/app/src/main/java/com/andreykoff/racenav/MapFragment.kt`
Версия сейчас: `2.1.2` (versionCode = 149) в `app/build.gradle.kts`

## Что нужно сделать

Добавить новые источники карт в `MapFragment.kt`. Все URL проверены и возвращают HTTP 200.

---

## Текущие tileSources (уже есть — НЕ добавлять повторно)

```
osm, satellite, topo, google, yandex_sat, yandex_map,
kosmosnimki_relief, lomaps, cyclosm, tf_outdoors,
genshtab250, genshtab500, ggc500, ggc1000, topo250, topo500
```

## Текущие overlaySources (уже есть — НЕ добавлять повторно)

```
none, osm_gps, hiking, cycling, osm_ov, winter,
nakarte, labels_light, labels_dark, voyager_labels
```

---

## Что добавить в tileSources

Вставить ПОСЛЕ записи `"tf_outdoors"` и ПЕРЕД `"genshtab250"`:

```kotlin
"esri_clarity"  to TileSource("ESRI Clarity (спутник)", listOf(
    "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"), maxZoom = 19),
"google_maps"   to TileSource("Google Карты", listOf(
    "https://mt0.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
    "https://mt1.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
    "https://mt2.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
    "https://mt3.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
"google_terrain" to TileSource("Google Рельеф", listOf(
    "https://mt0.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
    "https://mt1.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
    "https://mt2.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
    "https://mt3.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
"google_hybrid" to TileSource("Google Гибрид", listOf(
    "https://mt0.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
    "https://mt1.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
    "https://mt2.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
    "https://mt3.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
"tf_transport"  to TileSource("TF Transport", listOf(
    "https://a.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
    "https://b.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
    "https://c.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
"tf_cycle"      to TileSource("TF Велосипед", listOf(
    "https://a.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
    "https://b.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
    "https://c.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
"osm_hot"       to TileSource("OSM Humanitarian", listOf(
    "https://tile-a.openstreetmap.fr/hot/{z}/{x}/{y}.png",
    "https://tile-b.openstreetmap.fr/hot/{z}/{x}/{y}.png"), maxZoom = 19),
"mtbmap"        to TileSource("MTB Map", listOf(
    "http://tile.mtbmap.cz/mtbmap_tiles/{z}/{x}/{y}.png"), maxZoom = 18),
"2gis"          to TileSource("2GIS", listOf(
    "https://tile0.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
    "https://tile1.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
    "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1"), maxZoom = 19),
"michelin"      to TileSource("Michelin", listOf(
    "https://map1.viamichelin.com/map/mapdirect?map=viamichelin&z={z}&x={x}&y={y}&format=png&version=201901161110&layer=background&locale=default&cs=1&protocol=https"), maxZoom = 18),
```

## Что добавить в overlaySources

Вставить ПОСЛЕ записи `"nakarte"` и ПЕРЕД `"labels_light"`:

```kotlin
"windy_relief"  to OverlaySource("Windy рельеф", listOf(
    "https://tiles.windy.com/tiles/v8.1/darkmap/{z}/{x}/{y}.png"), opacity = 0.5f),
"snowmap"       to OverlaySource("Горнолыжные трассы", listOf(
    "https://tiles.opensnowmap.org/pistes/{z}/{x}/{y}.png")),
```

> Примечание: `"winter"` уже есть с URL `opensnowmap.org/piste/` (без `s`). Новый `"snowmap"` использует `/pistes/` (с `s`) — другой эндпоинт, проверен 200.

---

## Порядок действий

1. Открыть `MapFragment.kt`
2. Найти блок `tileSources` (строки ~344–408)
3. Добавить новые записи tileSources перед `"genshtab250"`
4. Найти блок `overlaySources` (строки ~413–444)
5. Добавить новые записи overlaySources перед `"labels_light"`
6. Обновить версию в `app/build.gradle.kts`:
   - `versionCode = 150`
   - `versionName = "2.1.3"`
7. Собрать: `cd /opt/racenav-android && ./gradlew assembleRelease`
8. Скопировать APK: `cp app/build/outputs/apk/release/app-release.apk /var/www/html/racenav-v2.1.3.apk`
9. Обновить nginx: `sed -i 's|racenav-v[0-9.]*\.apk|racenav-v2.1.3.apk|g' /etc/nginx/sites-enabled/updates && systemctl reload nginx`

Скачать APK будет по адресу: http://87.120.84.254/trophy

