# SeaBathy

SeaBathy is a native Kotlin Android app for viewing offline MBTiles bathymetry and marine map layers. Many navigation maps are derived from publicly available data. If you'd like to help improve SeaBathy, please let me know! 😃

## Features

- Offline MBTiles loading
- MapLibre map display
- Raster and vector MBTiles support
- Layer opacity controls
- Basemap switching
- Waypoints with names, notes, colours, search, jump, edit and delete
- Measurement tool
- GPS locate and follow modes
- Depth display from supported contour/vector MBTiles
- Visual settings for cursor colour, coordinate colour, depth mode and text sizes

## Build

Requirements:

- Java 21
- Android SDK
- Included Gradle wrapper

Build a debug APK:

    ./gradlew assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Map data

SeaBathy does not include map data. MBTiles files are loaded separately from device storage.

Depth display depends on vector contour MBTiles exposing supported contour/depth attributes.

## Project status

SeaBathy is an early working release. Core offline map loading, waypoints, measurement, GPS and depth display are functional.

## License

MIT License. See `LICENSE`.

## Data attribution and safety

SeaBathy does not include official nautical charts. Any bathymetry or contour MBTiles must come from sources that allow redistribution.

See [`DATA_ATTRIBUTION.md`](DATA_ATTRIBUTION.md) before uploading or distributing map data.

SeaBathy and supplied MBTiles are not for navigation.
