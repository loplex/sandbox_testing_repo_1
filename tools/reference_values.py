"""Print what the desktop dog-vision computes, as the reference core's tests compare against.

The desktop dog-vision is https://github.com/loplex/dog-vision. From a checkout of it, run

    uv run --project <checkout> python tools/reference_values.py > core/src/test/resources/reference.tsv

Each line is a kind of value, the species and the chroma scale it is for, and the values, tab-separated.
"""

import numpy as np

from dog_vision.core.model import CHROMA_SCALES, Params, neutral_point, rnl_gains, simulation_matrix
from dog_vision.core.species import SPECIES

SCENE_MEAN = np.array([0.8, 0.3, 0.1])  # a warm scene, for the adaptation


def row(kind: str, species: str, scale: str, values) -> str:
    return "\t".join([kind, species, scale, *(repr(float(v)) for v in np.ravel(values))])


for species in SPECIES:
    for scale in CHROMA_SCALES:
        print(row("simulation", species, scale, simulation_matrix(Params(species, chroma_scale=scale))))
        adapted = Params(species, chroma_scale=scale, adaptation=0.5, strength=0.75)
        print(row("adapted", species, scale, simulation_matrix(adapted, SCENE_MEAN)))
    print(row("rnl-gains", species, "-", rnl_gains(Params(species))))
    if len(SPECIES[species]) == 2:
        print(row("neutral-point", species, "-", [neutral_point(Params(species))]))
