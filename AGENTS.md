# AGENTS.md

Edendale is a free, open-source video player and personal watch/review tracker.
Each supported platform is developed on an independent branch with its own
source, tests, dependencies, and CI/CD.

Read [README.md](README.md) for the product and branch model, and
[DESIGN.md](DESIGN.md) for the shared design language. Follow
[MODEL.md](MODEL.md) only when a prompt explicitly references it.

## Branch ownership

| Branch | Allowed scope |
|---|---|
| `main` | Universal documentation and repository metadata |
| `apple` | Apple implementation and delivery |
| `android` | Android implementation and delivery |
| `windows` | Windows implementation and delivery |
| `web` | Static Web implementation and delivery |

Before changing files, identify the active branch and inspect its manifests,
toolchain files, and README. Never assume that commands or directory layouts
from another branch apply.

## Working rules

1. Keep a task within the active branch's platform unless the user explicitly
   requests coordinated work across branches.
2. Do not add platform source, build configuration, generated output, or
   deployment workflows to `main`.
3. Keep source, tests, dependency locks, CI, packaging, and release
   configuration self-contained on the owning platform branch.
4. Document exact build, test, and release commands in the platform branch's
   README when its implementation is introduced or changed.
5. Prefer the active platform's native frameworks, language, persistence,
   accessibility, navigation, and testing tools.
6. Do not create `TASKS.md`; use issues and pull requests for work tracking.

## Hard constraints

1. **Native platform boundaries.** Never add a shared executable runtime,
   cross-platform domain module, generated bridge, or binary dependency on
   another Edendale branch.
2. **Branch-isolated delivery.** CI for one platform must not check out, build,
   test, package, deploy, or require credentials for another platform.
3. **Secrets stay private.** Never hardcode, commit, log, publish, or expose
   credentials. Use gitignored local configuration and protected CI
   environments.
4. **Privacy.** Do not add analytics or an Edendale account. Network access is
   limited to documented metadata, playback, and user-controlled sync/storage
   services. Trailer playback requires an explicit user action.
5. **Classify before network.** Native applications parse filenames locally;
   metadata enrichment stays off the import fast path.
6. **Separate local and portable data.** Device-specific library paths and
   access grants stay local. If watch state is synced, keep it independent from
   the local library and use a documented user-controlled service.
7. **Behavioral parity is explicit, not shared.** A product-rule change must
   identify the other affected platform branches. Implement and test the rule
   natively on each branch rather than sharing executable code.
8. **Static Web boundary.** The Web branch may provide product information,
   privacy content, downloads, and verified-link association. It must not host
   a media library, player backend, credentialed metadata proxy, watch tracker,
   or user account.

## Design and implementation

- Use the semantic tokens and interaction rules in [DESIGN.md](DESIGN.md);
  express them through native platform resources.
- Preserve keyboard, pointer, touch, remote, screen-reader, and reduced-motion
  behavior where the active platform supports them.
- Keep content readable when artwork is bright, missing, or still loading.
- Add new semantic tokens deliberately; do not scatter arbitrary production
  color values through application code.
- Keep imported media responsive by separating local persistence from
  background metadata work.

## Verification

Run the smallest complete set of native checks documented by the active
branch. For CI/CD changes, also validate branch filters, artifact ownership,
secret boundaries, and release gating. Report commands actually run and any
checks that could not run in the current environment.

Documentation-only changes on `main` require no platform build, but links,
branch names, and the absence of platform code must still be verified.
