// Generates the app's offline MapLibre styles from @protomaps/basemaps and downloads the glyph
// ranges and sprites they need into app/src/main/assets/map.
//
//   cd pipeline/tools/style && npm ci && npm run generate
//
// The basemap source URL is the placeholder "pmtiles://{basemap}"; the app replaces it with the
// installed data pack file at runtime.
import { layers, namedFlavor } from "@protomaps/basemaps";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const outDir = join(repoRoot, "app", "src", "main", "assets", "map");

// basemaps-assets pinned to a commit so regenerating is reproducible.
const ASSETS_COMMIT = "028c18f713baecad011301ff7a69acc39bcc2ae7";
const ASSETS_BASE = `https://raw.githubusercontent.com/protomaps/basemaps-assets/${ASSETS_COMMIT}`;

// Font stacks as named by @protomaps/basemaps -> asset folder names without spaces
// (avoids percent-encoding issues in asset:// glyph URLs).
const FONTS = {
  "Noto Sans Regular": "NotoSans-Regular",
  "Noto Sans Medium": "NotoSans-Medium",
  "Noto Sans Italic": "NotoSans-Italic",
};
// Referenced by the style for Devanagari labels only; renamed for consistency but not bundled.
const UNBUNDLED_FONTS = {
  "Noto Sans Devanagari Regular v1": "NotoSansDevanagari-Regular",
};
const FONT_RENAMES = { ...FONTS, ...UNBUNDLED_FONTS };
// Latin, Latin Extended A/B, combining marks and general punctuation cover Portuguese labels;
// 65024-65279 (variation selectors, BOM) shows up inside some OSM names.
const GLYPH_RANGES = ["0-255", "256-511", "512-767", "768-1023", "8192-8447", "65024-65279"];
const FLAVORS = ["light", "dark"];

const ATTRIBUTION =
  '<a href="https://protomaps.com">Protomaps</a> © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';

async function download(url, target) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, Buffer.from(await response.arrayBuffer()));
}

function renameFonts(value) {
  if (Array.isArray(value)) return value.map(renameFonts);
  if (typeof value === "string") return FONT_RENAMES[value] ?? value;
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, renameFonts(v)]));
  }
  return value;
}

for (const flavor of FLAVORS) {
  const style = {
    version: 8,
    name: `LisBora ${flavor}`,
    glyphs: "asset://map/fonts/{fontstack}/{range}.pbf",
    sprite: `asset://map/sprites/${flavor}`,
    sources: {
      protomaps: {
        type: "vector",
        url: "pmtiles://{basemap}",
        attribution: ATTRIBUTION,
      },
    },
    // Fonts appear in "text-font" and inside "format" sections of "text-field", so rename the whole layout.
    layers: layers("protomaps", namedFlavor(flavor), { lang: "pt" }).map((layer) =>
      layer.layout ? { ...layer, layout: renameFonts(layer.layout) } : layer,
    ),
  };
  const target = join(outDir, `style-${flavor}.json`);
  await mkdir(outDir, { recursive: true });
  await writeFile(target, JSON.stringify(style));
  console.log(`style: ${target} (${style.layers.length} layers)`);

  for (const suffix of ["", "@2x"]) {
    for (const ext of ["json", "png"]) {
      const name = `${flavor}${suffix}.${ext}`;
      await download(`${ASSETS_BASE}/sprites/v4/${name}`, join(outDir, "sprites", name));
    }
  }
  console.log(`sprites: ${flavor}`);
}

for (const [stack, folder] of Object.entries(FONTS)) {
  for (const range of GLYPH_RANGES) {
    await download(
      `${ASSETS_BASE}/fonts/${encodeURIComponent(stack)}/${range}.pbf`,
      join(outDir, "fonts", folder, `${range}.pbf`),
    );
  }
  console.log(`fonts: ${stack} -> ${folder}`);
}
await download(`${ASSETS_BASE}/fonts/OFL.txt`, join(outDir, "fonts", "OFL.txt"));
