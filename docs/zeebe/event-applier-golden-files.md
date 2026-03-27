# Event Applier Golden Files

## Background

Event appliers in Zeebe run in two contexts: during **processing** (when events are initially
appended to the log) and during **replay** (to rebuild state from the event log). Replay must
produce the exact same state mutations as the original processing — if it doesn't, leaders and
followers end up with divergent state, which is a serious production bug.

Golden files enforce this invariant by snapshotting the source code of each event applier. The
`NoChangesTest` in `EventAppliersTest` compares each applier's current source against its golden
copy and fails when they differ. This catches accidental or unreviewed changes before they reach
production.

For the full engine mental model, see [zeebe/engine/README.md](../../zeebe/engine/README.md).

## Common Cases

### I added a new event applier

Create the golden file. The test failure message includes a ready-to-run `cp` command. Copy-paste
it, or run `GoldenFileUpdater.main()` (defined inside `NoChangesTest`) to update all golden files
at once.

### I intentionally changed an existing event applier

**Don't update the golden file.** Register a new applier version instead. The previous version must
remain unchanged so that older events replay correctly. Ensure the new version is registered in all
newer minor versions as well.

## High-Stakes Porting Cases

These scenarios are severe because they can cause leader/follower state divergence — a
production-breaking bug.

### Backported applier (newer → older version)

If you ported an applier from a newer version to an older one and the golden file differs, this is
a **breaking change**. The applier in the newer version was already released with different behavior.

**Action:** Roll back the change in the newer version. Instead, register a new applier version and
ensure it is available in all newer versions *before* adding it to the older version.

### Forward-ported applier (older → newer version)

If you ported an applier from an older version to a newer one and the golden file differs, you may
have found a **critical bug** — the newer version may already be running different behavior in
production.

**Action:** Carefully review the differences. If the current code is already released, there is no
standard resolution — escalate and assess whether the change can be tolerated. If the current code
is not yet released, update the golden file to align with the older version.

## Allowed Changes

In rare cases, updating the golden file is acceptable:

- **Cosmetic changes**: comments, formatting, import reordering — anything that doesn't affect
  runtime behavior.
- **Safe optional-field additions**: storing a new optional field that is always used the same way
  and where it's acceptable that the field may or may not be present in state. Even so, consider
  carefully whether this is truly safe.

When in doubt, register a new applier version.
