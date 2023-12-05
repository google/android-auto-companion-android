# Android Auto Companion Library

Library that will abstract away the process of associating a phone with an
Android Auto head unit. Once associated, a device will gain the ability to
unlock the head unit via BLE. The library supports both iOS and Android, the
code for which is contained with the `ios` and `android` directories
respectively.

For usage instructions, follow the
[Phone SDK Integration Guide](https://docs.partner.android.com/gas/integrate/companion_app/cd_phone_sdk).

This repository is intended to release the AAOS Companion Device program as an
open source project.

Use Google Maven to obtain the SDK:


1. Add Google Maven to the app repositories.

```
buildscript {
    repositories {
        google()
    }
}
```

2. Add Companion Device package to the app dependencies.

https://maven.google.com/web/index.html#com.google.android.car.connectionservice:connectionservice

```
dependencies {
    // NOTE: use the latest version.
    implementation 'com.google.android.car.connectionservice:connectionservice:3.1.2'
}
```
