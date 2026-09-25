# Split layout — pages as nested splits instead of a cell grid

Plan to replace the page grid (`GridLayout`, 12 × 7 landscape / 6 × 12 portrait) with a **split
tree**: a page is a region that is cut, horizontally or vertically, into children at ratios, and each
leaf holds one widget. The way a tiling window manager lays out windows.

Status: **planned, nothing built**. Written 2026-09-25 against branch `ui-display-edit` @ `fd001da`
(the top of the unpushed UI-overhaul stack). It builds on that stack's on-display edit mode, so it
stacks on top of it rather than on `main`.

Decided with the user before writing this:

- **Why:** more freedom in placement, while making symmetry *easier* rather than harder. "Same size
  as that one" and "half of this space" should be a thing the structure guarantees, not something
  the user counts cells to get right.
- **Not a free-form canvas.** A canvas would need snap guides, match-size and distribute actions,
  overlap handling — all on touch, all twice (landscape + portrait) — and would still drift by a few
  dp. Rejected.
- **Splits take more than two children.** Lighting | Combine | Engine is *one* three-way split, so
  "make these three equal" is one action and the tree matches how the page reads.
- **One layout system, not pluggable providers.** Keeping the grid beside the split tree per page
  (a `PageLayout` interface, a `type` discriminator, dp floors derived to spans) is cheap to build
  but doubles every edit-mode change afterwards. Revisit only if a real use case needs both.
- The user's current Vehicle page (front column + map + rear column over a three-tile band, each
  column a slot over two shortcuts) was checked against the model and cuts cleanly — see the
  worked example below.

---

## The model

```kotlin
@Serializable
sealed interface LayoutNode

/** A region cut along [axis] into [children], each taking [Child.weight] of the space. */
@Serializable @SerialName("split")
data class Split(val axis: Axis, val children: List<Child>) : LayoutNode

@Serializable
data class Child(val weight: Float, val node: LayoutNode)

/** Axis.Row = children side by side (vertical dividers); Axis.Column = stacked. */
enum class Axis { Row, Column }

/** A placed widget. Same identity/config contract as today's LayoutCell, minus the position. */
@Serializable @SerialName("tile")
data class Tile(val instanceId: String, val widgetId: String, val config: WidgetConfig = emptyMap()) : LayoutNode

/** Space deliberately left free — the add target in edit mode. */
@Serializable @SerialName("empty")
data class Empty(val id: String) : LayoutNode
```

`Page.landscape` / `Page.portrait` become `LayoutNode`s. Everything else about a page (id, title,
icon, auto-show, the shared-instance-id-across-orientations rule, `WidgetSettings` purge) is
unchanged: `PageStore.instanceIds` walks the tree instead of `cells`.

**Canonical form**, enforced by one `normalize()` that every mutation ends in:

- a split with one child is replaced by that child;
- a child split on the **same axis** as its parent is flattened into it (weights scaled), since two
  nested row-splits draw exactly like one — and "equalize" must see all the siblings the user sees;
- weights are renormalized to sum to 1;
- a split with zero children disappears (an empty page is a single `Empty`).

Nodes are addressed by **path** (list of child indices from the root) in the pure operations; Compose
keys tiles by `instanceId` and empties by their `id`, so a tile keeps its state when the tree around it
is restructured.

### Measuring is a pure function

```kotlin
fun LayoutNode.measure(bounds: Rect, gap: Float): Map<Path, Rect>
```

Each split subtracts `gap × (n − 1)` along its axis and hands out the rest by weight. Rendering places
every leaf at its rect with absolute offsets (as `WidgetGrid` does today) rather than nesting
`Row`/`Column` with `weight` — because edit mode needs the same rects for divider hit targets, snap
lines and size floors, and a pure function is unit-testable. (The project has no Compose UI-test
dependency, so everything that can be pure should be.)

---

## Worked example: the current Vehicle page

```
Column [5/8, 3/8]
├─ Row [1/6, 2/3, 1/6]
│   ├─ Column [slot FRONT, Row [Animals, Diagnostics]]
│   ├─ map
│   └─ Column [slot REAR,  Row [Production, Storage]]
└─ Row [Lighting, Combine, Engine]
```

