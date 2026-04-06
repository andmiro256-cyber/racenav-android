# Trophy Navigator Android — TODO

## 🟡 Средние

### Sprint 2: DownloadQueue
- Пауза/resume загрузки
- Retry backoff (1с → 5с → 30с)
- Checkpoint: tileExists в MBTiles

### Sprint 3: MBTilesReader + TileServer
- /offline/ маршрут в TileServer
- LruCache пул соединений
- AUTO online/offline переключение

### Sprint 4: DownloadedAreasFragment
- UI список скачанных областей
- Удаление, обновление, показ на карте

### Undo точки при рисовании полигона
- removeLastPoint() существует но не привязан к UI
- Добавить кнопку "Отменить" в Snackbar

## 🟢 Планы

### Полевое тестирование
- Сегментация трека (NaN маркеры)
- Bearing interpolation при езде
- GPS стабильность (START_STICKY)
- Офлайн карты со слоями

### Master ключи в APK
- Аудит безопасности
- Обфускация или серверная валидация
