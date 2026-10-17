# FBX source mapping sidecar

An FBX model may be accompanied by `<model filename>.fbxmap.json`, for example
`avatar.fbx.fbxmap.json`. The same sidecar may be placed beside a
`.unitypackage` (`avatar.unitypackage.fbxmap.json`) or a normal workspace ZIP
(`avatar.zip.fbxmap.json`); it is applied to the FBX selected from the archive.
The importer packages it as
`_omysm/fbx-mapping.json`.

This file is deliberately separate from `<model filename>.omysm.json`:

- the ordinary profile maps source bones and clips to YSM roles and controls
  presentation, retargeting, actions, sockets, and features;
- the FBX mapping resolves source ambiguity before those semantics are built.

The first schema selects one `FbxSkin` for each mesh that has multiple skins:

```json
{
  "schemaVersion": 1,
  "sourceHash": "",
  "skinByMeshElement": {
    "1234": 5678
  }
}
```

Keys are FBX mesh element IDs. Values may be the attached Skin's element ID or
typed ID. A selected Skin must already belong to that mesh. An empty mapping
preserves the source selection, while a non-equivalent unselected Skin is
rejected instead of being silently added to the chosen Skin. `sourceHash`, when
present, is the lowercase SHA-256 of the original FBX bytes.

For an archive, `sourceHash` refers to the selected embedded FBX bytes; leave
it empty when the hash is not known. The mapping participates in package
parsing and geometry cache keys. Changing
the FBX bytes invalidates the complete package; changing this sidecar rebuilds
the selected Skin and derived geometry while leaving the original FBX bytes
unchanged.