The bottom band's dividers don't line up with the top band's and don't have to. Front and Rear stay
the same width because they are the 1/6 ends of one split, and each shortcut pair is an exact half.

What the model **cannot** express: interlocking arrangements where no straight cut crosses the region
edge to edge, e.g. four tiles pinwheeled around a centre one. Accepted — that has to be built on
purpose and rarely looks balanced.

---

## Size floors move from cells to dp

A widget's `minColSpan`/`minRowSpan` only meant something against a known cell size, and that was
already two different sizes (≈91dp landscape, ≈56dp portrait). They become:

```kotlin
val minWidth: Dp
val minHeight: Dp
```

Initial values: the widget's current span floor measured on the **portrait** cell (56dp + 8dp gaps),
because that is the smallest each widget is already rendered at and has been made to cope with
(step 4 of the UI overhaul). Then reviewed per widget — the map and the rig slot are the ones likely
to want more.

`defaultColSpan`/`defaultRowSpan` go away: a widget added into an `Empty` takes the whole of it.

Subtree floors are derived: along a split's axis, the sum of the children's floors plus gaps; across
it, the largest child floor. `minSize(node)` is pure.

**Floors gate edits, not rendering.** A layout is ratios, so a page arranged on an 11" tablet opened
on a smaller one (or at a larger UI size — floors are dp and scale with `UiScaleStore`) can land tiles
below their floor. Those render anyway, as they do in portrait today; the widgets' compact forms are
what handles it. Refusing to render, or re-laying out automatically, would both change a page the user
didn't touch.

---

## Edit mode

Replaces the grid's move / resize arrows / add-slot overlay. Everything happens on the tile or the
divider the finger is on — no mode switches, no selection state.

**On a tile**

- **Drag onto another leaf → swap.** Onto an `Empty` that is a move. Same gesture as today's
  `moveOrSwap`, but by leaf rather than grid origin, so there is no "does it fit at the new origin"
  failure: both leaves keep their rects, only the contents trade.
- **Split right / split below** (Material `VerticalSplit` / `HorizontalSplit`, extended icon set is
  already a dependency): halves the tile and puts an `Empty` beside it. Along the parent's own axis
  this inserts a sibling (the tile's weight is halved) instead of nesting, which keeps the tree flat.
  Offered only when both halves clear the floor (the tile's own, and the `Empty`'s — see below).
- **Remove** turns the tile into an `Empty` in place. The structure — and so the symmetry — stays;
  a widget swap is remove + add, without the neighbours shifting in between.
- **Configure** (gear), unchanged.

**On an `Empty`**

- **Add** opens the widget picker, filtered to widgets whose floor fits the empty's measured size
  (the same "only list what actually works" rule the grid picker has).
- **Close** deletes the leaf; its siblings share out its weight in proportion, and `normalize()`
  collapses whatever is left with one child. This is how a region gets *merged*.

An `Empty` gets a small floor of its own (enough to show its two buttons, ~88 × 48dp) so a split can't
create an untappable sliver.

**On a divider**

- **Drag** moves weight between the two children either side of it. Clamped so neither subtree drops
  below its floor.
- It **always snaps** — there are no free positions, which is what keeps pages tidy without effort.
  Snap candidates:
  1. twelfths of the parent's extent (familiar granularity, carries the old grid's feel);
  2. the position that makes the two neighbours equal;
  3. **any other divider on the page on the same axis** — so the bottom band's divider can line up
     with the top band's column edge. This is the cross-band symmetry the tree alone doesn't give.

  The nearest candidate within ~24dp wins; otherwise the nearest twelfth. The target line is drawn
  while dragging.
- **Equalize** button on the divider's grip (a Material icon, not a `=` glyph — wasm has no font
  fallback): sets every child of that split to the same weight.

**On the page edge**

A thin add strip along each of the four edges inserts a full-width row or full-height column there —
wrapping the root in a new split, or appending to it when the root already runs on that axis. It is
the only way to add a band spanning the whole page after the fact, so it can't be left out.

Divider grips and edge strips exist only in edit mode, so the page chrome is unchanged when driving.
Controls size from the tile as today (`CTRL_MIN`–`CTRL_MAX`), and the on-display edit mode from the
UI-overhaul step 5 hosts all of it unchanged.

---

