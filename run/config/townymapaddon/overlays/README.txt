Drop custom map-overlay JSON files here (any number of *.json files), then use
"Reload Overlays" in the mod settings. Routes draw as coloured lines and points
as labelled dots on the world map.

Supported shapes (all best-effort):
  - EarthMC Ice Highways Map files (github.com/xilef2211/ice-highways-map,
    e.g. aurora/highways.json)
  - Generic: { "lines": [ { "color": "rrggbb", "points": [[x,z],...] } ],
               "markers": [ { "name": "...", "x": 0, "z": 0 } ] }
  - GeoJSON FeatureCollection (LineString / MultiLineString / Point;
    coordinates read as [x, z]).
