# CLAUDE.md

Project context for Claude Code. This is a **fork of GraphHopper 11** (Apache 2.0, Java
routing engine). The `road-risk-11` branch adds crash-risk-aware routing (rebased from
the older 9.x `road-risk` branch, which is kept as a fallback).

## Purpose of the `road-risk` branch

1. **Import**: read a modified `.pbf` whose ways carry a `road_risk` tag (per-segment
   crash risk) and store it as an encoded value on every edge.
2. **Route**: bias routing away from high-risk segments via a GraphHopper custom model
   (a JSON file applied at routing time), so weighting can be tuned without re-importing.

## Key files

| File | Role |
|------|------|
| `core/.../routing/ev/RoadRisk.java` | Declares the `road_risk` DecimalEncodedValue. |
| `core/.../routing/util/parsers/RoadRiskParser.java` | Reads the `road_risk` OSM tag during import, writes the edge value. |
| `core/.../routing/ev/DefaultImportRegistry.java` | Registers the parser so `road_risk` can be listed in `graph.encoded_values`. |
| `custom_models/road_risk.json` | Routing-time weighting overlay: penalizes risky segments. Composed on top of the built-in `car.json`. |
| `config-w-road-risk.yml` | Server config; enables the EV and wires the car profile to `[car.json, road_risk.json]` (flexible mode). |
| `run-with-roadrisk.md` | Launch command. |

## ⚠️ Critical semantics — the value is SAFETY, not risk

`RoadRiskParser` stores **`1 - risk`**, i.e. **safety**, clamped to `[0, 1]`:
- **Higher stored `road_risk` = safer** road.
- Missing/unparseable tag → default 0.5.
- Encoding: 10 bits, factor 0.001, so range ~`0.000–1.022` (top raw value = infinity).

The custom model relies on this inversion: safe roads (high value) keep priority ~1.0,
unsafe roads (low value) get penalized.

## Build & run

```bash
# Build the web jar (standard GraphHopper build)
mvn --projects web -am -DskipTests clean package

# Run, pointing at the risk-tagged pbf
java -D"dw.graphhopper.datareader.file=/path/to/oh-risk.osm.pbf" \
  -jar web/target/graphhopper-web-11.0-SNAPSHOT.jar server config-w-road-risk.yml
```

Delete `graph-cache/` to force a re-import after changing encoded values or the parser.

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

## History & rationale

The *why* behind changes lives in commit messages, not here. Run `git log` /
`git blame` (structured bodies — see `.gitmessage`) before changing the risk
logic or the encoding.
