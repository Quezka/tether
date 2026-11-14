# tether

## what
phone link app (user ask 2026-11-14: "like windows my phone"): notif mirror + reply, sms, calls, files, clipboard
computer src = `../suite-rs/crates/tether` (rust, kit ui); phone src = `app/` (kotlin compose, minSdk 28); ci build commit @ `suite-rs.ref`
protocol = `docs/PROTOCOL.md`; change both side together; rust e2e test `crates/tether/tests/link.rs`; kotlin `ProtocolTest` key = python ref
## build
android: `JAVA_HOME=~/.config/craft-anything/engines/jdk/jdk-21.0.12.1+1 ANDROID_HOME=~/.local/share/android-dev/sdk ./gradlew testDebugUnitTest`
signing key @ `~/.local/share/tether-signing/` + gh secret `TETHER_KEYSTORE_B64`, `TETHER_KEYSTORE_PASSWORD`; ! never commit key
release: bump `crates/tether/Cargo.toml` + `app/build.gradle.kts` versionName/Code + CHANGELOG + metainfo -> tag `vX.Y.Z`
## risk
untested on real phone by claude; sms/calls need user grant; MIUI may kill bg service -> user: autostart + no battery limit
