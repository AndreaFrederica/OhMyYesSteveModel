# FBX resource mapping sidecar

An FBX model may have an optional sidecar beside the source file:

```text
Avatar.fbx.resources.json
```

The sidecar makes texture relocation explicit when an exporter stored an
absolute workstation path or when multiple files share the same basename:

```json
{
  "schemaVersion": 1,
  "resources": {
    "C:/author/project/Texture/Face.png": "Texture/Face.png"
  }
}
```

Keys are the texture references written in the FBX. Backslashes are accepted.
For a loose `.fbx`, values are model-directory-relative paths and must point to
regular files. For a `.unitypackage`, the same sidecar may sit beside the
archive; values then refer to Unity's serialized `pathname` entries inside the
archive (for example `Assets/Flandre Scarlet/Textures+Normals/Socks.png`).
The importer reads those entries directly and never requires the user to unpack
the package. A listed mapping is authoritative and fails import if its target
is missing.
The same sidecar syntax works beside a normal `.zip` workspace. Its values are
ZIP entry paths, and the importer reads the selected model and textures through
`ZipFile` without creating an extracted workspace.
References without an entry retain the existing exact-relative-path and
basename fallback matching. The sidecar is copied into the converted scene
package and participates in the source identity, so changing it invalidates
the conversion cache. For an archive, changing the sidecar also invalidates
the catalog source stamp and schedules the archive for reconversion.
