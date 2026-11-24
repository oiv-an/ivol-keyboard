#!/bin/sh
# Downloads the sherpa-onnx Android AAR used for on-device speech recognition.
# The AAR is ~52 MB and is therefore not committed to this repository.
#
# Usage: ./tools/fetch-sherpa-onnx.sh
set -eu

VERSION="2.0.3"
AAR="sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${AAR}"

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="${ROOT}/app/libs"

mkdir -p "${DEST}"
if [ -f "${DEST}/${AAR}" ]; then
    echo "${AAR} already present in app/libs/"
    exit 0
fi

echo "Downloading ${AAR} ..."
curl -fL --progress-bar -o "${DEST}/${AAR}.part" "${URL}"
mv "${DEST}/${AAR}.part" "${DEST}/${AAR}"
echo "Saved to app/libs/${AAR}"
