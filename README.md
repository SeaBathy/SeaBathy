# SeaBathy

SeaBathy is a native Kotlin Android app for viewing offline MBTiles bathymetry and marine map layers.

## Features

- Offline MBTiles loading
- MapLibre-based map display
- Layer opacity controls
- Basemap switching
- Waypoints with names, notes, colours, search, jump, edit and delete
- Measurement tool
- GPS locate and follow modes
- Depth display from supported contour/vector MBTiles
- Visual settings for cursor, coordinate colour, coordinate size, and depth display

## Build

Requirements:

- Java 21
- Android SDK
- Android Gradle Plugin / Gradle wrapper included in this repository

Build a debug APK:

    ./gradlew assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## MBTiles

SeaBathy does not bundle map data. Load MBTiles files from device storage.

Supported MBTiles behaviour depends on the tile type and metadata. Raster MBTiles display as map overlays. Vector contour MBTiles can provide depth display when contour attributes are available.

## Current status

SeaBathy is currently a working early release/prototype. The app is suitable for testing offline MBTiles viewing, waypoints, measurement, and GPS workflows.

## License

MIT License. See `LICENSE`.
