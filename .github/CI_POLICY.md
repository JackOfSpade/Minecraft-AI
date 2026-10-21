# CI and merging

Direct pushes to `main` are allowed. No branch protection or ruleset requires
checks or a pull request before a push. CI runs on pushes to every branch,
including `main`, and reports these stable aggregate checks. Their names are
status labels, not branch restrictions. A failed or skipped prerequisite fails
its aggregate check.

- `CI required`
- `Security required`
- `Dependency review required`

A temporary branch and pull request can be used for review. The optional
**Merge validated PR** workflow is manual and accepts one selected PR number. It
checks the current main ancestry, exact PR head, GitHub Actions check origin,
and latest successful aggregate results before merging. It checks out trusted
main policy, never PR code. It dispatches main validation after the merge and never deletes
branches itself. GitHub's existing repository auto-delete setting can separately
delete the selected PR's merged head branch. Existing branches and PRs are not
swept or automatically adopted.

These validation checks guard only that optional workflow; they do not prevent
direct pushes to `main`. Standard public runners are used, and existing
cloud/API workflows retain their own credentials and cost controls.
