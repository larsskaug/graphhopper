# Running GraphHopper with crash-risk routing

## Build

```bash
mvn --projects web -am -DskipTests clean package
```

## Run

```bash
java -Xmx8g -D"dw.graphhopper.datareader.file=$PWD/<your-risk>.osm.pbf" \
  -jar web/target/graphhopper-web-11.0-SNAPSHOT.jar server config-w-road-risk.yml
```

Serves on `localhost:8989` (admin on `:8990`). A US-state-sized extract imports in well
under a minute.

**Delete `graph-cache/` before re-importing** after any change to the parser, the encoded
value, `graph.encoded_values`, or the input `.pbf` — GraphHopper will otherwise silently
reuse the old graph.

## Input

Any `.osm.pbf` whose ways carry `road_risk=<decimal>`, crash risk in `[0, 1]`, higher =
riskier. The file is not distributed with this repo (`*.osm.pbf` is gitignored) — bring
your own. See CLAUDE.md → "Input contract" for the tag requirements and how to sanity-check
a file before importing it.

## Verifying the risk overlay actually does something

The config defines two profiles: `car` (car.json + road_risk.json) and `car_plain`
(car.json only). `car_plain` exists purely as an A/B baseline — if the two profiles
return the same route, the risk overlay is not working.

Compare distance and geometry:

```bash
for p in car car_plain; do
  curl -s "http://localhost:8989/route?point=39.1031,-84.5120&point=39.7589,-84.1916&profile=$p&points_encoded=false" \
    | jq -r "\"$p: \(.paths[0].distance/1000 | floor) km, \(.paths[0].time/60000 | floor) min\""
done
```

Better: ask for the per-edge `road_risk` values along the path and compute the
distance-weighted mean. The stored value is **crash risk**, so *lower is better*:

```bash
curl -s "http://localhost:8989/route?point=39.1031,-84.5120&point=39.7589,-84.1916&profile=car&points_encoded=false&details=road_risk" \
  | jq '.paths[0] as $p
        | [$p.details.road_risk[] | {seg:(.[1]-.[0]), risk:.[2]}] as $d
        | ($d | map(.seg) | add) as $tot
        | {weighted_risk: ($d | map(.seg * .risk) | add / $tot),
           pct_dist_on_risky_road: ($d | map(select(.risk > 0.6) | .seg) | add // 0) / $tot * 100}'
```

A working overlay should show `car` with a lower weighted risk and a smaller share of its
distance on risky road than `car_plain`, in exchange for a somewhat longer route. If the
two profiles come back identical, the overlay is not live — check that `road_risk` is in
`graph.encoded_values` and that the pbf's tag values are numeric.

## Tuning

Edit `custom_models/road_risk.json` and restart (no re-import needed — the config runs
in flexible mode, so weighting is applied at request time):

- `multiply_by` per band: lower = avoid risk harder = bigger detours.
- `distance_influence`: higher = prefer shorter routes.

Custom models can also be POSTed per request, so bands can be swept without a restart.
