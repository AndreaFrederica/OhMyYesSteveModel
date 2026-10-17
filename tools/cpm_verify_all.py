#!/usr/bin/env python3
"""Run the source/YSM offline contract over every local CPM model."""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COLLECTION = Path(r"C:\Users\qwe17\OneDrive\backup\CPM-mod-CPM-mod\CPM-Collection")

def slug(path: Path) -> str:
    # Keep trailing replacement underscores so names match the Java batch
    # dumper's deterministic `replaceAll` file names.
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", path.name)

def run(cmd, log: Path, env: dict[str, str], timeout: int = 180) -> tuple[bool, str]:
    try:
        p = subprocess.run(cmd, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout)
        log.write_text(p.stdout, encoding="utf-8", errors="replace")
        return p.returncode == 0, p.stdout[-4000:]
    except subprocess.TimeoutExpired as e:
        text = (e.stdout or "") if isinstance(e.stdout, str) else ""
        log.write_text(text + "\nTIMEOUT\n", encoding="utf-8", errors="replace")
        return False, "TIMEOUT"

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", type=Path, default=COLLECTION)
    ap.add_argument("--output", type=Path, default=ROOT / "build" / "cpm-all")
    ap.add_argument("--kind", choices=("all", "legacy", "modern"), default="all",
                    help="verify one CPM source family without changing its parser")
    ap.add_argument("--render", action="store_true", help="also render source and YSM thumbnails")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--resume", action="store_true", help="reuse existing dump/source/compare artifacts")
    ap.add_argument("--recheck", action="store_true", help="reuse dumps but regenerate source, compare, and render artifacts")
    a = ap.parse_args()
    java = Path(r"C:\Users\qwe17\.jdks\corretto-17.0.4.1")
    env = os.environ.copy(); env["JAVA_HOME"] = str(java)
    env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")
    legacy = [("legacy", p) for p in sorted((a.collection / "Legacy-Models").iterdir()) if p.is_dir()]
    modern = [("modern", p) for p in sorted((a.collection / "Remake-Models").iterdir()) if p.is_dir()]
    models = legacy + modern if a.kind == "all" else (legacy if a.kind == "legacy" else modern)
    if a.limit: models = models[:a.limit]
    rows = []; started = time.time()
    for index, (kind, model) in enumerate(models, 1):
        ident = slug(model)
        out = a.output / kind; out.mkdir(parents=True, exist_ok=True)
        dump = out / f"{ident}.json"; source_png = out / f"{ident}-source.png"; ysm_png = out / f"{ident}-ysm.png"
        compare = out / f"{ident}-compare.json"
        row = {"kind": kind, "model": model.name, "path": str(model), "status": "fail"}
        print(f"[{index}/{len(models)}] {kind} {model.name}", flush=True)
        if a.resume and not a.recheck and dump.exists() and source_png.exists() and source_png.with_suffix('.json').exists() and compare.exists():
            result = json.loads(compare.read_text(encoding="utf-8"))
            # Reports written before the parent graph contract was added are
            # stale even when their corner numbers happened to pass.
            reusable = isinstance(result.get("parentGraph"), dict)
            if not reusable:
                result = None
        else:
            result = None
        if result is not None:
            row.update(status="pass" if all(v == "pass" for k,v in result.get("stageChecks", {}).items() if k not in ("mirror_source_entries",)) else "fail",
                       maxCornerError=result.get("maxCornerError"), stageChecks=result.get("stageChecks"), compare=str(compare), reused=True)
            rows.append(row)
            continue
        if a.resume and dump.exists():
            ok, tail = True, "reused dump"
        elif a.resume and (out / f"{ident}.error.txt").exists():
            row.update(stage="dump", detail=(out / f"{ident}.error.txt").read_text(encoding="utf-8", errors="replace"))
            rows.append(row)
            continue
        else:
            ok, tail = run([str(ROOT / "gradlew.bat"), "cpmYsmDump", f"-Pcpm.modelDir={model}",
                            f"-Pcpm.output={dump}", "--no-configuration-cache"],
                           out / f"{ident}-dump.log", env, timeout=900)
        if not ok or not dump.exists(): row.update(stage="dump", detail=tail); rows.append(row); continue
        ok, tail = run([sys.executable, str(ROOT / "tools" / "cpm_source_render.py"), str(model),
                        "--kind", kind, "-o", str(source_png), "--size", "220",
                        "--pose-json", str(dump)],
                       out / f"{ident}-source.log", env)
        source_json = source_png.with_suffix(".json")
        if not ok or not source_json.exists(): row.update(stage="source", detail=tail); rows.append(row); continue
        ok, tail = run([sys.executable, str(ROOT / "tools" / "cpm_ysm_compare.py"), str(source_json), str(dump),
                        "--model-dir", str(model), "-o", str(compare)],
                       out / f"{ident}-compare.log", env)
        if not ok or not compare.exists(): row.update(stage="compare", detail=tail); rows.append(row); continue
        result = json.loads(compare.read_text(encoding="utf-8"))
        row.update(status="pass" if all(v == "pass" for k,v in result.get("stageChecks", {}).items() if k not in ("mirror_source_entries",)) else "fail",
                   maxCornerError=result.get("maxCornerError"), stageChecks=result.get("stageChecks"), compare=str(compare))
        if a.render:
            run([sys.executable, str(ROOT / "tools" / "cpm_ysm_render.py"), str(model), str(dump),
                 "-o", str(ysm_png), "--size", "220"], out / f"{ident}-ysm-render.log", env)
        rows.append(row)
    summary = {"collection": str(a.collection), "count": len(rows), "elapsedSeconds": time.time()-started,
               "pass": sum(r.get("status") == "pass" for r in rows), "fail": sum(r.get("status") != "pass" for r in rows),
               "models": rows}
    a.output.mkdir(parents=True, exist_ok=True)
    (a.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("count", "pass", "fail", "elapsedSeconds")}, ensure_ascii=False))
    return 0 if summary["fail"] == 0 else 2

if __name__ == "__main__":
    raise SystemExit(main())
