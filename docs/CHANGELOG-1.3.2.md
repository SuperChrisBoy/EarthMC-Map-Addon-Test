# 1.3.2

**Town rendering overhaul**
- **Smooth, squaremap-style town outlines.** Borders are now simplified the same way map.earthmc.net does it, so they read as clean diagonals instead of chunk staircases, and neighbouring towns share one flush line instead of two competing ones.
- **Crisper lines at every zoom.** Outlines are baked at your display's real pixel density and drawn as close to 1:1 as possible, so they stay sharp instead of soft. Line thickness now adapts to the zoom level rather than turning fat when you zoom in.
- **Outlines stay on screen while you zoom**, including all the way out to the whole map, instead of dropping back to the old blocky lines mid-gesture.
- **Nation colours inside, outline colours on the edge.** Towns carry separate fill and border colours, matching how the website colours nation schemes.
- Favourited towns are drawn exactly like every other town - same line weight, same opacity, at every zoom. Only the colour differs, so they no longer shout over their neighbours or shift brightness as you zoom.
- New **Far Zoom Town Dots** setting (off by default): when on, very small towns collapse to a dot at far zoom. Leave it off to keep real outlines until towns are literally sub-pixel.
- Fixed towns briefly rendering fat and blocky when zooming quickly.

**Chunk counter**
- The counter now uses the same smooth merged outline as town borders.
- New **Fill** toggle: any area fully enclosed by your selected chunks is treated as selected and counted too, so outlining a region fills it in. Switching Fill back off keeps the filled chunks rather than reverting them.

**Fixes**
- **Overlays now show on other servers when "EarthMC Only" is off.** The overlay was hidden in any dimension not registered as `minecraft:overworld`, which on many servers (hubs, skyblock, minigames) meant only the on-map buttons appeared. The overworld restriction now applies only when you are actually connected to EarthMC; behaviour on EarthMC, including the "EarthMC Map In Nether" setting, is unchanged.

Available for Minecraft **1.21.11**, **26.1.x**, and **26.2**.
