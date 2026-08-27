package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of ONE ground-layer raster file — the mod writes `mapLayers/<id>.json` per plane
 * (crops planted, growth state, soil condition, and whatever else the map offers), each on its own
 * sweep cadence; see the mod's `src/collect/MapLayersExporter.lua`. The planes are colored and
 * classified to match the in-game map's own overlay exactly.
 *
 * Each file repeats the grid geometry rather than referring to [MapLayersCatalog], because the
 * server parses and renders it on its own: a raster whose cell size lived in another file would be
 * undecodable whenever the two disagreed.
 *
 * [gridSize] cells span [terrainSize] meters in both axes, same world origin (terrain center) as
 * [MapData]'s coordinates. Each [rows] entry is a right-trimmed hex string, 2 chars per cell — see
 * [decodeCells].
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults.
 */
@Serializable
data class MapLayerData(
  val version: String = "",
  val terrainSize: Float = 0f,
  val gridSize: Int = 0,
  /** "crops", "growth", "soil", … — also the `/api/map-layer/{id}` path segment. */
  val id: String = "",
  /** Values actually seen in [rows] during the sweep that produced this plane, sorted by [MapLayerLegendEntry.v]. */
  val legend: List<MapLayerLegendEntry> = emptyList(),
  /** One right-trimmed hex string per grid row (2 chars/cell); an all-zero row is `""`. */
  val rows: List<String> = emptyList(),
) {
  /**
   * Opaque content version of THIS plane's raster — see [computeContentVersion] for what goes into
   * it and why.
   *
   * Per plane, not per snapshot: the app displays one overlay at a time and refetches when its
   * version changes, so a version covering every plane made it refetch a megabyte of identical
   * raster whenever some other plane moved.
   *
   * Memoized, because it is asked for far more often than it changes: every connected WebSocket
   * session computes it once per sweep to build its [MapLayersInfo], and the `/api/map-layer` route
   * computes it again on every PNG request. Each computation walks every character of every row —
   * hundreds of kilobytes on a 512² grid — for an answer that is fixed the moment this instance is
   * parsed. Delegated (not a constructor property), so it stays out of the serialized form and out
   * of `equals`/`hashCode`.
   */
  val contentVersion: String by lazy { computeContentVersion() }

  /**
   * Decode [rows] into a flat `gridSize * gridSize` array of cell values (row-major, 0..255 each).
   * A short or entirely missing row zero-pads; a malformed byte pair decodes as 0, and a [gridSize]
   * outside `1..`[MAX_GRID_SIZE] decodes as empty — junk degrades to blank rather than throwing.
   */
  fun decodeCells(gridSize: Int): IntArray {
    // Guard before allocating: gridSize comes from the file, and a corrupt one is either negative
    // (gridSize * gridSize is then positive again, so the loops would run off the array) or large
    // enough that the product silently overflows Int into a bogus size — or doesn't, and asks for
    // tens of gigabytes. Blank is the documented answer for junk input.
    if (gridSize <= 0 || gridSize > MAX_GRID_SIZE) return IntArray(0)
    val cells = IntArray(gridSize * gridSize)
    for (row in 0 until gridSize) {
      val hex = rows.getOrNull(row) ?: continue
      val cellCount = minOf(gridSize, hex.length / 2)
      for (col in 0 until cellCount) {
        // Both nibbles parsed as digits rather than substring().toIntOrNull(16): that accepts a sign,
        // so a "-1" pair decoded to -1 and escaped the documented 0..255. Also saves a String per
        // cell — a 512² grid ran that a quarter of a million times per render.
        val high = hex[col * 2].digitToIntOrNull(16)
        val low = hex[col * 2 + 1].digitToIntOrNull(16)
        cells[row * gridSize + col] = if (high == null || low == null) 0 else high * 16 + low
      }
    }
    return cells
  }

  companion object {
    /**
     * Largest grid the decoder will allocate for. The mod samples at the game's own overlay
     * resolution (512), so this is generous headroom for a future bump while still refusing a
     * corrupt size that would otherwise mean a multi-gigabyte array.
     */
    const val MAX_GRID_SIZE = 2048
  }
}

/**
 * The one ground layer the **server** owns rather than the mod.
 *
 * Coverage is accumulated from the telemetry the server already receives — where the rig's work areas
 * have actually been — because nothing in the game's ground layers records it: a tedder spreads a
 * windrow and leaves no trace any of the mod's planes can sample. Deriving it server-side costs the
 * game nothing, which matters when `Json.lua` is already the mod's hot spot during active work.
 *
 * It reaches the app through exactly the same catalogue, legend, version and PNG path as the mod's own
 * planes, so the id has to be a name the mod will never use: [MapLayersCatalog] is the mod's statement
 * of what the *map* offers, and this one is never in it. That is also why it must be stripped out of
 * the subscription the server hands back to the mod — asked to sweep a plane it has never heard of,
 * the mod could never report it active, and the reconcile loop would restate the command forever.
 */
