`jorbis-0.0.17-sources.jar` is the unmodified corresponding source archive from
https://repo.maven.apache.org/maven2/org/jcraft/jorbis/0.0.17/jorbis-0.0.17-sources.jar.
JOrbis/Jogg are Copyright (C) 2000 ymnk, JCraft, Inc., LGPL-2.0-or-later.
Their notice and full license accompany the prerequisite under `licenses/jorbis-0.0.17/`.

The runtime uses the unmodified Maven binary. Its only distribution transformation
is the `com.jcraft` relocation specified in `forge/build.gradle`. To rebuild or
replace this library, compile the supplied source (or your modifications) with
Java 17, substitute that JAR for the JOrbis dependency in `java-audio/build.gradle`,
and run the ordinary runtime build. Runtime source and Gradle scripts are supplied
for relinking. Source archives are also included as unchanged ZIP bytes with a
`.zip` extension in the prerequisite JAR under
`third-party-sources/`; downstream redistributors must retain them and the notices.

The CPM Bedrock source reader adapts the parsing structure from
Gamepiaynmo/CustomPlayerModel revision c40b0ab28b6a223888f04156a0b827b18b5cf36d
(Copyright (c) 2020 Gamepiaynmo, MIT). The complete license and adaptation notice
are supplied by java-cpm under `licenses/custom-player-model/` and included in
the prerequisite distribution. No upstream model art is distributed.
