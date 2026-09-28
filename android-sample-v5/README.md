# VisionSDK v5 Sample (android-sample-v5)

A Jetpack Compose sample app demonstrating VisionSDK v2.7.0 (Hilt-based DI, single-module).

## Build and run

```bash
export JAVA_HOME=/Users/kashif/Library/Java/JavaVirtualMachines/jdk-17.0.19+10/Contents/Home
cd android-sample-v5
cp secrets.properties.example secrets.properties
# then fill in STAGING_API_KEY / PRODUCTION_API_KEY in secrets.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`local.properties` (git-ignored) must contain:

```
sdk.dir=/Users/kashif/Library/Android/sdk
```

### Secrets

`secrets.properties` (git-ignored, copy from `secrets.properties.example`) supplies:

- `VISION_ENV` — `staging` or `production` (defaults to `staging`)
- `STAGING_API_KEY`
- `PRODUCTION_API_KEY`

Values can also be supplied via environment variables of the same name, which take precedence
over `secrets.properties`.

### Consuming vision-sdk-android assets locally

The UVDoc dewarp model (`uvdoc_fp16.tflite`, ~15 MB) and the `docscanner-release.aar` are not
committed here; they're copied/referenced from a sibling `vision-sdk-android` checkout at build
time. Override the location with `-PvisionSdkAndroidDir=<path>` if your checkout isn't at
`../vision-sdk-android` relative to this repo's parent directory.
