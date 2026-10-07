# ExifTweaker Roadmap

Milestones are ordered so that each one builds on the previous: safety and infrastructure first, then
bulk editing, then the features that write to many files at once.

Legend: `[x]` done · `[ ]` planned

---

## 0.2 — Modernization ✅

- [x] Java 17, dependencies from Maven Central, runnable fat jar (`mvn package`)
- [x] Plain Swing UI instead of the IntelliJ GUI Designer form
- [x] Safe save (temp file + atomic move), works on Linux/macOS/Windows
- [x] Background loading of folders and thumbnails
- [x] Coordinate input (decimal and DMS)
- [x] Unit tests and GitHub Actions build

---

## 0.3 — Foundations ✅

Groundwork the later milestones depend on. Nothing here changes how photos are written.

- [x] **Modern look & feel with FlatLaf**: light and dark themes, following the OS setting by default and
      switchable in the menu. We stay on Swing: no JavaFX rewrite.
- [x] **Replace Bing/VirtualEarth with Esri World Imagery** as the satellite layer (Bing Maps is being retired).
      Show the attribution each tile provider requires in a corner of the map.
      Keep the tile providers in one list so adding another layer later is a single entry.
- [x] **Metadata backend abstraction**: put a `MetadataBackend` interface (read position, write position,
      read/write fields) between the UI and commons-imaging, so ExifTool can be plugged in for 0.8 without UI changes.
- [x] **Backups & undo**
  - Optional `.bak` copy of the original before the first write (setting, default on).
  - In-session undo of the last write operation, including batch writes.
- [x] **Thumbnail orientation**: apply the EXIF Orientation tag so portrait/phone photos aren't sideways.
- [x] Menu bar and a small settings dialog (theme, backups, map defaults).
- [x] Fixed along the way: map tiles of only one layer were ever cached on disk.

**Done when:** the app looks native in light and dark mode, the satellite layer works, and any write can be undone.

---

## 0.4 — Bulk location editing ✅

- [x] **Multi-select** in the file list (Shift/Ctrl, Select all, filter "without GPS only").
- [x] **Batch tagging**: apply the selected map position to all selected photos, with a progress bar and a
      summary of failures at the end.
- [x] **Copy / paste location** between photos (Ctrl+C / Ctrl+V on the file list).
- [x] **Remove GPS data** from the selected photos (privacy before sharing), with a confirmation.
- [x] **Place search**: search box backed by OpenStreetMap Nominatim.
  - Respect the usage policy: at most 1 request/second, identifying User-Agent, results cached,
    search only on Enter (no search-as-you-type).
