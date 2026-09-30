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
 - Select the image you'd like to edit
 - Use the map to find the desired location and rightclick on it, or type a coordinate
   (`47.4979;19.0402` or `47°29'52"N 19°2'24"E`) into the field below the map and press Go!
 - Press the save button (or Ctrl+S / Cmd+S)
 - Changed your mind? Edit → Undo (Ctrl+Z / Cmd+Z) restores the photo
 > If a picture already has GPS data, it'll have a green color in the filelist

 > You can choose between two map layers: OpenStreetMap and satellite imagery (Esri)

 > Before a photo is changed for the first time, a copy of the original is kept next to it as `<name>.bak`.
 > This can be turned off in Settings, where you can also pick a light or dark theme.

# Planned features
See the [roadmap](ROADMAP.md): batch tagging, GPX track geotagging, place search, an opt-in photo overview
on the map, EXIF editing, and HEIC/RAW support through ExifTool.

# Used libraries
 - [Apache Commons-Imaging](https://commons.apache.org/proper/commons-imaging/) (from Maven Central)
 - [JXMapViewer2](https://github.com/msteiger/jxmapviewer2) from @msteiger
 - [FlatLaf](https://www.formdev.com/flatlaf/)
