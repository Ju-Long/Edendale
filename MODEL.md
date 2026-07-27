# MODEL.md — Task Workflow Protocol

Append this file to a task prompt—for example, end the prompt with
`follow @MODEL.md`—to enable the propose-first and review-after workflow below.
Every rule binds the current task only.

## Phase 1 — Before writing code

1. **Restate the goal** in one sentence.
2. **Evaluate the requested approach against the active branch**, not in the
   abstract:
   - Confirm that the current branch owns the requested platform.
   - Inspect the actual source, tests, manifests, and CI configuration.
   - Reuse existing native behavior where it already solves part of the task.
   - Prefer the active platform's idiomatic frameworks and patterns.
   - Check the hard constraints in [AGENTS.md](AGENTS.md), especially branch
     isolation, privacy, secrets, data separation, and import performance.
   - Identify other platform branches affected by a shared product-rule change;
     do not import their code or silently expand the task across branches.
   - Check performance, accessibility, reduced motion, and supported input
     methods where relevant.
3. **If a meaningfully better method exists**, present it and stop for
   approval:
   - Give at most two alternatives plus the requested approach.
   - For each, state what changes, why it is better here, its tradeoffs, and
     rough effort (S/M/L).
   - End with one recommendation and ask whether to proceed as requested or
     switch.
   - Do not implement until the user answers.
4. **If the requested approach is already right**, say so in one line and
   proceed immediately.
5. **Trivial tasks** such as typo fixes, obvious renames, and comment-only
   changes skip Phase 1.

## Phase 2 — Implementation

- Follow the approved or stated approach. If it cannot work as planned, stop
  and report what was discovered rather than changing direction silently.
- Keep implementation, dependencies, tests, and CI inside the active platform
  branch.
- For a shared product-rule change, update the active platform natively and
  explicitly report which other platform branches require equivalent work.
- Never introduce a shared executable runtime, generated bridge, or dependency
  on another Edendale branch.
- Verify with the build, test, and CI commands documented by the active branch.
  Report actual results, including environmental limitations and failures.

## Phase 3 — On completion

1. **Update documentation.** Correct any README, design, or contributor
   guidance made stale by the change. Do not create or update `TASKS.md`.
2. **Report changes by feature.** List each changed file with a one-line
   summary, then list the verification performed.
3. **Suggest improvements** as a ranked list:
   - Separate items related to this change from pre-existing issues.
   - Include why each matters and rough effort (S/M/L).
   - If nothing is worth suggesting, say so without adding filler.
4. **Stop.** Do not begin suggested work until the user explicitly approves
   the named item.
