# WatermarkBox Tutorial

WatermarkBox is a **fully offline** image watermarking tool. Everything runs on
your phone — no network access, no uploads, no ads.

---

## 1. Three steps

1. **Pick an image** — tap "Choose a photo" on the home screen
2. **Adjust the watermark** — drag it right on the preview, or use the panel below
3. **Export** — tap "Export" at the bottom right

That's it.

---

## 2. Visible watermark

### Text watermark

- Type your text under "Watermark text"
- Placeholders: `{date}`, `{filename}`, `{timestamp}`
- Adjustable: size, colour, bold, italic, stroke, shadow

### Image watermark (logo)

- Tap "Choose Logo" to pick an image
- Adjustable: size, opacity, position

### Three layouts

| Mode | Effect |
|---|---|
| **Single** | One watermark at a chosen position (3×3 grid, or drag it there) |
| **Tiled** | A grid covering the whole image; each watermark **rotates on its own** |
| **Diagonal** | **The whole grid is rotated** — parallel bands filling all four corners |

> **Drag to position**: in Single mode, just drag the watermark where you want it.

---

## 3. Invisible watermark (for provenance)

An invisible watermark **does not change how the image looks**. Nobody can see
it, but you can use it to prove "this image came from me".

### Two channels

| Channel | Strength | Cost |
|---|---|---|
| **Metadata (EXIF / XMP)** | Completely lossless | Lost on any compression / conversion |
| **DCT frequency** | Survives JPEG re-compression | Slightly alters pixels |

**Turn on both.** Uncompressed images fall back to metadata (perfectly
lossless); once the image is compressed, the frequency channel still holds.

### Content

- **Custom text** — anything you like
- **Device ID + timestamp** — generated automatically, useful for tracing
  *which device* exported it and *when*

### Embedding strength

1 is the most subtle, 4 is the most stubborn. Raise it if the image will be
compressed repeatedly.

### Signature

Invisible watermarks carry an **app signature**. Detection reports one of:

- ✅ **Verified · issued by this app**
- ⚠️ **Invalid · possibly forged**
- **Not verifiable** (older version, or metadata-only)

---

## 4. Detecting an invisible watermark

Home → "Detect watermark" → pick an image → detection runs automatically.

The result shows: content, source channel, signature status, confidence.

### Detection settings

- **Check EXIF metadata** — fastest; catches untouched images without scanning pixels
- **Check DCT frequency** — survives compression, but needs a full pixel scan
- **Try all embedding strengths** — the strength used at export is unknown, so trying all is more reliable
- **Try 4 rotation directions** — recognises images that were rotated
- **Verify signature via DCT** — EXIF is plain text with no signature; turn this on to fetch the signature status from the frequency channel

> Turning options off is faster, but may miss a watermark. All on by default.

---

## 5. Batch processing

Home → "Batch process":

1. Up to **100 images** at once
2. **Identical** parameter panel to single-image editing
3. Swipe the preview to move between images
4. Runs in the background, cancellable, with a summary at the end

---

## 6. Presets

Save a frequently used set of parameters as a preset and apply it in one tap.

- Enter from the **bookmark icon** at the top right of the home screen
- **Editable without an image** — tweak parameters before picking one
- **Export / import as a file** — handy when switching phones or sharing
  - The exported file **contains no local images**; image presets need a new logo after import
  - Duplicate names are **renamed automatically** on import — existing presets are never overwritten

---

## 7. Export settings

Two entry points, two different meanings:

| Entry | What it does |
|---|---|
| **⚙ at the top right** | **Global export settings** — changes the defaults used by every future export |
| **"Export" at the bottom right** | **One-off parameters** — affects this export only, defaults untouched |

Configurable: format (PNG / JPEG), JPEG quality, filename template, output
directory, whether to keep the original EXIF.

---

## 8. FAQ

**Q: Can the watermark be removed?**

A visible watermark can be cropped away. An invisible one survives compression
and right-angle rotation, but **not screenshots, re-photographing, or AI repainting** — it is meant for provenance, not for making copying impossible.

**Q: Do you upload my images?**

No. The app requests no network permission; all processing is local.

**Q: Where are exported images saved?**

By default in `Pictures/WatermarkBox` in your gallery. Change it in export settings.

**Q: Can a rotated image still be detected?**

90° / 180° / 270° — yes (the detector tries all four orientations).
**Arbitrary angles** (e.g. 15°) are not currently supported.

**Q: What about a downscaled image?**

No. Scaling breaks the block structure the watermark relies on, and downscaling
is **irreversible** information loss.