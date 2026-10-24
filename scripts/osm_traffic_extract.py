#!/usr/bin/env python3
"""
Create regional offline traffic data from the entire Vietnam OpenStreetMap PBF extract.
Source: https://download.geofabrik.de/asia/vietnam-latest.osm.pbf
Dependency: pip install osmium (pyosmium). No Mapbox or VietMap traffic sign API.

Input PBF already clipped to Vietnam by Geofabrik. Unlike a BRF routing profile,
this extracts only explicit OSM road maxspeed tags + mapped physical traffic
signs/cameras. It NEVER converts vmax or maxspeed_implicit into a legal speed sign.

Usage:
    python scripts/osm_traffic_extract.py --pbf vietnam-latest.osm.pbf \
      --out data/traffic_vn

Output: independent <= 9MB regional JSON packages, consumable by TrafficDatabase.
Always attribute © OpenStreetMap contributors, ODbL 1.0.
"""
import argparse
import collections
import datetime
import json
import math
from pathlib import Path

SPEED_TAGS = (
    "maxspeed", "maxspeed:forward", "maxspeed:backward",
    "maxspeed:lanes", "maxspeed:lanes:forward", "maxspeed:lanes:backward"
)
ROADS = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service", "road"
}
MAX_PACKAGE_BYTES = 8_000_000


def tile_for(lat, lon):
    """Country region cell 0.5 × 0.5 degrees, independent of road naming."""
    south = math.floor(lat * 2) / 2
    west = math.floor(lon * 2) / 2
    return f"VN_{south:.1f}_{west:.1f}".replace("-", "m")


def classify(tags):
    sign = tags.get("traffic_sign", "") or tags.get("traffic_sign:forward", "") or tags.get("traffic_sign:backward", "")
    camera = (
        tags.get("highway") == "speed_camera"
        or tags.get("enforcement") in ("maxspeed", "traffic_signals", "average_speed")
        or tags.get("speed_camera") == "yes"
    )
    if camera:
        return "CAMERA", tags.get("enforcement") or tags.get("highway") or "speed_camera"
    if sign:
        if "maxspeed" in sign.lower() or "speed_limit" in sign.lower():
            return "SPEED", sign
        return "SIGN", sign
    if tags.get("highway") in ("services", "rest_area") or tags.get("barrier") == "toll_booth":
        return "SERVICE", tags.get("highway") or "toll_booth"
    return None, None


class TiledWriter:
    def __init__(self, out):
        self.out = Path(out)
        self.temp = self.out / "_temp"
        self.temp.mkdir(parents=True, exist_ok=True)
        self.counts = collections.Counter()
        self.handles = collections.OrderedDict()

    def emit(self, tile, item):
        if tile in self.handles:
            h = self.handles.pop(tile)
        else:
            if len(self.handles) >= 24:
                _, old = self.handles.popitem(last=False)
                old.close()
            h = (self.temp / (tile + ".jsonl")).open("a", encoding="utf-8")
        self.handles[tile] = h
        h.write(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n")
        self.counts[tile] += 1

    def finish(self):
        for h in self.handles.values():
            h.close()
        self.handles.clear()
        self.out.mkdir(parents=True, exist_ok=True)
        total = [0, 0]
        for file in sorted(self.temp.glob("*.jsonl")):
            region = file.stem
            roads, points = [], []
            count = 0
            serial = 1

            def write():
                nonlocal roads, points, count, serial
                if not roads and not points:
                    return
                obj = {
                    "schema": 1, "country": "VN", "license": "ODbL-1.0",
                    "source": "Geofabrik/OpenStreetMap", "region": region,
                    "created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "roads": roads, "points": points
                }
                dest = self.out / (f"{region}_{serial:02}.json")
                dest.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")),
                                encoding="utf-8")
                total[0] += len(roads)
                total[1] += len(points)
                serial += 1
                roads = []
                points = []
                count = 0

            for line in file.open(encoding="utf-8"):
                record = json.loads(line)
                payload = record["data"]
                count += len(line.encode("utf-8"))
                if count > MAX_PACKAGE_BYTES:
                    write()
                if record["kind"] == "road":
                    roads.append(payload)
                else:
                    points.append(payload)
            write()
            file.unlink()
        self.temp.rmdir()
        summary = {
            "regions": len(self.counts),
            "roads_segments": total[0],
            "physical_points": total[1],
            "source": "OpenStreetMap contributors / Geofabrik (ODbL-1.0)",
            "warning": "Explicit OSM tags only; unrecorded signs and legal limits are UNKNOWN."
        }
        (self.out / "index.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        return summary


def run(pbf, output):
    import osmium

    writer = TiledWriter(output)

    class Extractor(osmium.SimpleHandler):
        def node(self, n):
            tags = dict(n.tags)
            kind, code = classify(tags)
            if kind is None:
                return
            if not n.location.valid():
                return
            lat, lon = n.location.lat, n.location.lon
            if not math.isfinite(lat) or not math.isfinite(lon):
                return
            writer.emit(tile_for(lat, lon), {
                "kind": "point",
                "data": {
                    "id": "osm:node:" + str(n.id),
                    "lat": lat, "lon": lon,
                    "type": kind, "code": code,
                    "maxspeed": tags.get("maxspeed", ""),
                    # OSM sign-facing direction is NOT vehicle travel bearing.
                    # Do not infer lane or camera direction from coordinates alone.
                    "lane": "UNKNOWN", "side": "UNKNOWN",
                    "driving_bearing": None,
                }
            })

        def way(self, w):
            tags = dict(w.tags)
            if tags.get("highway") not in ROADS:
                return
            if not any(key in tags for key in SPEED_TAGS):
                return
            name = tags.get("name", tags.get("ref", ""))[:140]
            oneway = tags.get("oneway", "")
            if not oneway and tags.get("junction") == "roundabout":
                oneway = "yes"
            for idx in range(len(w.nodes) - 1):
                a, b = w.nodes[idx], w.nodes[idx + 1]
                if not a.location.valid() or not b.location.valid():
                    continue
                lat1, lon1 = a.location.lat, a.location.lon
                lat2, lon2 = b.location.lat, b.location.lon
                if not all(map(math.isfinite, (lat1, lon1, lat2, lon2))):
                    continue
                if abs(lat1 - lat2) + abs(lon1 - lon2) < 1e-8:
                    continue
                mid_lat, mid_lon = (lat1 + lat2) / 2, (lon1 + lon2) / 2
                record = {
                    "id": f"osm:way:{w.id}:segment:{idx}",
                    "osm_way_id": "osm:way:" + str(w.id),
                    "name": name, "oneway": oneway,
                    "coordinates": [[lon1, lat1], [lon2, lat2]]
                }
                for key in SPEED_TAGS:
                    if key in tags:
                        record[key] = tags[key]
                writer.emit(tile_for(mid_lat, mid_lon), {
                    "kind": "road", "data": record
                })

    extractor = Extractor()
    extractor.apply_file(str(pbf), locations=True)
    return writer.finish()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("data/traffic_vn"))
    args = parser.parse_args()
    if not args.pbf.is_file():
        parser.error("Vietnam PBF file does not exist")
    print(json.dumps(run(args.pbf, args.out), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
