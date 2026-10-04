"""Prepare a marked build copy; the hash-verified upstream cache remains untouched."""
import hashlib
import json
from pathlib import Path
import shutil
from fetch_bullet import ROOT, REVISION


OLD = b'\t\t// pair adjacent nodes into new(parent) node\n'
NEW = b'''\t\t// YSM repair: disconnected component roots have no adjacent partner.
\t\t// Add BVH-only adjacency once components stop connecting. This does not
\t\t// add physical links, merge vertices or disable soft-body collisions.
\t\tbool hasNeighbor = false;
\t\tfor (int i = 0; i < N; ++i) hasNeighbor = hasNeighbor || adj[i].size() != 0;
\t\tif (!hasNeighbor)
\t\t{
\t\t\tfor (int i = 1; i < N; ++i)
\t\t\t{
\t\t\t\tadj[i - 1].push_back(i);
\t\t\t\tadj[i].push_back(i - 1);
\t\t\t}
\t\t}
\t\t// pair adjacent nodes into new(parent) node
'''


def prepare(upstream):
    relative = 'src/BulletSoftBody/btSoftBody.cpp'
    original = (upstream / relative).read_bytes()
    if original.count(OLD) != 1:
        raise RuntimeError('Pinned Bullet disconnected-BVH repair no longer matches')
    patched = original.replace(OLD, NEW)
    manifest = {'revision': REVISION, 'file': relative,
                'upstream_sha256': hashlib.sha256(original).hexdigest(),
                'patched_sha256': hashlib.sha256(patched).hexdigest(),
                'repair': 'Terminate BVH construction for disconnected soft face/node components'}
    destination = ROOT / 'build/engine' / ('bullet-ysm-' + REVISION)
    marker = destination / 'ysm-patches.json'
    if marker.exists() and json.loads(marker.read_text()) == manifest:
        files = json.loads((upstream / 'source.json').read_text())['files']
        for name, expected in files.items():
            if name == relative:
                expected = manifest['patched_sha256']
            if hashlib.sha256((destination / name).read_bytes()).hexdigest() != expected:
                raise RuntimeError('Modified patched Bullet source: ' + name)
        return destination
    shutil.copytree(upstream, destination, dirs_exist_ok=True)
    (destination / relative).write_bytes(patched)
    marker.write_text(json.dumps(manifest, indent=2) + '\n')
    return destination
