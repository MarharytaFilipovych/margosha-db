# Design

## Context

See proposal.md - Why. Two constraints shape every decision here:

- The sandbox only grants the fixed `LabRole`; it refuses `iam:CreatePolicy`, `iam:CreateRole`, and similar write calls on IAM, so the read-only policy and the assumable role can only exist as unattached IaC templates with a written explanation of what they would do / why the sandbox refuses them.
- The sandbox session is time-boxed (~3 hours) and all resources are deleted when it ends. The network stack is the only piece actually deployed, and it must be redeployable from IaC alone within a session - that requirement is what the recreatability task (tasks.md §6) exists to prove. The session runs under the `voclabs` role. The LabRole analysis and the Milestone 2 quota review are each performed once in this AWS Academy sandbox and once in a separate AWS Learner Lab environment, since the two can differ in quotas and exact policy wiring.

The repo is greenfield: no IaC tool, module layout, or CIDR scheme has been chosen yet. Those three choices are tracked as standalone ADRs (see Decisions below) rather than decided here.

## Goals / Non-Goals

**Goals:**
- Produce IaC templates for the read-only IAM policy and the assumable role, each paired with a written explanation (what it would do / why the sandbox refuses it) since neither can be deployed.
- Stand up a real, working VPC (public + private subnets, IGW on the public subnet only) from an IaC stack in the sandbox.
- Produce the M2 planning docs: service quota review and budget-vs-credit-ceiling comparison.
- Demonstrate the network stack is fully recreatable from IaC alone, with timing and failure notes from the first attempt.

**Non-Goals:**
- Deploying or attaching the IAM policy/role (sandbox blocks this by design; documenting the refusal is the deliverable, not working around it).
- Building any Milestone 2 application resources (shards, databases, compute) - M1 only plans for their quotas/budget.
- Multi-account, multi-region, or cross-session persistence of the network stack - it is expected to be destroyed and redeployed each session.

## Decisions

- **IaC tool, stack layout, CIDR addressing scheme** - these are the three mandatory ADRs called out in the proposal. They are authored separately in `adr/` rather than decided in this document; tasks.md §1 blocks on them before the network and IAM templates are written.
- **Proving what LabRole denies**: attempt the relevant actions (e.g. `iam:CreatePolicy`, `iam:CreateRole`) via CLI/console and record the resulting `AccessDenied` errors, supplemented by the IAM policy simulator for actions that are unsafe or inconvenient to attempt directly. Chosen over relying on the simulator alone, since simulator results can diverge from what the sandbox actually enforces at call time - the proposal's own ADR step ("console, CLI, policy simulator") implies cross-checking methods, not picking one.
- **Validating the undeployable IAM templates**: run them through the chosen IaC tool's static validation/lint step (exact command depends on the tool ADR) instead of a real deploy, since deployment is blocked. This is the only feasible correctness check available in-sandbox.
- **Recreatability test method**: destroy and reapply the network stack using the IaC tool's own destroy/apply (or equivalent) commands, timing each with wall-clock timestamps and logging any error encountered on the first attempt verbatim, rather than approximating with manual console steps.

## Risks / Trade-offs

- Session time limit means the network stack doesn't persist between sandbox sessions → mitigation: tasks.md §6 proves the full teardown/rebuild cycle works and is timed, so redeploying each session is a known, bounded cost rather than a surprise.
- The IAM policy and role are never actually deployed, so correctness can only be checked statically → mitigation: static validation/lint via the chosen IaC tool, plus a written walkthrough of what each resource would do if the sandbox allowed it.
- The three mandatory ADRs are not yet written, so network and IAM task work in tasks.md §3-4 is blocked until they land → mitigation: tasks.md already orders ADRs first (§1); no template work should start before they're resolved.
- M2 budget/quota estimates are necessarily approximate before M2's actual design exists → mitigation: scope the estimate explicitly to "2 shards" as given, and flag it as a planning input to revisit once M2 is designed, not a final number.
- The AWS Academy sandbox and the AWS Learner Lab environment may expose different quotas or LabRole specifics → mitigation: review and record both explicitly rather than assuming parity between them.