const val COVERAGE_LAYER_ID = "coverage"

/**
 * The mod's two ground-state planes, named here because more than one consumer joins against them by
 * id — the field histogram counts both, and the app subscribes to both to make the mod sweep them.
 *
 * `growth` says what stage the ground is at (cultivated, growing, ready, cut, withered); `soil` says
 * what condition it is in (weeds, stones, needs plowing, needs lime, fertilized). The mod owns the
 * ids; these constants are just the two this build reasons about by name rather than offers blindly.
 */
const val GROWTH_LAYER_ID = "growth"

/** See [GROWTH_LAYER_ID]. */
const val SOIL_LAYER_ID = "soil"

/**
 * One value a plane's raster carries, and how to read it.
 *
 * [v] is the mod's own wire vocabulary — a private enumeration it may renumber — and [label] is
 * localized to whatever language the game runs in, so **neither is a safe thing to branch on**.
 * [kind] is: a stable token naming what the value *means*, emitted by the mod so a consumer can
 * group cells by meaning without either hardcoding the mod's numbers or matching on translated text.
 *
 * Deliberately coarser than [v]: every step of the growth plane's growing gradient is `growing`,
 * every fertilizer level is `fertilized`, every weed severity is `weed`. Read [v] when the finer
 * detail is wanted, [kind] when the question is "what is on this ground".
 */
@Serializable
data class MapLayerLegendEntry(
  val v: Int = 0,
  val label: String = "",
  /** `#rrggbb`; null when the mod couldn't resolve a color for this value. */
  val color: String? = null,
  /**
   * What this value means, as a stable token — `growing`, `harvest`, `cut`, `withered`, `plowed`,
   * `cultivated`, `stubble`, `seedbed`, `topping` on the growth plane; `crop` on the crops plane;
   * `weed`, `stone`, `needsPlowing`, `needsLime`, `fertilized` on the soil plane.
   *
   * Null on a plane the mod keeps no vocabulary for — every Precision Farming plane, where a value
   * is a measurement (a pH, kg N/ha, a yield potential) whose meaning is the number itself — and on
   * a value the mod produced but could not name. Null is therefore "no grouping available", never
   * "nothing here"; treat it as its own bucket rather than folding it into a known one.
   *
   * Also null for a plane written by a mod older than `mapLayers` version 3, which predates this
   * key. A consumer that needs it must cope with a whole legend of nulls, not just one.
   *
   * A string rather than a [LayerKind] on purpose — see that enum for why, and use [knownKind] to
   * branch on it.
   */
  val kind: String? = null,
) {
  /**
   * [kind] resolved to the vocabulary this build knows, or null when it names something newer (or
   * nothing at all). Computed, so it stays out of the serialized form and out of `equals`.
   *
   * Branch on this; group and display by [kind]. That split is what keeps a token from a newer mod
   * counted and named rather than quietly merged into a kind it isn't.
   */
  val knownKind: LayerKind? get() = LayerKind.of(kind)
}

/**
 * Typed model of `mapLayers/index.json`: which raster planes this map offers, and the grid geometry
 * they share.
 *
 * Written by the mod **without sampling anything**, which is what makes it the catalogue rather than
 * a summary of the rasters: the mod only sweeps planes something is subscribed to, so a plane is
 * normally listed here before (and while) it has no file of its own. The app offers exactly these,
 * and selecting one is what causes it to be swept.
 */
@Serializable
data class MapLayersCatalog(
  val version: String = "",
  val terrainSize: Float = 0f,
  val gridSize: Int = 0,
  val layers: List<MapLayerCatalogEntry> = emptyList(),
)

@Serializable
data class MapLayerCatalogEntry(
  val id: String = "",
  /**
   * Display name, taken from the game's own map overlay selector (so it is localized, and matches
   * what the player sees in-game). Present so the app can name a plane it has never heard of.
   */
  val label: String = "",
  /**
   * Whether the mod is currently sweeping this plane — i.e. whether it believes something is
   * subscribed to it.
   *
   * The command channel that carries subscriptions is one-way and lossy by design: the mod deletes
   * `commands.xml` at every map load, so a subscription sent before this game session existed was
   * thrown away unread. Reporting what the mod actually has makes that recoverable — the server
   * compares this against the union it wants and restates the command when they disagree, instead
   * of assuming an edge landed. See `MapLayerSubscriptions.reconcile`.
   */
  val active: Boolean = false,
)

