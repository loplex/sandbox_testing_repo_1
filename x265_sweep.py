"""libx265 through ffmpeg, over small frame sizes: which ways of encoding a size fail.

Each line of the output: WxH variant run status seconds.
Variants:
  probe3      bestEncoder's probe: 3 grey frames from lavfi through VIDEO_FILTER, to null
  gray30      the same, 30 frames
  pipe3null   black 0rgb frames from a pipe, as VideoWriter sends them, 3 frames, to null
  pipe30null  the same, 30 frames, to null
  pipe30mp4   VideoWriter's command exactly: 30 black frames from a pipe, to an .mp4
"""
import argparse
import concurrent.futures
import os
import subprocess
import sys
import tempfile
import time

FILTER = ("pad=ceil(iw/2)*2:ceil(ih/2)*2,"
          "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p")
X265 = ["-c:v", "libx265", "-crf", "24", "-preset", "medium", "-x265-params", "log-level=error"]
WRITER_TAIL = ["-tag:v", "hvc1", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
               "-movflags", "+faststart"]
VARIANTS = ["probe3", "gray30", "pipe3null", "pipe30null", "pipe30mp4"]
TIMEOUT = 120


def command(variant, w, h, out):
    if variant.startswith("probe") or variant.startswith("gray"):
        frames = 3 if variant == "probe3" else 30
        duration = "0.1" if frames == 3 else "1.2"
        return (["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"color=c=gray:s={w}x{h}:d={duration}",
                 "-frames:v", str(frames), "-vf", FILTER] + X265 + ["-f", "null", "-"]), None
    frames = 3 if variant == "pipe3null" else 30
    head = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "0rgb", "-s", f"{w}x{h}", "-r", "30",
            "-i", "-", "-map", "0:v", "-vf", FILTER] + X265
    tail = WRITER_TAIL + [out] if variant == "pipe30mp4" else ["-f", "null", "-"]
    return head + tail, bytes(w * h * 4 * frames)


def run(job, workdir):
    (w, h), variant, rep = job
    out = os.path.join(workdir, f"{w}x{h}-{variant}-{rep}.mp4")
    cmd, data = command(variant, w, h, out)
    start = time.monotonic()
    try:
        p = subprocess.Popen(cmd, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            _, err = p.communicate(data, timeout=TIMEOUT)
            status = str(p.returncode)
        except subprocess.TimeoutExpired:
            p.kill()
            _, err = p.communicate()
            status = "timeout"
    except OSError as e:
        status, err = f"oserror:{e.errno}", b""
    seconds = time.monotonic() - start
    if os.path.exists(out):
        os.remove(out)
    tail = err.decode(errors="replace").strip().splitlines()[-1:] if err else []
    return f"{w}x{h} {variant} {rep} {status} {seconds:.2f}" + (f" | {tail[0]}" if tail and status != "0" else "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shard", default="0/1")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--sizes", default="8-96x8-72", help="WMIN-WMAXxHMIN-HMAX, even sides, or WxH,WxH,...")
    parser.add_argument("--variants", default=",".join(VARIANTS))
    parser.add_argument("--workers", type=int, default=os.cpu_count())
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    if "-" in args.sizes:
        ws, hs = args.sizes.split("x")
        (w0, w1), (h0, h1) = (map(int, ws.split("-")), map(int, hs.split("-")))
        sizes = [(w, h) for w in range(w0, w1 + 1, 2) for h in range(h0, h1 + 1, 2)]
    else:
        sizes = [tuple(map(int, s.split("x"))) for s in args.sizes.split(",")]
    index, count = map(int, args.shard.split("/"))
    sizes = sizes[index::count]
    jobs = [(s, v, r) for r in range(args.runs) for s in sizes for v in args.variants.split(",")]
    print(f"{len(sizes)} sizes, {len(jobs)} runs, {args.workers} workers", flush=True)

    failed = 0
    with tempfile.TemporaryDirectory() as workdir, open(args.out, "w") as log, \
            concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        for line in pool.map(lambda job: run(job, workdir), jobs):
            log.write(line + "\n")
            if line.split()[3] != "0":
                failed += 1
                print(line, flush=True)
    print(f"done: {failed} of {len(jobs)} runs failed", flush=True)


if __name__ == "__main__":
    sys.exit(main())
