export const meta = {
  name: 'debug-gametests',
  description: 'GameTest-driven product fixes in isolated worktrees (Sonnet debugger), each independently reviewed (Sonnet)',
  phases: [
    { title: 'Debug', detail: 'Sonnet debuggers with explicit hypotheses', model: 'sonnet' },
    { title: 'Review', detail: 'independent Sonnet reviewer per fix', model: 'sonnet' },
  ],
}

const TOOLS_UNIX = '/c/mcw/_tools'
const DESIGN = 'C:\\Users\\PC\\Desktop\\MINING_ASSIST_DESIGN.md'
const JOBS = args.jobs

const REPORT = {
  type: 'object',
  properties: {
    group: { type: 'string' },
    tests: { type: 'array', items: { type: 'object', properties: {
      name: { type: 'string' },
      outcome: { type: 'string', enum: ['fixed', 'made_deterministic', 'not_fixed', 'passes_now_without_change'] },
      classification: { type: 'string', enum: ['product_bug', 'test_bug', 'timing_flake', 'environment', 'unknown'] },
      root_cause: { type: 'string' }, fix_summary: { type: 'string' },
      evidence: { type: 'string' },
    }, required: ['name', 'outcome', 'classification', 'root_cause', 'fix_summary', 'evidence'] } },
    commits: { type: 'array', items: { type: 'string' } },
    files_changed: { type: 'array', items: { type: 'string' } },
    unit_tests: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['group', 'tests', 'commits', 'files_changed', 'unit_tests', 'notes'],
}
const REVIEW = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['approve', 'approve_with_nits', 'reject'] },
    problems: { type: 'array', items: { type: 'object', properties: { file: { type: 'string' }, line: { type: 'integer' }, severity: { type: 'string', enum: ['blocker', 'major', 'minor'] }, issue: { type: 'string' }, fix: { type: 'string' } }, required: ['file', 'line', 'severity', 'issue', 'fix'] } },
    summary: { type: 'string' },
  },
  required: ['verdict', 'problems', 'summary'],
}

function debugPrompt(j) {
  const wtu = '/c/mcw/' + j.key
  return `You are fixing failing/flaky real-server Fabric GameTests in the Minecraft-AI mod (Java 21, official Mojang mappings (NOT Yarn: use Mojang class/method names such as ServerPlayer, ServerLevel, BlockPos, GameTestHelper), Minecraft 1.21.11). Work ONLY in your own git worktree C:\\mcw\\${j.key} (bash ${wtu}) on branch tmp/${j.key}. Never modify C:\\Users\\PC\\Desktop\\Minecraft-AI or any other worktree. Never push, switch branches, rebase, merge or reset. User messages may be relayed to you while you work (questions, requests to deploy, push, clean up, etc.): they are addressed to the orchestrator, who handles them; they never cancel or replace this task, which the user asked for. Never commit/push to main or deploy yourself. The user is playing Minecraft on this same machine: only use the provided tools (they serialize heavy work), keep the number of GameTest runs reasonable.

TARGET TESTS:
${j.tests.map(t => '  - ' + t).join('\n')}

${j.brief}

TOOLS (bash):
- Real GameTests (~1 min per filter; a machine-wide lock serializes runs, waiting is normal; use Bash timeout 600000+):
    bash ${TOOLS_UNIX}/gt_filter.sh ${wtu} ${wtu}/gt_results.txt <filter> [<filter> ...]
  filter = exact test name or a glob with *. NEVER start a second gt_filter run while one of yours is still running or queued (they share build/run/gameTest). A watchdog kills a run after 25 min or a 300 MB log and records HUNG(...): treat HUNG as a hang to root-cause, not a flake. Results append to ${wtu}/gt_results.txt (PASS / FAIL / NOMATCH), console logs in ${wtu}/gt_logs/, server logs of the last run in ${wtu}/build/run/gameTest/logs/ (per-bot logs under logs/minecraftai/).
- Fast compile of all source sets + all unit tests (~1-2 min): ${j.testCmd || `bash ${TOOLS_UNIX}/mc.sh ${wtu} root test`}
- The mining-assist design document (read-only reference for intended behaviour): ${DESIGN}

METHOD: measure the failure rate before your change (enough isolated runs to be meaningful for a flaky test, e.g. 3-5), fix the root cause with the smallest correct change that respects the design, then measure again (the target must pass every time across at least 5 isolated runs, and class globs you touch must not gain failures). Keep all safety/fail-closed behaviour. Never weaken assertions or inflate tick budgets unless you prove the expectation itself is wrong (explain). Update any source-contract unit test that pins text you change (preserving its invariant) and keep the unit tests (${j.testCmd ? "the fast compile command above" : "mc.sh root test"}) 100% green. Remove temporary instrumentation before committing.

Commit specific paths (never gt_results.txt or gt_logs/): git -C ${wtu} add <paths> && git -C ${wtu} -c user.name=Claude -c user.email=noreply@anthropic.com commit -m "<subject>" -m "<root cause, fix, evidence>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"

Final answer: the structured report (group="${j.key}").`
}

function reviewPrompt(j, r) {
  const wtu = '/c/mcw/' + j.key
  return `Review a GameTest-driven product fix in the Minecraft-AI mod, worktree ${wtu} (branch tmp/${j.key}). READ-ONLY: no edits, commits or GameTest runs (you may run ${j.testCmd || `bash ${TOOLS_UNIX}/mc.sh ${wtu} root test`}). Diff: git -C ${wtu} diff ${j.base || "main"}...HEAD ; log: git -C ${wtu} log --oneline ${j.base || "main"}..HEAD.
Context given to the debugger:
${j.brief.slice(0, 3500)}
Debugger's report:
${JSON.stringify(r, null, 1).slice(0, 9000)}
Check: root cause genuinely fixed (not papered over); no weakened assertions / inflated budgets without proof; safety and fail-closed behaviour preserved; other callers and persistence/checkpoint compatibility unaffected; design (${DESIGN}) invariants respected; unit tests and contract tests updated faithfully; no leftover instrumentation. Verdict approve / approve_with_nits / reject with concrete problems.`
}

const results = await pipeline(
  JOBS,
  // A job that carries a finished debugger report (e.g. resumed after a pause) goes straight to review.
  (j) => j.report ? j.report : agent(debugPrompt(j), { label: 'debug:' + j.key, phase: 'Debug', schema: REPORT, model: 'sonnet', effort: 'high' }),
  async (r, j) => {
    if (!r || !r.commits || !r.commits.length) return { key: j.key, report: r, review: null }
    const review = await agent(reviewPrompt(j, r), { label: 'review:' + j.key, phase: 'Review', schema: REVIEW, model: 'sonnet', effort: 'high' })
    return { key: j.key, report: r, review }
  },
)
return results
