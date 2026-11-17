"""Validation mirror of the Kotlin per-cell pipeline in app/src/main/java/com/sudokuassist/vision/.

Check-list:
  * cell_ink_mask()  == SudokuGridDetector.cellInkMask()   (Otsu, minority, contrast guard, 2x2 OPEN)
  * clean_border()   == SudokuGridDetector.cleanBorderBlobs()
  * occupancy rules  == SudokuRecognizer.readOccupancy()
  * DigitTemplates.features() -> zero-mean 16x16 coverage, NCC
  * axisAlignedCandidate() -> structMask / lineProfiles / fitLattice (fast path)

Usage:
    pip install opencv-python-headless pillow numpy
    python3 tools/verify_pipeline.py

Exit code is 0 when the bundled sample is recognised without errors.
"""
import sys

import cv2
import numpy as np

CELL = 96
MIN_CELL_CONTRAST = 35.0
GRID = 16


def cell_ink_mask(gray):
    g = cv2.GaussianBlur(gray, (5, 5), 0)
    t, _ = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    total = g.size
    bright = int(np.count_nonzero(g > t))
    if bright == 0 or bright == total:
        return np.zeros_like(g)
    if bright > total - bright:
        ink = (g <= t)
        ink_count = total - bright
    else:
        ink = (g > t)
        ink_count = bright
    m = (ink.astype(np.uint8) * 255)
    ink_mean = float(g[ink].mean())
    bg_count = total - ink_count
    all_mean = float(g.mean())
    bg_mean = (all_mean * total - ink_mean * ink_count) / bg_count if bg_count else all_mean
    if abs(ink_mean - bg_mean) < MIN_CELL_CONTRAST:
        return np.zeros_like(g)
    return cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))


def clean_border(mask, border=1):
    n, labels, stats, _ = cv2.connectedComponentsWithStats((mask > 0).astype(np.uint8), 8)
    if n <= 1:
        return mask
    h, w = mask.shape
    out = mask.copy()
    for i in range(1, n):
        x, y, bw, bh, _a = stats[i]
        if x <= border or y <= border or x + bw >= w - border or y + bh >= h - border:
            out[labels == i] = 0
    return out


def features(bmp_gray):
    """Mirror of DigitTemplates.features(): bbox-normalise, 16x16, max-normalise, zero-mean."""
    lum = bmp_gray.astype(np.float32) / 255.0
    ink = 1.0 - lum
    ys, xs = np.nonzero(ink > 0.5)  # lum < 128
    f = np.zeros((GRID, GRID), np.float32)
    if len(xs) == 0:
        return f.ravel()
    minX, maxX, minY, maxY = xs.min(), xs.max(), ys.min(), ys.max()
    w = float(maxX - minX + 1)
    h = float(maxY - minY + 1)
    for y in range(ink.shape[0]):
        for x in range(ink.shape[1]):
            v = ink[y, x]
            if v <= 0.05:
                continue
            gx = int((x - minX) / w * (GRID - 1))
            gy = int((y - minY) / h * (GRID - 1))
            gx = min(max(gx, 0), GRID - 1)
            gy = min(max(gy, 0), GRID - 1)
            f[gy, gx] += v
    mx = f.max()
    if mx > 0:
        f /= mx
    f -= f.mean()
    return f.ravel()


SIZE = 64


def build_templates(font_path="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
    """Mirror of DigitTemplates.buildTemplates(): 64x64 canvas, textSize = 0.82*SIZE,
    centered horizontally, baseline chosen so the glyph is vertically centered."""
    from PIL import Image, ImageDraw, ImageFont

    font = ImageFont.truetype(font_path, int(SIZE * 0.82))
    tiles = []
    for d in range(1, 10):
        img = Image.new("L", (SIZE, SIZE), 255)
        dr = ImageDraw.Draw(img)
        box = dr.textbbox((0, 0), str(d), font=font)
        x = SIZE / 2 - (box[2] + box[0]) / 2
        y = SIZE / 2 - (box[3] + box[1]) / 2
        dr.text((x, y), str(d), font=font, fill=0)
        tiles.append(features(np.array(img)))
    return np.array(tiles)


def classify(crop, templates):
    f = features(crop)
    na = float(f @ f)
    scores = []
    for t in templates:
        nb = float(t @ t)
        denom = np.sqrt(na * nb)
        scores.append(float(f @ t) / denom if denom > 0 else 0.0)
    s = np.array(scores)
    k = int(np.argmax(s))
    second = float(np.sort(s)[-2])
    return k + 1, float(s[k]), second


# ---------------------------------------------------------------- detection
# Mirror of SudokuGridDetector.structMask / lineProfiles / fitLattice (axis-aligned
# fast path only: that is the path the bundled sample takes).


def struct_mask(gray):
    g = cv2.GaussianBlur(gray, (3, 3), 0)
    inv = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 3)
    nrm = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, 31, 3)
    m = inv if np.count_nonzero(inv) <= np.count_nonzero(nrm) else nrm
    _, o = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    if np.count_nonzero(o) > o.size / 2:
        o = cv2.bitwise_not(o)
    return cv2.bitwise_or(m, o)


