// Fork: makes the raster icons in public/brand from the two 512 x 512 source PNGs of the square "A" mark,
// favicon-lightmode.png (dark mark, for light mode) and favicon-darkmode.png (light mark, for dark mode):
// favicon.ico and favicon-darkmode.ico (16, 32 and 48 px), icon-192.png and icon-512.png, and
// apple-touch-icon.png (180 px on white), the last three from the light-mode PNG. Run it after replacing the source
// PNGs, then commit the new files. It renders with the installed Microsoft Edge through the client's Playwright.
// See FORK.md, "Branding".
// Usage (from the repo root): node app/client/scripts/fork-brand-icons.js
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const brandDir = path.resolve(__dirname, "../public/brand");
const source = (name) =>
  "data:image/png;base64," +
  fs.readFileSync(path.join(brandDir, name)).toString("base64");

// Scales the source PNG to a square PNG of the given size, halving step by step so small sizes stay smooth.
async function render(page, sourceUri, size, background) {
  const b64 = await page.evaluate(
    async ({ background, size, sourceUri }) => {
      const img = new Image();

      img.src = sourceUri;
      await img.decode();
      let canvas = document.createElement("canvas");

      canvas.width = img.width;
      canvas.height = img.height;
      canvas.getContext("2d").drawImage(img, 0, 0);

      while (canvas.width / 2 >= size) {
        const half = document.createElement("canvas");

        half.width = canvas.width / 2;
        half.height = canvas.height / 2;
        const ctx = half.getContext("2d");

        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(canvas, 0, 0, half.width, half.height);
        canvas = half;
      }

      const out = document.createElement("canvas");

      out.width = size;
      out.height = size;
      const ctx = out.getContext("2d");

      if (background) {
        ctx.fillStyle = background;
        ctx.fillRect(0, 0, size, size);
      }

      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(canvas, 0, 0, size, size);

      return out.toDataURL("image/png").split(",")[1];
    },
    { background, size, sourceUri },
  );

  return Buffer.from(b64, "base64");
}

// An ICO file whose images are PNGs (supported by every current browser).
function ico(pngs) {
  const header = Buffer.alloc(6);

  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(pngs.length, 4);
  const entries = [];
  let offset = 6 + 16 * pngs.length;

  for (const { data, size } of pngs) {
    const entry = Buffer.alloc(16);

    entry.writeUInt8(size >= 256 ? 0 : size, 0);
    entry.writeUInt8(size >= 256 ? 0 : size, 1);
    entry.writeUInt8(0, 2);
    entry.writeUInt8(0, 3);
    entry.writeUInt16LE(1, 4);
    entry.writeUInt16LE(32, 6);
    entry.writeUInt32LE(data.length, 8);
    entry.writeUInt32LE(offset, 12);
    entries.push(entry);
    offset += data.length;
  }

  return Buffer.concat([header, ...entries, ...pngs.map((p) => p.data)]);
}

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage();
  const write = (name, data) => {
    fs.writeFileSync(path.join(brandDir, name), data);
    // eslint-disable-next-line no-console
    console.log(`wrote public/brand/${name} (${data.length} bytes)`);
  };
  const light = source("favicon-lightmode.png");
  const dark = source("favicon-darkmode.png");

  for (const [name, uri] of [
    ["favicon.ico", light],
    ["favicon-darkmode.ico", dark],
  ]) {
    const images = [];

    for (const size of [16, 32, 48]) {
      images.push({ size, data: await render(page, uri, size) });
    }

    write(name, ico(images));
  }

  write("icon-192.png", await render(page, light, 192));
  write("icon-512.png", await render(page, light, 512));
  write("apple-touch-icon.png", await render(page, light, 180, "#ffffff"));
  await browser.close();
})();
