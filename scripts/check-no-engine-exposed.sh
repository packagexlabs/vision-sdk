#!/bin/bash
# Fails if the PackageX barcode engine is exposed in what ships. The engine is linked statically
# and hidden inside VisionSDK.framework; nothing of it may be a file, a module, a symbol or a
# readable name of a shipped artifact, and its detector model may not be a plain file.
#
#   scripts/check-no-engine-exposed.sh <path>...
#
# Every path is a file or a directory (an xcframework, an unzipped release, the Sources/ of the
# public repo, VisionSDK.podspec, Package.swift). Run it on what is about to be shipped, never on
# the private source tree, which holds the engine on purpose. It is the same file in
# vision-sdk-ios and vision-sdk; run it locally the way the workflows do.
#
# What fails:
#   - a path named BarcodeScannerKit*, VSDKCore* (the module of the static build) or *.tflite,
#     or a *.swiftmodule / *.swiftinterface of either
#   - a text or interface file (podspec, Package.swift, .swiftinterface, .abi.json, .swiftdoc, .h,
#     .plist, .modulemap, ...) that names BarcodeScannerKit, VSDKCore or BarcodeCoreBridge
#   - a binary (Mach-O or archive) whose exported symbols name the engine, its bridge, its
#     shared module or zxing-cpp, or whose readable strings name BarcodeScannerKit,
#     BarcodeCoreBridge or aab.tflite
# What stays readable in VisionSDK's own binary, and is not checked: the names of the engine's
# internal Swift classes and of the Kotlin classes, which the runtimes need. See the notes of
# the release.
set -u

[ "$#" -gt 0 ] || { echo "usage: $0 <path>..." >&2; exit 2; }

failed=0
fail() { echo "EXPOSED: $*" >&2; failed=1; }

PATH_RE='BarcodeScannerKit|VSDKCore|BarcodeCoreBridge'
TEXT_RE='BarcodeScannerKit|VSDKCore|BarcodeCoreBridge'
STRINGS_RE='BarcodeScannerKit|BarcodeCoreBridge|aab\.tflite'
EXPORT_RE='BarcodeScannerKit|VSDKCore|BarcodeCoreBridge|NativeEngine|ModelVault|PXBarcode|BarcodePipeline|kfun:|Kotlin_|ZXing|zxing|ReadBarcode'

check_file() {
    local f=$1 name
    name=$(basename "$f")
    if echo "$f" | grep -Eiq "$PATH_RE"; then
        fail "$f: the path names the engine"
    fi
    case "$name" in
        *.tflite) fail "$f: a model file" ;;
        *.swiftmodule|*.swiftinterface)
            if grep -aEq "$TEXT_RE" "$f"; then fail "$f: an interface that names the engine"; fi ;;
    esac
    if file -b "$f" | grep -Eq 'Mach-O|ar archive'; then
        if nm -gU "$f" 2>/dev/null | grep -Eq "$EXPORT_RE"; then
            fail "$f: exported symbols of the engine:"
            nm -gU "$f" 2>/dev/null | grep -E "$EXPORT_RE" | head -5 >&2
        fi
        if strings -a "$f" | grep -Eq "$STRINGS_RE"; then
            fail "$f: readable names of the engine:"
            strings -a "$f" | grep -E "$STRINGS_RE" | sort -u | head -5 >&2
        fi
    elif grep -aEq "$TEXT_RE" "$f"; then
        fail "$f: names the engine: $(grep -aEo "$TEXT_RE" "$f" | sort -u | tr '\n' ' ')"
    fi
}

for root in "$@"; do
    if [ ! -e "$root" ]; then
        fail "$root: does not exist"
    elif [ -d "$root" ]; then
        # A directory whose own name is the engine's counts as well (BarcodeScannerKit.framework)
        while IFS= read -r entry; do
            if echo "$(basename "$entry")" | grep -Eiq "$PATH_RE"; then
                fail "$entry: the path names the engine"
            fi
        done < <(find "$root" -type d)
        while IFS= read -r f; do
            check_file "$f"
        done < <(find "$root" -type f ! -name .DS_Store)
    else
        check_file "$root"
    fi
done

if [ "$failed" -eq 0 ]; then
    echo "OK: the engine is not exposed in: $*"
fi
exit "$failed"
