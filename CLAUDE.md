# CLAUDE.md

Project context for Claude Code. This is a **public fork of GraphHopper 11** (Apache 2.0,
Java routing engine).

## Purpose of this fork

Add a single new routing variable, **`road_risk`**, and let the router use it:

1. **Import**: read a `.pbf` whose ways carry a `road_risk` tag (per-segment crash risk)
   and store it as an encoded value on every edge.
2. **Route**: bias routing away from high-risk segments via a GraphHopper custom model
   (a JSON file applied at routing time), so weighting can be tuned without re-importing.

That is the whole scope. **How `road_risk` values are derived is out of scope for this
repo.** This fork is the *consumer*: it takes any `.pbf` carrying a numeric `road_risk`
tag and routes on it. Do not document or reason about how the values are produced — treat
the tag as given.

`master` is the current v11 line. The `road-risk` branch preserves the older 9.x state as
a fallback.

## Key files

| File | Role |
|------|------|
| `core/.../routing/ev/RoadRisk.java` | Declares the `road_risk` DecimalEncodedValue. |
| `core/.../routing/util/parsers/RoadRiskParser.java` | Reads the `road_risk` OSM tag during import, writes the edge value. |
| `core/.../routing/ev/DefaultImportRegistry.java` | Registers the parser so `road_risk` can be listed in `graph.encoded_values`. |
| `custom_models/road_risk.json` | Routing-time weighting overlay: penalizes risky segments. Composed on top of the built-in `car.json`. |
| `config-w-road-risk.yml` | Server config; enables the EV and wires the `car` profile to `[car.json, road_risk.json]` (flexible mode). Also defines `car_plain` (`[car.json]` only) as an A/B baseline — if `car` and `car_plain` return identical routes, the risk overlay is not working. |
| `run-with-roadrisk.md` | Runbook: build, launch, A/B-verify the overlay, tune the bands. |
| `docs/road-risk-design.md` | Design rationale: the measurement-vs-policy split (shift-left), why weighting stays at routing time, why graduated bands. |

## Input contract

The engine expects ways tagged `road_risk=<decimal>` where the value is **crash risk in
`[0, 1]`, higher = riskier**. Values outside the range are clamped.

A non-numeric value (e.g. a category like `medium`) fails `Double.parseDouble` and falls
back to the default. **This fails quietly** — routing still returns perfectly good,
entirely risk-blind routes. If the risk overlay appears to do nothing, check the tag
values before suspecting the code:

```bash
osmium tags-filter input.osm.pbf w/road_risk -o /tmp/r.pbf --overwrite
osmium cat -f opl /tmp/r.pbf | grep -o 'road_risk=[^,]*' | sort -u | head
```

Expect decimals. If you see words, the input is bad, not the parser — do **not** "fix"
`RoadRiskParser` to accept categories.

The risk-tagged `.pbf` is not distributed with this repo and is gitignored.

## ⚠️ Critical semantics — the stored value IS the risk

The encoded value equals the tag: `RoadRiskParser` stores the crash-risk value as-is,
clamped to `[0, 1]`. **No inversion.**

- **Higher stored `road_risk` = more dangerous** road.
- Missing or unparseable tag → neutral default **0.5**.
- Encoding: 10 bits, factor 0.001, range ~`0.000–1.022` (top raw value = infinity).

Because the stored value is risk, a custom model deprioritizes danger by scaling priority
with **safety** = `1 - road_risk`: safe roads (low `road_risk`) keep priority ~1.0,
dangerous roads (high `road_risk`) get penalized. That is why the Maps UI preset
`multiply_by: "1 - road_risk"` is correct, and why the server-side `road_risk.json` bands
penalize the **high** end of the range.

> Historical note: earlier revisions of `RoadRiskParser` stored `1 - risk` (safety), so the
> value's meaning was inverted relative to its name. That was a booby trap — every natural
> expression against a value named `road_risk` came out backwards, silently. The inversion
> was removed so the name matches the number. If you are reading old commits, docs, or a
> graph-cache built before that change, `road_risk` meant safety there.

### An untagged way is *ordinary* — neither optimal nor blocked

The concrete trap: **a missing `road_risk` tag does not reach the graph as 0.**
`RoadRiskParser.handleWayTags` always writes a value, using `DEFAULT_RISK` = **0.5** when
the tag is absent. 0.5 falls in the middle band → priority **×0.6** — the same band, and
the same multiplier, a median-risk road gets.

So an untagged way is not the most attractive edge in the graph, and not an excluded one.
It is an unremarkable one. Two corollaries:

- If you catch yourself reasoning that "blank means priority 1.0" or "blank means
  impassable", stop and read `RoadRiskParser` and `road_risk.json`.
- **Filling in previously-untagged ways cannot change routes** — they already routed at the
  band-`0.6` default. Untagged ways are a data-completeness matter, not a live hazard.

## Build & run

```bash
# Build the web jar (standard GraphHopper build)
mvn --projects web -am -DskipTests clean package

# Run, pointing at a risk-tagged pbf
java -Xmx8g -D"dw.graphhopper.datareader.file=$PWD/<your-risk>.osm.pbf" \
  -jar web/target/graphhopper-web-11.0-SNAPSHOT.jar server config-w-road-risk.yml
```

Delete `graph-cache/` to force a re-import after changing encoded values, the parser, or
the input `.pbf`. GraphHopper will silently reuse the old graph otherwise.

See `run-with-roadrisk.md` for the A/B verification recipe.

## Gotchas

- GraphHopper 11 removed the `vehicle:` concept: a profile is defined purely by its
  custom model(s). The `car` profile uses `custom_model_files: [car.json, road_risk.json]`;
  the built-in `car.json` needs `car_access, car_average_speed, road_access` in
  `graph.encoded_values` (alongside `road_risk`).
- Multiple `custom_model_files` are **merged left-to-right**: priority/speed statements
  are appended, so `road_risk.json`'s bands multiply on top of `car.json`. `distance_influence`
  from the *later* file wins, so `road_risk.json` sets it (70) to override `car.json`'s 90.
- A custom-model `multiply_by: 0` makes edges impassable → "connection not found".
  `road_risk.json` uses graduated bands (min 0.1) instead of hard blocks.
- Custom model files load from `custom_models.directory` OR the classpath; a name that
  collides with a built-in model under `core/.../custom_models/` is rejected. `road_risk.json`
  is fine (no built-in of that name); `car.json` resolves from the classpath.
- Config runs in flexible mode (`profiles_ch: []`, `profiles_lm: []`) so the risk weighting
  stays tunable at request time without re-preparing.
- **What counts as "car-routable" is decided by `CarAccessParser`, not by intuition — and it
  can widen between GraphHopper versions.** Its `highwayValues` set is broader than the
  obvious road classes: `highway=pedestrian` is routable when the way carries an explicit
  `motor_vehicle`/`vehicle`/`access` permission, and ferries are routable *with no `highway`
  tag at all* (`getAccess` short-circuits to `WayAccess.FERRY` on a null highway value).
  Anything deciding which ways ought to carry a `road_risk` tag must read that parser rather
  than assume a class list, and must re-check it after an upstream version bump.

## History & rationale

The *why* behind individual changes lives in commit messages, not here. Run `git log` /
`git blame` (structured bodies — see `.gitmessage`) before changing the risk
logic or the encoding.

The durable, cross-cutting *why* — the measurement-vs-policy (shift-left) split that
explains why risk is stored faithfully at import but weighted at routing time — lives in
[docs/road-risk-design.md](docs/road-risk-design.md).