/**
 * Slim broadcast variant: what the map panel needs to offer the layers and draw their legends, never
 * [MapLayerData.rows] — the raster itself is fetched separately as a PNG
 * (`GET /api/map-layer/{id}?v={version}`), never over the WebSocket, since a 512x512 grid per plane
 * is far too heavy to push on every sweep.
 *
 * Built from the catalogue, so it lists every plane the map offers — including the ones with no
 * raster yet, which carry a null [MapLayerInfo.version] and simply draw nothing until selected.
 */
@Serializable
data class MapLayersInfo(val layers: List<MapLayerInfo> = emptyList()) {
  companion object {
    /**
     * Combine the catalogue with whichever plane files have been parsed so far, keyed by layer id.
     * A plane in [rasters] that the catalogue doesn't list is ignored: the catalogue is the mod's
     * statement of what this map has, and an unlisted file is last session's leftover.
     */
    fun from(catalog: MapLayersCatalog, rasters: Map<String, MapLayerData>): MapLayersInfo = MapLayersInfo(
      layers =
      catalog.layers.map { entry ->
        val raster = rasters[entry.id]
        MapLayerInfo(
          id = entry.id,
          label = entry.label,
          version = raster?.contentVersion,
          legend = raster?.legend ?: emptyList(),
        )
      },
    )
  }
}

/**
 * One offered plane. [version] is content-derived from that plane's **full** data including
 * [MapLayerData.rows], so a changed cell always changes the version even though the cells themselves
 * never cross the wire — that's what tells the app to refetch the PNG. Deliberately content-derived
 * rather than a counter: a sweep that re-samples an unchanged map produces the same version, so the
 * app keeps the PNG it already has instead of refetching hundreds of kilobytes of identical raster.
 *
 * Null [version] means "offered, but not swept yet" — nothing to fetch, and nothing to draw.
 *
 * The version is an opaque string — see [MapLayerData.contentVersion] for why it isn't `hashCode()`.
 */
@Serializable
data class MapLayerInfo(
  val id: String = "",
  val label: String = "",
  val version: String? = null,
  val legend: List<MapLayerLegendEntry> = emptyList(),
)

/**
 * Opaque content version of one plane's raster: 64-bit FNV-1a over everything that affects what is
 * derived from this plane — the rendered PNG, and anything else keyed on this version — as hex. Call [MapLayerData.contentVersion] rather than this — it memoizes the
 * result, and this walk is not cheap.
 *
 * Not `hashCode()`: 32 bits is small enough that two different rasters can collide, and the PNG for
 * a version is served under `Cache-Control: immutable` for a year — a collision would pin the wrong
 * overlay in the browser's cache with no way to invalidate it. 64 bits makes that vanishingly
 * unlikely, at about the cost the data class's own `hashCode()` already paid (both walk the rows).
 */
private fun MapLayerData.computeContentVersion(): String {
  var hash = 0xcbf29ce484222325UL // FNV-1a 64-bit offset basis
  val prime = 0x100000001b3UL

  // Each value is terminated by a marker, so ("ab", "c") and ("a", "bc") don't hash alike, and null
  // gets a different marker than "" so the two stay distinguishable.
  fun mix(s: String?) {
    if (s == null) {
      hash = (hash xor 0xfeUL) * prime
      return
    }
    for (c in s) {
      hash = (hash xor (c.code.toULong() and 0xffffUL)) * prime
    }
    hash = (hash xor 0xffUL) * prime
  }
  mix(version)
  mix(terrainSize.toRawBits().toString())
  mix(gridSize.toString())
  mix(id)
  // Every list is length-prefixed. Terminating each string is not enough on its own: without the
  // counts, the flat sequence of values carries no list boundaries, so a legend entry's three values
  // could line up with three rows of another layout and hash identically. Unreachable from anything
  // the mod emits (rows are hex, labels are not), but this is the input to an immutable-for-a-year
  // cache key, and a length-prefixed encoding is unambiguous by construction rather than by argument.
  mix(legend.size.toString())
  for (entry in legend) {
    mix(entry.v.toString())
    mix(entry.label)
    mix(entry.color)
    // Mixed in even though it cannot change a pixel: this version is the cache key for everything
    // derived from the plane, not only for the PNG, and a consumer that groups cells by
    // MapLayerLegendEntry.kind would keep a stale grouping across a legend that changed only there.
    mix(entry.kind)
  }
  mix(rows.size.toString())
  for (row in rows) {
    mix(row)
  }
  return hash.toString(16)
}
