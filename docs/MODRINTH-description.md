# EarthMC Map Addon

A client-side EarthMC map extension for **Xaero's World Map** and **Xaero's Minimap**. It overlays EarthMC Towny data directly onto your in-game maps so you can read towns, nations, players, borders, and claims without leaving Minecraft.

## World Map

- Town claims drawn as real claim shapes, with configurable border thickness, fill opacity, and smooth outlines.
- Squaremap background layer — the real map.earthmc.net tiles rendered under the overlay, with an optional darken pass.
- Nation capital stars, toggleable.
- Nation join range — circles showing where a town could join a nation (1,500 block town radius, 5,000 nation radius), with a union outline when nearby towns extend the nation's reach.
- World Map Overview — zooms out past Xaero's normal limit to see the whole world.
  - Far-zoom town dots so towns stay visible when zoomed out.
- Real-world country and state borders as an overlay.
- Custom overlays — drop GeoJSON FeatureCollections (LineString, MultiLineString, Point) into a folder the mod creates and opens for you.

## Minimap

- Town borders, town names, and player dots on the minimap.
- Info panel under the minimap: current town and nation, nearby players with live distance (players leaving view linger in red at their last-known spot), nearest town when in wilderness. Each line toggles independently and stacks below Xaero's own info without overlapping.
- Wilderness player alert for nearby outsiders.
- Optional hide-in-Nether, or an "EarthMC map in Nether" mode that scales the overworld map ×8 over Nether tiles.

## Map modes

- Six status modes: Public, Overclaim, Open, Meganations, Alliances, and Planning. The first three highlight matching towns in a colour you pick (fixed or RGB cycling); Meganations and Alliances recolour towns by bloc instead, and use the BreakTheBot Alliance API.
- Planning mode turns the search bar into a nation picker and lets you place prospective towns (T1, T2, …) with numbered map markers. Towns outside the nation's range are marked red on both the chip and the map.

## Search

- Towns, nations, players, and alliances, with detail panels — nation bonus, king/capital, outlaws, enemies, resident lists, player online/last-seen/town/nation/role.
- Filters: `nationless`, `n:germany,france` (multi-value), `r>30,<60`, `chunks>500`. Short prefixes `n:` and `r:` supported.
- Archive lookup — type a `dd/mm/yyyy` date to load historical map data from the Wayback Machine.
  - Earliest date is 17/4/2026, as that is when Nostra started.
  - The separator can be `/`, `.`, `,`, `-` or a space, leading zeroes are optional, and the year can be 2 or 4 digits (`17 4 26` works).
- Filters persist while panning; the box auto-widens.
- Favourites for towns, nations, players, and alliances — jump to a nation's capital or an online player's location.

## Players

- Online player dots on both maps coloured by relation (your town / your nation / others), real player heads, configurable head/name/label ranges, and last-seen positions.

## Info panels

- Right-click a town for mayor, nation, residents, gold, chunk count, open/public status, and founded date.
- Click on a nation star for information such as the capital, founded date, nation chunks, towns, etc.
- Click on a player head or dot to get information on a player.
- All three of the above have an extended mode with more in-depth information.

## Tools

- Chunk counter with persistent multi-group tracking and Shift+right-drag box select.
- Xaero waypoint routes to map targets.
- Map screenshots on a keybind — captures the squaremap, town, and player layers, hides mod and Xaero UI, with its own settings section (players off, nation stars on, dimmed towns hidden by default) and no chunk cursor.

## Setup

- Searchable sectioned settings screen via Mod Menu with per-row Reset and right-click-to-go-back; UI scale; dark buttons; EarthMC-only mode; keybinds for cycling borders, cycling map mode, refreshing towns, toggling the chunk counter, toggling squaremap, and map screenshot (synced with MC's controls screen); `/townymap` command with `refresh`.

## Requirements

- Fabric Loader + Fabric API
- Xaero's Minimap and/or Xaero's World Map
- Mod Menu (optional, for the in-game settings screen)