def line_profiles(mask, kvert, khorz):
    vert = cv2.morphologyEx(mask, cv2.MORPH_OPEN,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (1, kvert)))
    horz = cv2.morphologyEx(mask, cv2.MORPH_OPEN,
                            cv2.getStructuringElement(cv2.MORPH_RECT, (khorz, 1)))
    cols = (vert > 0).mean(axis=0).astype(np.float32)
    rows = (horz > 0).mean(axis=1).astype(np.float32)
    return cols, rows


def subpixel(p, center, half):
    a = max(0, int(np.floor(center - half)))
    b = min(p.size - 1, int(np.ceil(center + half)))
    if b <= a:
        return center
    seg = p[a:b + 1]
    mx = float(seg.max())
    w = np.maximum(seg - 0.5 * mx, 1e-6)
    return float((np.arange(a, b + 1) * w).sum() / w.sum())


def seg_max(p, center, half):
    a = max(0, int(round(center - half)))
    b = min(p.size - 1, int(round(center + half)))
    if b < a:
        return 0.0
    return float(p[a:b + 1].max())


def fit_lattice(profile, span_lo=0.22, span_hi=1.0, min_count=9, fuzz=0.0):
    if profile.size == 0:
        return None
    if profile.max() <= 0.12:
        return None
    n = profile.size
    p = profile.copy()
    b = min(3, max(2, n // 60))
    for i in range(b):
        p[i] = 0
        p[n - 1 - i] = 0
    mx = float(p.max())
    if mx <= 0:
        return None
    pn = p / mx
    s_lo = max(4.0, (n - 1) * span_lo / 9.0)
    s_hi = (n - 1) * span_hi / 9.0
    # local maxima, non-max suppression
    thr = 0.13
    cand = [i for i in range(1, n - 1) if pn[i] >= thr and pn[i] >= pn[i - 1] and pn[i] >= pn[i + 1]]
    cand.sort(key=lambda i: -pn[i])
    nms = max(2, n // 150)
    keep = []
    for i in cand:
        if all(abs(k - i) > nms for k in keep):
            keep.append(i)
    keep.sort()
    peaks = np.array(keep, float)

    def consider(o, s):
        cnt = 0
        tot = 0.0
        for t in range(10):
            v = seg_max(pn, o + t * s, 0.20 * s)
            if v >= 0.22:
                cnt += 1
            tot += v
        return cnt + tot / 10.0, o, s

    best = None
    peak_count = min(peaks.size, 26)
    if peak_count >= 2:
        order = sorted(range(peaks.size), key=lambda i: -pn[int(peaks[i])])[:peak_count]
        pk = np.sort(peaks[order])
        for i in range(peak_count):
            for j in range(i + 1, peak_count):
                d = pk[j] - pk[i]
                for k in range(1, 10):
                    s = d / k
                    if s < s_lo or s > s_hi:
                        continue
                    for k1 in range(0, 10 - k):
                        o = pk[i] - k1 * s
                        if o < -0.8 * s or o + 9 * s > (n - 1) + 0.8 * s:
                            continue
                        r = consider(o, s)
                        if best is None or r[0] > best[0]:
                            best = r
    if best is None:
        return None
    o, s = best[1], best[2]
    # sub-pixel least squares over inliers
    ts, xs = [], []
    for t in range(10):
        pred = o + t * s
        if seg_max(pn, pred, 0.24 * s) >= 0.22:
            ts.append(t)
            xs.append(subpixel(pn, pred, 0.22 * s))
    if len(ts) >= 2:
        A = np.vstack([ts, np.ones(len(ts))]).T
        s_fit, o_fit = np.linalg.lstsq(A, np.array(xs), rcond=None)[0]
        if abs(s_fit - s) < 0.15 * s:
            s, o = float(s_fit), float(o_fit)
    count = max(len(ts), 1)
    score = best[0] / 10.0 * (count / 9.0)
    return dict(o=o, s=s, score=score, count=count)


def detect_axis(gray):
    """@return quad (4x2) or None, mirroring axisAlignedCandidate()."""
    h, w = gray.shape
    mask = struct_mask(gray)
    kvert = max(7, h // 8)
    khorz = max(7, w // 8)
    cols, rows = line_profiles(mask, kvert, khorz)
    lc = fit_lattice(cols)
    lr = fit_lattice(rows)
    if lc is None or lr is None:
        return None
    if abs(lc["s"] - lr["s"]) > 0.06 * max(lc["s"], lr["s"]):
        return None
    x0, y0 = lc["o"], lr["o"]
    x1, y1 = lc["o"] + 9 * lc["s"], lr["o"] + 9 * lr["s"]
    if x0 < -4 or y0 < -4 or x1 > w + 4 or y1 > h + 4:
        return None
    score = 0.5 * (lc["score"] + lr["score"])
    quad = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], np.float32)
    return quad, score


def run(path, truth, quad=None, templates=None):
    im = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if im is None:
        print(path, "MISSING")
        return None
    if quad is None:
        got = detect_axis(im)
        if got is None:
            print(f"{path.split('/')[-1]}: NO GRID")
            return None
        quad, score = got
        print(f"   detected quad={np.round(quad, 1).tolist()} lattice_score={score:.2f}")
    src = np.array(quad, np.float32)
    dst = np.array([[0, 0], [9 * CELL, 0], [9 * CELL, 9 * CELL], [0, 9 * CELL]], np.float32)
    M = cv2.getPerspectiveTransform(src, dst)
    warp = cv2.warpPerspective(im, M, (9 * CELL, 9 * CELL), flags=cv2.INTER_LINEAR)

    margin = int(CELL * 0.10)
    occ_err = 0
    digit_err = 0
    confs = []
    for i in range(81):
        r, c = divmod(i, 9)
        inner = warp[r * CELL + margin:(r + 1) * CELL - margin,
                     c * CELL + margin:(c + 1) * CELL - margin]
        mask = clean_border(cell_ink_mask(inner))
        n, _lab, stats, _ = cv2.connectedComponentsWithStats((mask > 0).astype(np.uint8), 8)
        best = None
        min_area = max(6, int(0.012 * inner.size))
        for k in range(1, n):
            x, y, bw, bh, a = stats[k]
            if a < min_area:
                continue
            if x <= 0 or y <= 0 or x + bw >= inner.shape[1] or y + bh >= inner.shape[0]:
                continue
            if best is None or a > best[4]:
                best = (x, y, bw, bh, a)
        occupied = False
        if best is not None:
            _x, _y, bw, bh, a = best
            frac = a / float(inner.size)
            occupied = (frac >= 0.02 and bw / inner.shape[1] >= 0.06
                        and bh / inner.shape[0] >= 0.22)
        want = truth[i] != '0'
        if want != occupied:
            occ_err += 1
        if want and occupied:
            crop = np.where(mask > 0, 0, 255).astype(np.uint8)
            d, s0, s1 = classify(crop, templates)
            confs.append((s0, s1))
            if d != int(truth[i]):
                digit_err += 1
    ngiv = sum(1 for ch in truth if ch != '0')
    print(f"{path.split('/')[-1]}: occupancy_err={occ_err} digit_err={digit_err} "
          f"(givens={ngiv}, {occ_err + digit_err} total errors)")
    if confs:
        a = np.array(confs)
        print(f"   template confidence: mean best={a[:, 0].mean():.2f} min={a[:, 0].min():.2f} "
              f"mean margin={np.mean(a[:, 0] - a[:, 1]):.2f}")
    return occ_err + digit_err


def main():
    import os
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sample = os.path.join(here, 'app', 'src', 'main', 'assets', 'samples', 'sample1.png')
    truth = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
    tmpl = build_templates()
    errors = run(sample, truth, templates=tmpl)
    return 0 if errors == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
