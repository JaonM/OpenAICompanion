# KMP Build Environment

The intended local environment is:

- Gradle Wrapper 8.9
- JDK 22
- Kotlin Multiplatform 2.2.0
- Android Gradle Plugin 8.7.2

The Gradle distribution is pinned in `gradle/wrapper/gradle-wrapper.properties`.
Once the wrapper scripts are generated, use `./gradlew` instead of the global
`gradle` command.

## Apple Silicon setup

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 22)
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$HOME/.gradle/native"
./gradlew --version
```

The native cache is safe to remove; Gradle recreates it for the current
architecture. If `gradlew` is not present yet, use the Homebrew Gradle command
once to generate it:

```bash
gradle wrapper --gradle-version 8.9
```

## Compatibility note

AGP 8.7 officially requires Gradle 8.9 or newer. This module pins the
AGP-compatible Gradle 8.9 Wrapper. The Kotlin and JDK settings are aligned
with Kotlin 2.2.0 and JDK 22.
