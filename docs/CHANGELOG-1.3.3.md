# 1.3.3

**Expanded information panels**
- Right-clicking a town now offers **Expand** in place of the Discord button, opening a full panel with everything EarthMC knows about that town: mayor, founder, nation, board, size, bank, nation bonus, spawn, every status and permission flag, and collapsible lists of residents, trusted, outlaws, quarters, warps and ranks. The Discord link moved inside the panel, alongside the wiki link.
- **Nation and player panels too**, reachable the same way from the search panel, or by clicking any name.
- **Everything is clickable.** Follow a town to its mayor, to their nation, to its capital, to that town's residents. Back walks the trail in reverse; clicking outside the panel leaves entirely.
- **Overclaim forecast.** Towns show when they become overclaimable, worked out from residents' 42-day purge dates and the nation bonus, whichever lands first. A town well inside its limit today can still be overclaimable in a few weeks purely because inactive residents drop off.
- **Nation bonus forecast.** Nations show the next bonus step and when it falls.
- Panels honour the Dark Buttons setting.

**Settings**
- Reorganised into categories down the left instead of one long scrolling list, so nothing is buried.
- The panel is more translucent, hovering a row highlights it, and 16 of the less obvious settings now explain themselves in a strip at the bottom.
- New **Reset All**, which resets preferences without touching favourites or chunk-counter selections.

**Fixes**
- **PvP always read as false.** Town PvP was parsed from a field the API does not return, so every town reported PvP off and the PvP map mode never highlighted anything. Explosion, fire and mob flags were never read at all.
- **Pacts showed no dates.** Nation pact timestamps were read from the wrong place and came back empty; they now show when a pact started and whether it expires.
- **Players on the map showed no online info.** The presence line was skipped for anyone currently visible — the one case where the answer is "right now".
- Map-mode highlights now use the same outline style as every other town instead of reverting to blocky borders.

Available for Minecraft **1.21.11**, **26.1.x**, and **26.2**.
