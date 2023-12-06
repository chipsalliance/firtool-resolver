# Firtool Resolver and LLVM Firtool Native

Firtool Resolver is a Scala library and command-line application for downloading [LLVM Firtool](https://github.com/llvm/circt) as a Maven artifact.
LLVM Firtool Native is a project to distribute LLVM Firtool as Maven artifacts.

# Firtool Native Dependency Distribution Specification (FNDDS)

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
