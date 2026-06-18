# 1.2.1

**New**
- **Info panel under the minimap** — shows your current town & nation, nearby players with live distance (a player who leaves view stays listed in red at their last-known spot for a short while), and the nearest town when you're in the wilderness. Each line can be toggled on/off. It anchors below Xaero's own info block so it never overlaps, no matter how many Xaero info lines you have enabled.
- **Redesigned settings screen** — searchable and sectioned (General / Minimap / World Map / Players / Info Display / Advanced), with a per-row **Reset** button and **right-click to cycle a setting backward**.

**Improved**
- Minimap direction markers (N/E/S/W) now match the minimap frame colour you choose in Xaero's settings.
- The chunk counter now stays inside the circular minimap edge instead of spilling into the corners.

**Temporarily disabled (will return when EarthMC's API is updated)**
- The town **"X / max claims" ("out of")** display and the **Overclaim** map mode are turned off for now. EarthMC's API currently counts inactive residents toward a town's claim limit, so the calculated maximum is wrong. These will be re-enabled once the API exposes active-resident counts — expected in roughly **2 weeks**. The town info popup shows the actual claimed chunk count in the meantime, and all of the logic is kept in the code so it can be switched straight back on.

Available for Minecraft **1.21.11** and **26.1.x**. (26.2 support will follow once Xaero's Minimap updates for 26.2.)
