# Proposed paid-account deployment authorization

Status: proposed for team Review only. The enforced gate and all current callers remain unchanged. This proposal is not approval to deploy and adds no active bypass.

The current gate compares the modeled window with verified credit balance, and its callers require a credit value. That expresses a credit-funded experiment policy, but rejects a normally paid account even when the owner explicitly permits paid deployment. Credit availability is not the only valid AWS payment source.

Propose an explicit protected `paid-account` payment mode alongside the existing credit-funded mode. The paid path must require an owner-approved decision record bound to the target account, source revision, approved UTC start/end, teardown or retention decision, and protected Environment review. It must not infer approval from an empty credit field, an ordinary workflow input, or a self-reviewed deployment. No credit balance should be invented or substituted for an approved payment decision.

The current quota subcommand returns UNVERIFIED even when invoked by a deployment caller. Its replacement must read actual service quotas and planned demand, fail on denied or missing evidence, and must not treat offline validation as a live quota pass. Review that implementation with the caller integration.

Keep identity checks, current state/plan review, live quotas, image/runtime evidence, branch trust, private access, and infrastructure-operation approvals. The paid path authorizes a normal payment source; it does not make an unknown account, expired window, unsafe plan, or missing runtime configuration eligible.

Implementation must update the gate, reusable caller, platform deployment caller, and runtime-secret caller together after policy approval. Required tests: legacy credit mode retains fail-closed behavior; paid mode fails without the protected approval record or a valid UTC window; untrusted branches and self-review cannot enable it; approved normal paid mode does not require fabricated credits. Public logs must omit payment/account details. No additional cost analysis report is requested.

Until the team approves and merges that change, preserve the existing gate's real blocking result. Candidate durations alone do not open a deployment window.