- [x] **Tile cache limits** (#29): size limit (Settings, default 500 MB), 30-day expiry, "Clear map cache".
- [x] Undo keeps up to 2 GB of copies; larger batches can run without undo after a confirmation.

**Done when:** a folder of 500 photos can be tagged in a few clicks, and batch operations can be undone.

---

## 0.5 — GPX track geotagging ✅

- [x] **Import GPX** track files (one or more; phone/watch exports). Parsed with the JDK's XML APIs, no new dependency.
- [x] **Time matching**: match each photo's `DateTimeOriginal` against the track, interpolating between
      track points.
  - Camera **time offset** (e.g. "camera clock was 3 min 12 s behind") and **time zone** of the camera clock.
  - Helper: pick a photo of a known place or of a clock to compute the offset automatically.
  - **Maximum gap** setting: photos taken more than *N* minutes from any track point stay unmatched.
- [x] **Preview before writing**: draw the track on the map and list proposed positions (matched / unmatched)
      so the user can review and deselect before applying.
- [x] Apply through the same batch writer as 0.4 (progress, failure summary, undo).

**Done when:** a day of photos plus a GPX file from the same day can be geotagged in one pass, with a preview.

---

## 0.6 — Photo overview on the map (opt-in) ✅

Showing every photo on the map is useful, but it's easy to clutter it, so this feature is **off by default** and limited.

- [x] **Opt-in toggle** ("Show photos on map"), off by default and remembered between sessions.
- [x] **Only the opened folder**, never the whole disk; the selected photos' markers are highlighted.
- [x] **Clustering**: markers closer than ~60 px at the current zoom merge into one marker with a count badge.
      2000 photos taken at the same spot show up as **one** marker labelled "2000".
      Clicking a cluster zooms in; photos at the same spot (or at max zoom) are listed in a menu.
- [x] **Hard cap** on drawn markers (default 200, configurable), counting only markers inside the visible
      map area. When the cap is hit, the map shows "showing 200 of 1 834 — zoom in to see more".
- [x] Clicking a single marker selects that photo in the file list; the selected photo's marker is highlighted.
- [x] Clustering is recomputed only when zooming or panning stops, not on every repaint, so the map stays smooth.

**Done when:** a folder of 2000 photos from one location renders as a single, readable cluster with no lag.

---

## 0.7 — Metadata editing ✅

- [x] **Editable EXIF table** for common fields: date/time, altitude, make/model, artist, copyright,
      image description. The field is validated against its EXIF type before writing.
- [x] **Date/time shift** for the selection (e.g. +2 h for a camera left on home time); sub-seconds are kept,
      time zone offset tags (`OffsetTime*`) are left unchanged.
- [x] **Altitude** entry next to the coordinate field (and taken from GPX elevation in 0.5 when present).
- [x] Multi-photo editing: fields that differ show "(multiple values)" and are only written if changed.
- [x] **Camera direction** (#31): view cone on the map, drag its handle or type degrees; written as
      `GPSImgDirection` (true north).

**Done when:** the common fields can be changed for one or many photos, with undo.

---

## 0.8 — More formats via ExifTool ✅

- [x] **ExifTool backend** (implements the 0.3 `MetadataBackend`).
  - Used automatically when `exiftool` is on the PATH (or configured in settings); otherwise the built-in
    commons-imaging backend is used for JPEG.
  - One long-running `exiftool -stay_open` process for speed, instead of one process per file.
- [x] **HEIC/HEIF** (iPhone), **PNG**, **TIFF**, **WebP**.
- [x] **RAW formats** (CR2/CR3, NEF, ARW, DNG, ...), writing to an **XMP sidecar** by default so the RAW file
      itself is never modified.
- [x] The file list shows which files can be written with the current backend.
- [x] ExifTool is **not bundled**. When it isn't found, a banner explains which formats need it; clicking it
      links to the download page and lets you pick the executable (no need to add it to the PATH).
- [x] Thumbnails for WebP (pure-Java plugin) and embedded previews of RAW files via ExifTool.

**Done when:** a mixed folder of JPEG, HEIC and RAW files can be geotagged, with ExifTool installed.

---

## Export & playback

- [x] **Export photos as GPX**: every photo with a location and a date becomes a waypoint (time in UTC,
      elevation, description), sorted by time.
- [x] **Playback**: a separate window plays the photos in the order they were taken - the photo large, the time
      and date top left, a map bottom right following the route; play/pause, previous/next, a slider, speed and
      loop; keyboard: Space, ←/→, Home/End, Esc.
- [x] **Travel mode**: the trip as a short film of a chosen length. A marker travels the route (GPX track if
      loaded, else straight lines); arriving at a photo, it fades in and stays at least the minimum photo time;
      photos in between are skipped (a burst shows its middle photo). Long stops within a short distance (e.g.
      nights) are squeezed with a "+23 h" caption; travel is shown on a full-screen map, each journey at most
      a set time (default 8 s) so long drives don't eat the film.
- [x] **Time zone of the clock** in both windows: the camera's clock (default) or any time zone, with its UTC
      offset shown next to the date.
- [x] **Export as a video file** (#32): both windows export an MP4 (H.264) at 720p to 4K, portrait or square,
      24-60 fps, rendered off screen (map tiles are waited for) in the background with progress and Cancel.
      Playback: seconds per photo and cross-fade. Uses FFmpeg when it's installed (not bundled, located like
      ExifTool), else the pure-Java JCodec encoder (slower, larger files).

---

## 1.0 — Release

- [x] Native installers via `jpackage` (Windows `.msi`, macOS `.dmg`, Linux `.deb`) built in GitHub Actions
      and attached to GitHub Releases (#24). They bundle a trimmed Java runtime, so users don't need Java.
- [x] Rename the Maven artifact from `GPSEditor` to `exiftweaker` (#25); MIT licence; an application icon.
- [x] User documentation in the README with screenshots: downloads, a walkthrough (single, batch and GPX tagging,
      metadata, playback, export), formats and keyboard shortcuts (#26).
- [x] Integration tests on sample photos from 16 cameras and phones, 6 RAW formats, HEIC, PNG and TIFF (#27):
      the location reads back, RAW files stay untouched, and every other tag (maker notes included) is unchanged.

---

## Open questions

- **Esri terms**: Esri World Imagery is free with attribution for non-commercial use; check the terms again
  before the 1.0 release, and keep OSM as the default layer.
- ~~**ExifTool distribution**~~: decided in 0.8 - not bundled; the app points users to exiftool.org and lets
  them pick the executable.
- ~~**Java version**~~: decided for 1.0 - the code stays on Java 17 (so the jar runs on 17+), and the installers
  bundle a trimmed Java 21 runtime.
