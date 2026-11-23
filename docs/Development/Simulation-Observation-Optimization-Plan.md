# Observation optimization design priorities

Status: proposed follow-up to the implemented [input-context profile](Simulation-Input-Context.md), baseline `f115c6f2843`. This plan changes no runtime behavior, budgets or measured results. Information coverage has a separate [design backlog](Simulation-Observation-Coverage-Plan.md).

Optimization work reduces the cost of producing and storing the same represented information. It does not gain a performance improvement by silently omitting inputs, weakening privacy, moving capture after selection or reusing stale state. Coverage additions can proceed independently, subject to their existing cost guardrails; optimization comparisons must name the exact coverage/profile baseline they preserve.

The [reviewed combined execution order](Simulation-Observation-Roadmap.md) keeps OPT-001 next; runtime optimizations remain conditional on measured costs.

## Ordered backlog

| Priority | Item | Design and completion criteria |
| --- | --- | --- |
| OPT-001 | Attribute cost and improve measurement | Separate projection, canonical ordering, freezing, event publication and serialization costs. Measure calling-thread CPU, allocation, garbage collection, peak transient capture retention, retained frozen contexts, populated disclosure buffers and raw/compressed bytes. Include disabled/copy paths, warmup, repeated samples and nonoverlapping benchmark JVMs. Record fixture sizes, profile/budget status and source revision; unavailable contexts must not make a benchmark appear faster. |
| OPT-002 | Remove redundant immutable copies | Profile repeated freezing between context construction and enclosing events. Design a trusted detached immutable representation before permitting reuse. An unmodifiable wrapper around mutable contents is not proof of deep immutability. Accept only with lower measured cost and regressions proving nested mutation cannot rewrite published data, no live engine objects escape, and ordinary untrusted maps still freeze/validate safely. |
| OPT-003 | Reduce construction and ordering allocation | Profile intermediate maps/lists and string representations used by hidden-zone canonicalization. Consider precomputed keys or typed comparison over permitted fields. Preserve output semantics, deterministic ordering, cancellation and bounds. Paired hidden-world tests must still pass; diagnostic IDs or hidden order must never become tie-breakers that affect policy values. |
| OPT-004 | Reduce storage and serialization cost | Measure serialization and compression independently from capture. Evaluate export-time normalization or compressed output while preserving field meanings, joins, source event order, failure visibility and full/filtered parity. Review any output-format change and consumer compatibility explicitly. Keep the legacy observation contract intact; adding contexts does not authorize removing old fields. Compression savings alone do not establish lower capture CPU or allocation. |
| OPT-005 | Validate across workloads | Repeat comparisons on simple decks, Doctor Who and other complex decks, four-player Commander, large boards, populated disclosures and long games. Report distributions and budget exhaustion by player/turn alongside CPU, allocation, retention and bytes. Separate instrumentation overhead from policy/search variation. Same-seed Monte Carlo games are not deterministic performance or gameplay controls. |

These are candidate investigations, not established bottlenecks or promised speedups. OPT-001 determines whether OPT-002 through OPT-004 justify implementation and can change their performance priority. Every optimization gets its own commit with before/after evidence and a review of remaining costs. Profiling instrumentation and benchmark improvements may have separate commits before runtime changes.

COV-001's gaps are assigned in the [coverage ownership table](Simulation-Observation-Coverage-Plan.md#assigned-cov-001-gaps-and-scheduling). COV-001A invocation accounting is not a prerequisite for OPT-001: cost measurements can use known fixture operations and report their actual capture statuses. Such measurements do not establish loss rates across all engine choices. Nested timing work and example-completeness criteria remain with COV-005/006 and COV-002 through COV-004 respectively. No optimization item silently takes ownership of those coverage changes.

## Baseline and limits

The existing two fixtures measured approximately 218–360 microseconds and 163–595 KB of incremental allocation per choice, including event freezing and Gson serialization. Reachable frozen contexts were approximately 31–105 KB. Those figures do not measure peak transient capture retention, whole-game CPU or nonempty disclosure-buffer cost. See the [original samples and methodology](assessments/input-context/benchmark-summary.json).

Fresh inline contexts remain the default. Same parent, phase, stack entry or engine-event count does not establish equal inputs. Producer-side caching, deduplication and asynchronous output would need separate architecture/lifecycle designs; they are not assumed optimizations in this backlog.

## Commit and acceptance boundaries

Compare the same schema/profile, input fixture, capture status and represented policy data before and after each change. Changes in elapsed-time diagnostics are expected; any other output difference must be explained and validated. Preserve callback results/exceptions, callback-once behavior, hidden-information restrictions, cancellation, observation-health reporting and engine event ordering, including pregame shuffles. Run tests appropriate to the touched ownership/serialization path and verify legacy/new exports where affected.

The existing 256,000-unit game traversal budget is not a CPU or byte budget. Reducing copies does not automatically increase how many contexts fit that allowance. Raising caps, changing player shares, sampling or dropping fields requires a separate coverage-policy design and commit with explicit data-availability tradeoffs. OPT-005 may supply evidence for that later decision; it does not authorize changing the policy itself.
