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
