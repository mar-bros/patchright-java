## build.sh
[build.sh](build.sh)
```bash
bash scripts/build.sh \
  1.58.0 \
  cc.marbro.patchright
```

## jitpack.sh
[jitpack.sh](jitpack.sh) is the JitPack entry point declared in [jitpack.yml](../jitpack.yml). It runs `build.sh` with the Playwright version taken from the requested tag (`v1.64.0` → `1.64.0`), or with the submodule version for commits and branch snapshots, and compiles with `--release 8` on JDK 25.
```bash
VERSION=v1.64.0 bash scripts/jitpack.sh
```
