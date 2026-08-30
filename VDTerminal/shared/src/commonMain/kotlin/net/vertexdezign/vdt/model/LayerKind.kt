package net.vertexdezign.vdt.model

/**
 * The ground-layer kinds this version of the app knows how to reason about — the resolved form of
 * [MapLayerLegendEntry.kind], for a `when` that wants to be exhaustive.
 *
 * **Deliberately not `@Serializable`, and deliberately not the type of the wire field.** The token
 * stays a `String?` on [MapLayerLegendEntry] and is resolved here at the point of use, for two
 * reasons that a decode test pins down rather than argues:
 *
 * 1. **An unknown token would be destroyed on the way in.** The parser runs with
 *    `coerceInputValues = true`, so kotlinx does not throw on an enumerator it doesn't know — it
 *    silently substitutes the property's default. A `kind` of `"ridge"` from a newer mod would
 *    arrive as `null`/`UNKNOWN` with the actual token gone, so it could not be logged, counted as its
 *    own bucket, or shown by name. As a string it survives intact and stays honestly unrecognised.
 * 2. **The tokens are camelCase and enum members are not.** `"needsPlowing"` matches no Kotlin
 *    member name, so an enum on the wire would need a `@SerialName` on every entry — machinery
 *    bought purely to re-derive what the string already says.
 *
 * The mod owns this vocabulary and may extend it (its growth classification covers four of
 * `FieldGroundType`'s sixteen values today, so ridge, grass and direct-sown are plausible
 * additions). This enum lists what *this build* understands; anything else is [of]-null and belongs
 * in the caller's own unknown bucket, never folded into a known kind.
 *
 * Same split the rest of the model makes for a value the mod owns — `MapPoi.type` is a passed-through
 * string for exactly this reason, as are `Task.type` and `Task.recurMode` as raw ints.
 */
enum class LayerKind(val token: String) {
  // growth plane — what state the ground is in
  CULTIVATED("cultivated"),
  STUBBLE("stubble"),
  SEEDBED("seedbed"),
  PLOWED("plowed"),
  GROWING("growing"),
  TOPPING("topping"),
  HARVEST("harvest"),
  CUT("cut"),
  WITHERED("withered"),

  // crops plane — every entry is a fruit type, told apart by MapLayerLegendEntry.label
  CROP("crop"),

  // soil plane
  WEED("weed"),
  STONE("stone"),
  NEEDS_PLOWING("needsPlowing"),
  NEEDS_LIME("needsLime"),
  MULCHED("mulched"),
  FERTILIZED("fertilized"),
  ;

  companion object {
    private val byToken = entries.associateBy { it.token }

    /** The kind [token] names, or null when this build doesn't know it (see the note above). */
    fun of(token: String?): LayerKind? = if (token == null) null else byToken[token]
  }
}
