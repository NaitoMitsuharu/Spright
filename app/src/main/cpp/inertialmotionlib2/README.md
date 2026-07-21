# InertialMotionLib2 integration

Spright vendors only the InertialMotionLib2 JNI boundary required for BLE IMU
attitude estimation. The files were imported from
`EdgeAI-BestPractices/sprbox-SDK` commit
`c1d5bc14d407d51102767b4188867fc6ea620e91`.

The Kotlin object intentionally keeps the JVM name
`jp.co.sony.sprbox.imuadv.internal.InertialMotionLib`. JNI entry-point names in
`inertialmotionlib2_jni.c` contain that package and class name, so changing it
requires changing every corresponding native symbol.

`libinertialmotionlib2.so` is a prebuilt arm64-v8a library. Its legacy ABI uses
`float` for the historical `IML_FLOAT64` alias. Do not change that alias to
`double`; doing so changes the structures exchanged with the prebuilt binary.
The vendored binary SHA-256 is
`40723a1b90bbdd55a6d9b9743422f80ff093117566c1df78784abde1f4a95bae`.

The prebuilt library currently has 4 KB ELF LOAD alignment. A new upstream
binary built for 16 KB Android pages is required before 16 KB page-size support
can be claimed.
