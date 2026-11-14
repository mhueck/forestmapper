# GeoPDF Mapper (Android + Android Auto)

An Android and **Android Auto** map navigation app that renders large georeferenced PDF maps (**GeoPDFs**), tracks your real-time GPS location, shows your movement direction with a rotating navigation arrow, and supports interactive panning, zooming, and continuous GPS re-centering.

---

## Key Features

1. **GeoPDF Coordinate & Projection Engine (`com.mapper.geopdf.geo`)**
   - Streams and parses ISO 32000 `/VP` (`/Measure` -> `/Subtype /GEO`) dictionaries without loading 50–100+ MB PDFs into memory.
   - Automatically separates the primary map viewport from small locator inset maps (such as the 288×288 pt state inset in the USFS sample maps).
   - Supports **North America Equidistant Conic**, **Lambert Conformal Conic**, **Transverse Mercator / UTM**, **Web Mercator**, and **Geographic** WKT definitions, including conic convergence angle correction for the movement direction arrow.

2. **Large-File Multi-Resolution Tiled PDF Renderer (`com.mapper.geopdf.pdf.TiledPdfEngine`)**
   - Handles 50–100+ MB PDF maps (such as `Coronado National Forest.pdf`, `Prescott National Forest.pdf`, and `Tonto National Forest.pdf`) without `OutOfMemoryError`.
   - Renders a full-page low-resolution overview backdrop immediately and streams high-DPI `512×512` vector tiles on demand for the currently visible screen area into a 48 MB `LruCache`.

3. **Real-Time GPS & Movement Direction Arrow (`com.mapper.geopdf.location.LocationTracker` & `MapOverlayRenderer`)**
   - Tracks high-accuracy GPS updates and computes movement bearing from Doppler GPS velocity (`location.bearing`) or consecutive fix trajectories.
   - Draws an accuracy radius halo, velocity vector ray, and high-contrast directional navigation arrow oriented in the exact direction of movement on the map.
   - Includes a built-in **Sim GPS** mode to simulate driving across the active map when testing indoors or on the Android Auto Desktop Head Unit (DHU).

4. **Re-center & Continuous Follow Mode (`com.mapper.geopdf.state.MapController`)**
   - When **Centered / Re-center** is active, the map automatically moves to keep your local GPS position centered on screen.
   - Dragging/panning the map automatically switches to free-pan mode; tapping **Re-center** locks the map back onto your live GPS position (and automatically switches to whichever loaded GeoPDF covers your coordinates).

5. **Full Android Auto Support (`com.mapper.geopdf.auto`)**
   - Implements `CarAppService`, `Session`, `SurfaceCallback`, and `NavigationTemplate`.
   - Renders the live GeoPDF tiles and GPS movement arrow directly onto the car head unit's `Surface`.
   - Supports touchscreen pinch-to-zoom and drag panning on the car display, rotary controller Pan Mode, plus on-screen buttons for **Zoom In (`+`)**, **Zoom Out (`-`)**, **Re-center**, and **Map Selection**.

---

## Loading the Sample Maps (`maps/*.pdf`)

You can load the three sample USFS GeoPDF maps (`Coronado National Forest.pdf`, `Prescott National Forest.pdf`, `Tonto National Forest.pdf`) in either of two ways:

### Option 1: Push via ADB to the Device / Emulator
```bash
adb shell mkdir -p /sdcard/Maps
adb push "maps/Coronado National Forest.pdf" /sdcard/Maps/
adb push "maps/Prescott National Forest.pdf" /sdcard/Maps/
adb push "maps/Tonto National Forest.pdf" /sdcard/Maps/
```
The app automatically scans `/sdcard/Maps`, `/sdcard/Download`, and `/Android/data/net.mhu.mapper.geopdf/files/maps` on startup.

### Option 2: Use the In-App "Import PDF" Button
Tap **Import PDF** in the top bar on your phone to select any `.pdf` file from device storage or cloud providers. The file is copied into the app's persistent maps library so it is immediately available in both the phone UI and Android Auto.
