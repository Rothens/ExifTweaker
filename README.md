# ExifTweaker

ExifTweaker is a small java desktop application, which can set the GPS position in the EXIF data of pictures.

![Image of the app](http://i.imgur.com/BbX5vTZ.png)

# Building & running

Requires Java 17+ and Maven.

```
mvn package
java -jar target/GPSEditor-*-all.jar
```

# Usage

 - Open a folder using the top part of the window
 - Select one or more photos (Shift/Ctrl-click; tick *Only without location* to hide the ones already tagged)
 - Find the place: search for it above the map (e.g. `Eiffel Tower`, then Enter), or pan and zoom
 - Right-click the exact spot on the map, or type a coordinate (`47.4979;19.0402` or `47°29'52"N 19°2'24"E`)
   into the field below the map and press Go!
 - Press Save (Ctrl+S / Cmd+S): the location is written to all selected photos
 - Changed your mind? Edit → Undo (Ctrl+Z / Cmd+Z) restores the photos - a whole batch at once

 > Photos with a location are green in the file list, the others are red

 > **Geotag from a GPX track** (File → Geotag from GPX, Ctrl+G): load the track your phone, watch or GPS logger
 > recorded, pick the time zone your camera's clock was set to, and review where each photo lands before applying.
 > If the camera's clock was off, enter by how much - or let ExifTweaker work it out from a photo of a clock, or
 > from a photo whose location you right-click on the map. Altitude is taken from the track too.

 > **See your photos on the map** with View → Show photos on map (off by default). Photos close to each other are
 > grouped into one marker with a count; click a group to zoom in, or a single photo to select it. To keep the map
 > readable, at most 200 markers are drawn (adjustable in Settings) - zoom in to see the rest.

 > **Edit metadata** in the table next to the thumbnail (double-click a value): date taken, camera make and model,
 > artist, copyright, description, altitude and camera direction - for one photo or all selected ones. Edit → Shift
 > date/time (Ctrl+T) fixes a camera that was set to the wrong time or time zone.

 > **Camera direction**: drag the handle next to a photo's pin to show which way the camera pointed, or type the
 > degrees next to the coordinate field. The altitude field there is written along with the location on Save.

 > **HEIC, PNG, TIFF, WebP and RAW files** (CR2, CR3, NEF, ARW, DNG, ...) need the free
 > [ExifTool](https://exiftool.org/). If it isn't found, a banner at the top links to the download and lets you pick
 > the executable. RAW files are never modified - their metadata is written to an `.xmp` sidecar next to them,
 > which Lightroom, darktable and most photo tools read.

 > **Export photos as GPX** (File menu, Ctrl+E) writes the photos that have a location and a date as waypoints,
 > e.g. to show them in Google Earth or another map app.

 > **Play photos** (View menu, F5) shows the photos in the order they were taken, with the time and a small map
 > following your route. Space plays and pauses, the arrow keys step, Esc closes.

 > **Travel mode** (View menu, Shift+F5) plays the trip as a short film: pick its length and how long each photo
 > stays at least. A marker travels your route (your GPX track if you loaded one) and each photo fades in when the
 > marker gets there. Nights and other long stops are skipped over quickly; longer journeys play on a full-screen map,
 > at most 8 seconds each by default ("Travel at most"), so the photos get the rest of the film.

 > Both windows show the times as the camera's clock recorded them, or in a time zone you pick ("Times in", e.g.
 > the local time of a trip when the camera stayed on home time).

 > Copy a photo's location with Ctrl+C on the file list and paste it onto others with Ctrl+V, then Save.
 > Edit → Remove location strips the GPS data, e.g. before sharing photos.

 > You can choose between two map layers: OpenStreetMap and satellite imagery (Esri)

 > Before a photo is changed for the first time, a copy of the original is kept next to it as `<name>.bak`.
 > This can be turned off in Settings, where you can also pick a light or dark theme and limit the map cache.

# Planned features
See the [roadmap](ROADMAP.md): batch tagging, GPX track geotagging, place search, an opt-in photo overview
on the map, EXIF editing, and HEIC/RAW support through ExifTool.

# Used libraries
 - [Apache Commons-Imaging](https://commons.apache.org/proper/commons-imaging/) (from Maven Central)
 - [JXMapViewer2](https://github.com/msteiger/jxmapviewer2) from @msteiger
 - [FlatLaf](https://www.formdev.com/flatlaf/)
 - [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (WebP thumbnails)
 - Optional, not bundled: [ExifTool](https://exiftool.org/) by Phil Harvey
