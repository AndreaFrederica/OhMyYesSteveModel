MMD default toon resources from benikabocha/saba, unchanged.
Revision: 29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332
Upstream directory: viewer/Saba/Viewer/resource/mmd
Upstream repository license: MIT, included as LICENSE.txt.
Per-file source links and SHA-256 are in manifest.json.

These are fixed library fallbacks, not user assets. A PMD file's existing
package-relative image takes precedence even when named toon01.bmp etc.
PMX's explicit shared-toon flag selects these resources directly.
Read, decode, sampling and GPU budgets also apply to these images.
