# Firtool Resolver and LLVM Firtool Native

Firtool Resolver is a Scala library and command-line application for resolving and downloading [LLVM Firtool](https://github.com/llvm/circt).
Starting with **2.2.0**, it downloads binaries directly from CIRCT's GitHub releases and verifies their SHA-256 checksums. It supports Scala 2.12 and 2.13, with the existing `Resolve`, `FirtoolBinary`, and `Logger` APIs.

LLVM Firtool Native (`org.chipsalliance:llvm-firtool`) and FNDDS are **legacy** distribution mechanisms. The last version published to Maven Central is **1.162.0**; existing artifacts and classpath overrides continue to work.

## Using Firtool Resolver

```scala
import firtoolresolver.Resolve

Resolve("1.163.0") match {
  case Right(binary) => println(binary.path)
  case Left(error) => sys.error(error)
}
```

The command-line interface is `firtool-resolver [-v] <version>`. For example:

```sh
cs launch --scala 2.13 org.chipsalliance::firtool-resolver:2.2.0 \
  --main firtoolresolver.Main -- -v 1.163.0
```

### Resolution order

1. Run `firtool` from the directory specified by `CHISEL_FIRTOOL_PATH`. An invalid path or a binary that cannot run is an unrecoverable error, as before.
2. Choose the effective version: system property `chisel.firtool.version`, then environment variable `CHISEL_FIRTOOL_VERSION`, then the caller's default version. An explicit override skips the classpath lookup and warns if `llvm-firtool` is also on the classpath.
3. If no explicit version is set, honor `llvm-firtool` on the classpath. This legacy mechanism logs a deprecation message at info level.
4. Reuse the cached binary at `<cache>/<effective-version>/bin/firtool`.
5. Download the effective version from GitHub, verify its checksum, and atomically install just the executable in the cache. Downloads are locked per version across processes.
6. As a last resort, fetch `llvm-firtool` with Coursier from Maven. This supports older versions and environments that can reach a Maven proxy but cannot reach GitHub; it only works for already-published artifacts.

### Configuration

| Setting | System property | Environment variable | Default |
|---|---|---|---|
| Executable directory | — | `CHISEL_FIRTOOL_PATH` | Unset |
| Version override | `chisel.firtool.version` | `CHISEL_FIRTOOL_VERSION` | Caller's default version |
| Release base URL / mirror | `chisel.firtool.url` | `CHISEL_FIRTOOL_URL` | `https://github.com/llvm/circt/releases/download` |
| Cache directory | — | `CHISEL_FIRTOOL_CACHE` | OS-specific project cache directory |

Properties take precedence over the corresponding environment variables. Version strings must contain only ASCII letters, digits, `.`, `+`, `_`, or `-`, and cannot be `.` or `..`.

Direct downloads require access to **github.com** and **release-assets.githubusercontent.com**. Standard JVM proxy properties (`https.proxyHost`, `https.proxyPort`, and the corresponding HTTP properties for HTTP mirrors) are honored.

To use an internal HTTP server, an Artifactory generic remote, or a local `file:` mirror, set the release base URL. The mirror must provide both the archive and its `.sha256` file at:

```text
<base>/firtool-<version>/firrtl-bin-<platform>.<ext>
<base>/firtool-<version>/firrtl-bin-<platform>.<ext>.sha256
```

| Resolver platform | Release asset |
|---|---|
| `linux-x64` | `firrtl-bin-linux-x64.tar.gz` |
| `macos-x64` | `firrtl-bin-macos-x64.tar.gz` |
| `macos-aarch64` | `firrtl-bin-macos-arm64.tar.gz` |
| `windows-x64` | `firrtl-bin-windows-x64.zip` |

GitHub fetching supports versions starting at **1.45.0**. Native macOS arm64 assets start at **1.144.0**; earlier versions fall back to macOS x64 through Rosetta. Older releases remain available through the legacy Maven path.

Checksum files may contain just the hex digest or a digest followed by a filename. Checksums detect corrupt downloads and broken mirrors; because they come from the same origin as the archive, they do not authenticate a compromised release. A downloaded binary reporting a different version logs a warning.

### Development and release

Run `./mill -i 'firtool-resolver[_].test'` for the hermetic MUnit resolver tests on both Scala versions, or `./mill -i 'firtool-resolver[2.13].test'` for just Scala 2.13.

The FileCheck integration suite is `./firtool-resolver/test/run_tests.sh <linux|macos|windows> <2.12|2.13>`; it requires Coursier, FileCheck, and network access, along with `LLVM_FIRTOOL_VERSION` and `LLVM_FIRTOOL_PRERELEASE` for the retained legacy `publishLocal` build.

# Firtool Native Dependency Distribution Specification (FNDDS) (legacy)

This specification follows [Semantic Versioning 2.0.0](https://semver.org).

**Specification version: 1.0.0**

1. `Platforms` are defined to be a tuple of a `operating system` and an `architecture` written as `<os>-<arch>` which can also be written as `<platform>`.
1. The defined operating systems are `linux`, `windows`, and `macos`.
1. The defined architectures are `x64` and `aarch64`.
1. A Project SHALL have a `groupId`, `artifactId`, and `version`.
1. A Project MAY support any number of `Platforms`.
1. Artifacts SHALL be published as a [Maven Artifact](https://maven.apache.org/repositories/artifacts.html)
    1. The Maven Artifact `groupId` SHALL be the same as the project `groupId`.
    1. The Maven Artifact `artifactId` SHALL be the project `artifactId`.
    1. The Maven Artifact `version` SHALL be the project `version` and MAY include a suffix starting with `-`.
    1. The Maven Artifact MAY include additional jars distinguished by classifiers corresponding to supported `Platforms`.
1. The Maven Artifact SHALL contain a `baseDirectory` defined to be `<groupId>/<artifactId>`.
1. The Maven Artifact SHALL contain one or more `artifactDirectories` defined to be `<baseDirectory>/<platform>` (for each supported `Platform`).
1. The `baseDirectory` SHALL contain a file called `FNDDS.version` containing only the version of this specification adhered to by the artifact, encoded using UTF-8.
1. The `baseDirectory` SHALL contain a file called `project.version` containing only the `version` of the artifact, encoded using UTF-8.
1. Maven Artifacts published for various `Platforms` for a single Artifact must conform to the same `FNDDS.version` and `project.version`.
1. The `artifactDirectory` for each supported `Platform` MAY contain any other project-specific files including executables compiled for the specific `<platform>`.

## Revision History

### 1.0.0

Initial Version

## Commentary on the specification

The purpose of this specification is to enable distributing native executables (especially Firtool) as standard Maven artifacts.
It is indended to help bridge the gap between the cross-platform flexibility of JVM applications with the platform-specific requirements of native executables.
As such, it is important that executables for different platforms can co-exist on the same Java classpath so that JVM applications may be distributed with a multitude of native libraries so they can appear to be platform-agnostic despite the native dependency.
It may seem strange that the `FNDDS.version` file exists in the `baseDirectory` such that it will be clobbered by related artifacts for different architectures.
This design decision is a compromise--it is expected that the `Platform` specification will evolve to support more platforms while all `Maven Artifacts` for a specific `Artifact` must have the same `FNDDS.version`.
