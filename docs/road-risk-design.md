# Road-risk design & rationale

This document records *why* the road-risk feature is built the way it is — the
architectural choices, not the mechanics. For **what** it does and how to run it, see
the [README fork section](../README.md#about-this-fork) and
[run-with-roadrisk.md](../run-with-roadrisk.md). For the exact stored-value semantics and
day-to-day gotchas, see [CLAUDE.md](../CLAUDE.md).

The scope of the fork is one routing variable, `road_risk`. Every choice below follows from
one decision: **keep the risk *measurement* separate from the risk *policy*.**

## Two transformations, at two altitudes

It is tempting to think of "the road-risk work" as a single transformation. It is two, and
they deliberately live in different places:

| | Transformation | Where it runs | What it produces |
|---|---|---|---|
| **T1 — measurement** | `road_risk` tag → per-edge encoded value | **Import** (`RoadRiskParser`) | The crash-risk number, stored faithfully (clamped to `[0, 1]`, no inversion). |
| **T2 — policy** | risk value → priority penalty | **Routing** (`road_risk.json`, or a UI custom model) | A weighting decision: how strongly to avoid each risk level. |

T1 is a *measurement*: an objective, reusable fact about a road. T2 is a *policy*: a
subjective decision about how a router should behave, on which different consumers legitimately
disagree.

## The shift-left argument

In ETL, "shift left" means: do a transformation as early in the pipeline as possible, so every
downstream consumer inherits its benefit. The rule is often mis-stated as "do *all*
transformations early." The correct version is narrower:

> Shift left the transformations that are **canonical and reusable** (cleaning, conforming,
> type-casting). Keep right the transformations that are **contested policy**, so each consumer
> can define them differently.

Applied here:

- **T1 is shifted left — correctly.** The measurement happens once, at import, and lands on
  every edge as an encoded value. The `car` overlay, the Maps UI preset, a future "paint risk
  on the map" feature, and any other custom model all read the *same* faithful number. This is
  the reusable-measure benefit shift-left is supposed to buy.
- **T2 is kept right — also correctly.** The weighting is applied at request time, in flexible
  mode (`profiles_ch: []`, `profiles_lm: []`), so it can be tuned without re-importing. Risk
  *preference* is not a property of the road; it does not belong in the data.

So the design does **not** violate shift-left. It applies it at the granularity of the
individual transformation, which is where the principle actually operates.

## Why not bake the weighting into the graph?

Concretely, "shift T2 left" would mean storing `priority = f(risk)` on the edge instead of the
raw `road_risk`. The costs:

1. **It is lossy.** The encoded value becomes a weighting artifact, not a measurement. You can
   no longer recover the original risk to (a) display it, (b) apply a *different* policy, or
   (c) drive it from a UI slider. One number cannot be both the reading and the verdict.
2. **Re-tuning becomes a re-import.** The whole point of the flexible-mode design is that
   weighting is tunable at request time. Bake the bands into the data and every tweak requires
   `rm -rf graph-cache/` and a full re-read of the `.pbf` — the opposite of reusability.
3. **The A/B baseline breaks.** `car` and `car_plain` both read the same faithful edge value
   and differ *only* by the overlay (see `config-w-road-risk.yml`). Put the penalty in the data
   and `car_plain` is penalized too, so you lose the "is the overlay even live?" check.

## Why graduated bands, not the continuous `1 - road_risk`?

The routing-time policy (`custom_models/road_risk.json`) uses stepped bands
(`>0.8 → ×0.1`, `>0.6 → ×0.3`, `>0.4 → ×0.6`, `>0.2 → ×0.85`, else `×1.0`) rather than the
continuous `multiply_by: "1 - road_risk"` the Maps UI preset uses. Reasons:

- **A guaranteed floor.** The bands never multiply by 0, so no edge is ever made impassable.
  A raw `1 - road_risk` drives priority to 0 (or negative, given the top raw value clamps to
  ~1.022) at the dangerous end — exactly the `multiply_by: 0` → "connection not found" failure
  mode. The `0.1` floor avoids it.
- **A shaped response.** The bands roughly track `1 - road_risk` but bend it convex at the
  dangerous end (×0.1 / ×0.3 where the linear curve gives ~0.2 / ~0.4), punishing the worst
  roads disproportionately while leaving very safe roads (`≤ 0.2`) completely alone at ×1.0.
- **Legibility.** Four bands are self-documenting for a demo operator to read and tune; a
  continuous formula is not.

## Two loose ends worth knowing

- **The `0.5` default-fill is policy that lives in the parser.** A missing or unparseable tag
  becomes `DEFAULT_RISK = 0.5` in `RoadRiskParser` (T1), which then lands in the `>0.4` band
  (×0.6). This is the one spot where a T2-style judgment ("missing = median") sits in the T1
  layer. It is defensible — the encoded value must hold *something*, and "neutral median" is a
  reasonable choice — but it is worth recognizing as policy, not measurement.
- **The profile and the UI preset can double-count.** Because a UI custom model composes *on
  top of* the selected profile's model, choosing the risk-weighted `car` profile **and** pasting
  the `1 - road_risk` preset applies the safety penalty twice. The two are near-duplicates of
  the same safety curve, so stacking them squares an already-similar penalty — a `0.9`-risk edge
  collapses to `0.1 × 0.1 = 0.01`, worst exactly where risk is highest. If you offer both paths
  in a demo, guard against selecting both at once; do **not** "fix" this by baking the weighting
  into the data (see the section above — that trades a UI-layer guardrail for permanent rigidity).
