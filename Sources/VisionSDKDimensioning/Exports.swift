//
//  Exports.swift
//  VisionSDKDimensioning
//
//  Module-internal import of MVDimensioningCore. NOT re-exported —
//  consumers of VisionSDKDimensioning must use the VSDK-prefixed
//  wrapper types, not the raw MVDimensioning surface.
//

#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_implementationOnly import MVDimensioningCore
#endif
