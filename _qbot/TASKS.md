# QBot → OpenCode task queue

Status: ACTIVE

Rules for OpenCode:
1. Always read this file before making changes.
2. Execute only the current task marked as NEXT.
3. Do not rewrite unrelated architecture.
4. Do not rename DataTypes unless task explicitly says so.
5. Keep DYN static-only.
6. Keep DYNMSG/DYNMSGv2 as message-layer work, separate from DYN.
7. Keep LIVE as compact 3x2 Karoo map HUD, not fullscreen.
8. Never hardcode secrets/tokens.
9. After each task update `_qbot/STATUS.md`.
10. If unsure, inspect code and report findings instead of guessing.

## NEXT

Audit the project structure and report:
- all Kotlin/Java files,
- all XML layout/resource files relevant to Karoo extension,
- registered DataTypes,
- action receivers,
- current gate open implementation,
- current rate limit implementation,
- whether roboto_condensed_medium.ttf is a valid font or corrupted HTML,
- build/test result.

Do not modify code yet, except generating/updating `_qbot/STATUS.md` and `_qbot/AUDIT.md`.

