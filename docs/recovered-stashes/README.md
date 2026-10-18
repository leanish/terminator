# Recovered local work

These patches preserve seven local stashes from September 2025 through February
2026 before the obsolete local checkout is removed. They are historical source
material, not patches to apply indiscriminately to current main.

| Stash | Topic | Current disposition |
| --- | --- | --- |
| 0 | Java conventions migration, build setup and patch grouping | Superseded by current conventions and dependency automation; do not downgrade the build or restore retired Dependabot configuration. |
| 1 | Annotation and AutoCloseable registration, documentation and tests | Preserved together, without code changes, in PR #55. |
| 2 | Same file content as stash 1 | Preserved as originally saved; no separate implementation needed. |
| 3 | Earlier registration prototype | Same feature as stash 1, with older nullability annotation placement. |
| 4 | Error Prone setup and test concurrency refinements | Current main already contains the relevant build tooling, spin-wait and executor changes. |
| 5 | Initial publishing, nullability, licenses and changelog | Already represented in published history and superseded by current conventions. |
| 6 | README examples | The current README already includes the revised service examples; the old patch also contains an obsolete example name. |

`manifest.json` records each original stash id, its parent commit, date, description
and patch hash. Each `stash-N.patch` is the complete difference from that stash's
base to its working-tree snapshot. A `stash-N-staged.patch`, where present, records
the separate staged snapshot relative to the same base. These retain source
changes without retaining build caches or requiring independent backup branches.

The local maintenance branches for supply-chain adoption and Dependabot removal
were also checked: their patches are equivalent to changes already in current
main. No additional implementation pull request is needed for them.