## Migration from the grid

The user is the only user and has accepted breaking layouts before (`vdt.pages` → `vdt.pages.v2`), but
their current pages are worth keeping and convert cleanly, so migrate:

- New key **`vdt.pages.v3`**. On load: v3 if present; else decode v2 and convert; else seeds. v2 is
  left in storage untouched, like v1 was.
- **Conversion is a guillotine cut**, per arrangement: in a region, find a column or row line that no
  cell crosses; cut there into strips (all such lines at once, so three side-by-side tiles become one
  three-way split); recurse into each strip. Weights = cell counts. A region with no cells becomes one
  `Empty` (adjacent empty cells merge, rather than becoming 84 single-cell empties).
- **No clean cut** (a pinwheel): cut on the line that crosses the fewest cells and give each crossed
  cell to the side holding the larger part of it. Lossy on purpose, and logged; the page loads and the
  user fixes it with a divider drag.
- Weight = cell count differs from the grid by a fraction of a gap per tile (the grid's gaps sit inside
  spans; a split's sit between children). Invisible; not worth modelling.
- `sanitize()` keeps its two jobs: an unregistered widget becomes an `Empty` (instead of being dropped,
  which on a tree would reflow its neighbours), and a repeated instance id within an arrangement
  becomes an `Empty` too.
- The **seeds** are rewritten as trees by hand — Vehicle, Farm, Pillar, both orientations — rather
  than run through the converter, so they read as the arrangements they are.
- A page's **portrait default** (the `Page` constructor default, for a page stored with only one
  arrangement) becomes the landscape tree with every axis flipped. A starting point, as the rescaled
  grid is today.

---

## Steps

Each step builds, passes `./gradlew check` and is committable on its own.

1. **Model + pure operations.** `LayoutNode`, `normalize`, `measure`, `minSize`, and the edit
   operations (`swap`, `split`, `removeToEmpty`, `closeEmpty`, `setDivider`, `equalize`, `addAtEdge`,
   `place`, `reconfigure`) as pure functions returning a new tree, each refusing (returning the input)
   what would break a floor — the same "no-op means not allowed" contract `GridLayout` has, so the UI
   can grey out controls by comparing. Plus the snap-candidate function. Unit tests for all of it,
   including the worked example above measured at the iPad body size.
2. **Migration + storage.** The grid → tree converter (tests: every current seed, a layout with gaps,
   a pinwheel), `PageStore` on `vdt.pages.v3`, `sanitize` on trees, hand-written tree seeds, the
   flipped-axis portrait default. `Page` switches to `LayoutNode`; `DisplayStoreTest` and
   `PageStoreTest` follow.
3. **Rendering.** `SplitLayoutView` replaces `WidgetGrid` for display: `measure` + absolute offsets,
   `CELL_GAP`/`GRID_PADDING` unchanged so a converted page looks the same as before. Widgets get
   `minWidth`/`minHeight`; spans are deleted from `Widget`. Edit mode temporarily off for this step
   only if step 4 isn't ready in the same sitting.
4. **Edit mode.** Tile overlay (drag-swap, split ×2, remove, configure), `Empty` (add via picker,
   close), divider grips (snapping drag, equalize), page-edge strips. `WidgetDashboard` wires them to
   `PageStore` as it does today.
5. **Cleanup.** Delete `GridLayout`, `WidgetGrid`, `GridPos`, `LayoutCell`; `GridAspect` keeps only
   the orientation choice (`of(width, height)`) and loses `columns`/`rows`. Rewrite the README's
   "Portrait layouts" section and the "a page is a grid you arrange yourself" line; update the
   `Widget` KDoc. Whatever this leaves undone goes to `FUTURE.md` and this file is deleted.

## Open, to settle while building

- The snap threshold (24dp is a guess) and whether twelfths are the right fallback granularity once
  cross-band alignment exists — try it on the iPad.
- Per-widget dp floors after the mechanical conversion; the map and the rig slot are the likely
  outliers.
- Whether dragging a tile onto a leaf's **edge** (dock beside it, splitting that leaf) is worth adding
  on top of swap + split. Not in round 1: swap + split + close already reach every arrangement, and a
  second drag gesture with edge zones is the kind of thing that misfires on a tablet in a moving cab.
