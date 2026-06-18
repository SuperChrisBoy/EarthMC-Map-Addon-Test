# EarthMC Map Addon

A client-side EarthMC map extension for **Xaero's World Map** and **Xaero's Minimap**. It overlays EarthMC Towny data directly onto your in-game maps so you can read towns, nations, players, borders, and claims without leaving Minecraft.

## Highlights

- **Town claims on the World Map** with configurable border thickness and fill opacity, plus an optional Squaremap background layer.
- **Info panel under the minimap** — your current town & nation, nearby players with live distance (players who leave view linger in red at their last-known spot), and the nearest town when you're in the wilderness. Every line is individually toggleable and stacks below Xaero's own info without overlapping.
- **Right-click town info** — mayor, nation, residents (including how many are inactive), gold, claimed chunk count, open/public status, and founded date.
- **Search** for towns, nations, and players from the map UI, with rich detail panels (nation bonus, king/capital, outlaws, enemies; player online/last-seen/town/nation/role).
- **Online player dots** on both maps, coloured by relation (your town, your nation, others), with configurable name and label ranges.
- **Minimap overlays** — town borders, town names, player dots, chunk-grid options, nation capital stars, and a wilderness player alert for nearby outsiders. Direction markers match your chosen Xaero frame colour.
- **Persistent multi-group chunk counter** with right-click drag selection for planning claims or regions — now clipped cleanly to the circular minimap.
- **Town status map modes** — public towns, open towns, towns for sale, and towns without nations.
- **Real-world country/state border overlays** and optional RGB/custom-colour highlighting.
- **Favourite towns**, Xaero **waypoint routes** to map targets, and optional hiding of the minimap in the Nether.
- **Searchable, sectioned settings screen** (via Mod Menu) with per-row Reset and right-click-to-go-back.
- **EarthMC-only mode** so the addon only activates on EarthMC, plus `/townymap` commands for quick toggles and manual claim refresh.

## Requirements

- Fabric Loader + Fabric API
- Xaero's Minimap and/or Xaero's World Map
- Mod Menu (optional, for the in-game settings screen)

## Note on town claim limits

The town **"X / max claims"** display and the **Overclaim** map mode are temporarily disabled. EarthMC's API currently counts inactive residents toward a town's claim limit, so the calculated maximum is wrong. They will return once the API exposes active-resident counts (expected in ~2 weeks). The popup shows the actual claimed chunk count in the meantime.
