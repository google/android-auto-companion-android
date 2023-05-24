# Android Auto Companion Library

Library that will abstract away the process of associating a phone with an
Android Auto head unit. Once associated, a device will gain the ability to
unlock the head unit via BLE. The library supports both iOS and Android, the
code for which is contained with the `ios` and `android` directories
respectively.

For usage instructions, follow the
[Phone SDK Integration Guide](https://docs.partner.android.com/gas/integrate/companion_app/cd_phone_sdk).

## Build Instructions

This project uses gralde to build an Android AAR file as the Auto Companion SDK.

### Prerequisites

The build script was tested in the following environment to build the Companion
Android SDK and the reference app. Other versions of the binary might work but
YMMV.

#### Java

```Shell
$ java -version
openjdk version "17.0.6" 2023-01-17
OpenJDK Runtime Environment Homebrew (build 17.0.6+0)
OpenJDK 64-Bit Server VM Homebrew (build 17.0.6+0, mixed mode, sharing)
```

##### Install

There are many options to install Java (and the rest of the dependencies). This
doc lists two common options: [SDKMAN!](https://sdkman.io/) and
[Homebrew](https://brew.sh/).

```Shell
# Install with sdkman
$ sdk install java 17.0.4.1-tem
# Or install with brew
$ brew install openjdk@17
```

#### Android SDK

Follow [Android instructions](https://developer.android.com/studio) to Install
the Android SDK.

##### API 31

The companion SDK currently targets API 31.
[Install it with Android Studio](https://developer.android.com/about/versions/12/setup-sdk)
or [sdkmanager](https://developer.android.com/studio/command-line/sdkmanager).

Make sure
[$ANDROID_HOME](https://developer.android.com/studio/command-line/variables)
is set in your environment:

```Shell
$ echo $ANDROID_HOME
~/Library/Android/sdk
```

Or set the Android SDK path in your project's local.properties file:

```Shell
sdk.dir=${path_to_android-sdk}
```

#### Gradle

This doc is written with [gradle version 7.6.1](https://gradle.org/releases/).

```Shell
$ gradle --version
------------------------------------------------------------
Gradle 7.6.1
------------------------------------------------------------
Build time:   2023-02-24 13:54:42 UTC
Revision:     3905fe8ac072bbd925c70ddbddddf4463341f4b4
Kotlin:       1.7.10
Groovy:       3.0.13
Ant:          Apache Ant(TM) version 1.10.11 compiled on July 10 2021
JVM:          17.0.6 (Homebrew 17.0.6+0)
OS:           Mac OS X 13.2 x86_64
```

##### Install

Follow [gralde instructions](https://gradle.org/install/) to install, or use
a package manager:

```Shell
# Install with sdkman
$ sdk install gradle 7.6.1
# Or install with brew
$ brew install gradle@7
```

#### Flutter

Optional: only building the companion reference app requires flutter. Follow
[flutter instructions](https://docs.flutter.dev/get-started/install)
to install.

```Shell
$ flutter --version
Flutter 3.7.6 • channel stable • https://github.com/flutter/flutter.git
Framework • revision 12cb4eb7a0 (6 days ago) • 2023-03-01 10:29:26 -0800
Engine • revision ada363ee93
Tools • Dart 2.19.3 • DevTools 2.20.1
```

### Build the Companion SDK

#### Check out the GitHub repository

```Shell
git clone https://github.com/google/android-auto-companion-android && \
cd android-auto-companion-android
```

If you have the SDK repo checked out, **make sure to pull the latest update**.

#### Build

```Shell
./gradlew assemble
```

The SDK should be available as an AAR file at path

```Shell
build/outputs/aar/companion-release.aar
```

## Build the Companion Reference App with the new SDK

Now we have built the companion SDK as an AAR file. The next step is to
integrate it with your companion app. Google has provided a reference app for
your convenience to test this out.

This step is optional. Through the reference app, you can experience the
Companion platform and features, without building a full-fledged app.

### GitHub Repository

Check out the repository of the companion reference app.

```Shell
git clone https://github.com/google/android-auto-companion-app
```

The app repository contains logic for both Android and iOS app. We want to
build the Android app so we go into `android` directory.

```Shell
# Go into the android reference app directory.
cd android-auto-companion-app/android
```

Copy the generated aar file into the reference app repository.

```Shell
# First make the directory for libs.
android-auto-companion-app/android$ mkdir -p app/src/main/libs
# Copy the generated AAR file.
android-auto-companion-app/android$ cp <path-to-sdk-repo>/android-auto-companion-android/build/outputs/aar/companion-release.aar app/src/main/libs/
```

Edit `app/build.gradle`.

The required change happens at
[the last line in the file](https://github.com/google/android-auto-companion-app/blob/main/android/app/build.gradle#L94).
We'll use the local library instead of downloading from
[GMaven](https://maven.google.com/web/index.html#com.google.android.car.connectionservice:connectionservice).

**Original**
```Text
// The ConnectedDeviceManager itself.
implementation 'com.google.android.car.connectionservice:connectionservice:2.0.8'
```

**Replace with**

```Text
// The ConnectedDeviceManager itself.
implementation(name:'companion-release', ext:'aar')
```

Build the apk and install it.

```Shell
android-auto-companion-app/android$ flutter pub get
android-auto-companion-app/android$ ./gradlew assemble
android-auto-companion-app/android$ adb install ../build/app/outputs/flutter-apk/app-release.apk
```

You can validate the apk by going through basic companion use cases such as
association, reconnection, unlocking.
