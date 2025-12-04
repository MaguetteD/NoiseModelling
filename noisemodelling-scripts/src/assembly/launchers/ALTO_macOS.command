#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$DIR/noisemodelling/scripts"
"$DIR/bin/NoiseModelling_With_GUI" -scripts="$SCRIPTS_DIR"
