#!/usr/bin/env bash
# Writes the videos the instrumented tests play and convert, into android/src/androidTest/assets.
#
# Each is 256 x 144 pixels, 10 frames a second for 1 second, in four quadrants of one colour each, so
# that a test can tell which way up a frame came out and whether its colours survived: red top left,
# green top right, blue bottom left, grey bottom right. They are H.264, tagged BT.709 as phones write
# their videos, with a sine tone as AAC. quadrants-turned.mp4 is the same video stored with a display
# rotation that turns it a quarter clockwise, as a phone held upright records it. They are no smaller
# than that because a hardware decoder refuses a video below a size of its own: Qualcomm's refuses
# 96 x 64.
#
# Needs ffmpeg with libx264. Run it from anywhere; it overwrites the files.
set -euo pipefail

assets="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/src/androidTest/assets"

encoding=(
    -c:v "libx264" -crf "10" -g "10" -bf "0"
    -colorspace "bt709" -color_primaries "bt709" -color_trc "bt709"
    -c:a "aac" -b:a "64k" -shortest -map_metadata "-1" -fflags "+bitexact"
)

sound=(-f "lavfi" -i "sine=frequency=440:sample_rate=44100:duration=1")

# The quadrants, as an ffmpeg lavfi source.
printf -v picture '%s' \
    "color=c=0x808080:s=256x144:r=10:d=1," \
    "drawbox=x=0:y=0:w=128:h=72:c=0xC82828:t=fill," \
    "drawbox=x=128:y=0:w=128:h=72:c=0x28C828:t=fill," \
    "drawbox=x=0:y=72:w=128:h=72:c=0x2828C8:t=fill," \
    "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p"

run() {
    ffmpeg -v "error" -y "$@"
}

mkdir -p "$assets"
upright="$assets/quadrants.mp4"

run -f "lavfi" -i "$picture" "${sound[@]}" "${encoding[@]}" "$upright"

# A display rotation is counter-clockwise: 270 turns the stored frame a quarter clockwise.
run -display_rotation "270" -i "$upright" -c "copy" "$assets/quadrants-turned.mp4"
