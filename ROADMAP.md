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

## 0.4 — Bulk location editing

- [ ] **Multi-select** in the file list (Shift/Ctrl, Select all, filter "without GPS only").
- [ ] **Batch tagging**: apply the selected map position to all selected photos, with a progress bar and a
      summary of failures at the end.
- [ ] **Copy / paste location** between photos (Ctrl+C / Ctrl+V on the file list).
- [ ] **Remove GPS data** from the selected photos (privacy before sharing), with a confirmation.
- [ ] **Place search**: search box backed by OpenStreetMap Nominatim.
  - Respect the usage policy: at most 1 request/second, identifying User-Agent, results cached,
    search only on Enter (no search-as-you-type).

**Done when:** a folder of 500 photos can be tagged in a few clicks, and every batch operation can be undone.

---

## 0.5 — GPX track geotagging

- [ ] **Import GPX** track files (one or more; phone/watch exports). Parsed with the JDK's XML APIs, no new dependency.
- [ ] **Time matching**: match each photo's `DateTimeOriginal` against the track, interpolating between
      track points.
  - Camera **time offset** (e.g. "camera clock was 3 min 12 s behind") and **time zone** of the camera clock.
  - Helper: pick a photo of a known place or of a clock to compute the offset automatically.
  - **Maximum gap** setting: photos taken more than *N* minutes from any track point stay unmatched.
- [ ] **Preview before writing**: draw the track on the map and list proposed positions (matched / unmatched)
      so the user can review and deselect before applying.
- [ ] Apply through the same batch writer as 0.4 (progress, failure summary, undo).

**Done when:** a day of photos plus a GPX file from the same day can be geotagged in one pass, with a preview.

---

## 0.6 — Photo overview on the map (opt-in)

Showing every photo on the map is useful, but it's easy to clutter it, so this feature is **off by default** and limited.

- [ ] **Opt-in toggle** ("Show photos on map"), off by default and remembered between sessions.
- [ ] **Only the current folder / selection**, never the whole disk.
- [ ] **Clustering**: markers closer than ~60 px at the current zoom merge into one marker with a count badge.
      2000 photos taken at the same spot show up as **one** marker labelled "2000".
      Clicking a cluster zooms in; at max zoom it lists its photos.
- [ ] **Hard cap** on drawn markers (default 200, configurable), counting only markers inside the visible
      map area. When the cap is hit, the map shows "showing 200 of 1 834 — zoom in to see more".
- [ ] Clicking a single marker selects that photo in the file list; the selected photo's marker is highlighted.
- [ ] Clustering is recomputed only when zooming or panning stops, not on every repaint, so the map stays smooth.

**Done when:** a folder of 2000 photos from one location renders as a single, readable cluster with no lag.

---

## 0.7 — Metadata editing

- [ ] **Editable EXIF table** for common fields: date/time, altitude, make/model, artist, copyright,
      image description. The field is validated against its EXIF type before writing.
- [ ] **Date/time shift** for the selection (e.g. +2 h for a camera left on home time), keeping sub-second
      and offset tags consistent.
- [ ] **Altitude** entry next to the coordinate field (and taken from GPX elevation in 0.5 when present).
- [ ] Multi-photo editing: fields that differ show "(multiple values)" and are only written if changed.

**Done when:** the common fields can be changed for one or many photos, with undo.

---

## 0.8 — More formats via ExifTool

- [ ] **ExifTool backend** (implements the 0.3 `MetadataBackend`).
  - Used automatically when `exiftool` is on the PATH (or configured in settings); otherwise the built-in
    commons-imaging backend is used for JPEG.
  - One long-running `exiftool -stay_open` process for speed, instead of one process per file.
- [ ] **HEIC/HEIF** (iPhone), **PNG**, **TIFF**, **WebP**.
- [ ] **RAW formats** (CR2/CR3, NEF, ARW, DNG, ...), writing to an **XMP sidecar** by default so the RAW file
      itself is never modified.
- [ ] The file list shows which files can be written with the current backend.

**Done when:** a mixed folder of JPEG, HEIC and RAW files can be geotagged, with ExifTool installed.

---

## 1.0 — Release

- [ ] Native installers via `jpackage` (Windows `.msi`, macOS `.dmg`, Linux `.deb`) built in GitHub Actions
      and attached to GitHub Releases.
- [ ] Rename the Maven artifact from `GPSEditor` to `exiftweaker`.
- [ ] User documentation in the README with screenshots; keyboard shortcuts list.
- [ ] Integration tests on sample photos from several camera brands.

---

## Open questions

- **Esri terms**: Esri World Imagery is free with attribution for non-commercial use; check the terms again
  before the 1.0 release, and keep OSM as the default layer.
- **ExifTool distribution**: rely on the user's installation, or bundle it in the installers
  (it needs Perl on macOS/Linux, and the Windows build is a standalone exe)?
- **Java version**: stay on 17, or move to 21 once `jpackage` installers bundle their own runtime anyway?
