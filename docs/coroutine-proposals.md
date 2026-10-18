# Coroutine Proposal Decisions

KoKoDB retains its synchronous core and direct SQL API. The integrated admission/cancellation, Flow-first result API and coroutine group-commit proposals in issues #19–#21 are **not adopted**. The diagnostics demonstrate useful coroutine composition, but do not justify these new engine contracts for the current small-library scope.

## Admission improves worker availability through an external adapter

The shared facade holds a reentrant JVM monitor during a call or transaction. Under an intentionally stalled transaction, direct calls with 10/100/1,000 callers occupied both application workers and prevented a queued heartbeat from running in the 100 ms observation window. An external Mutex/single-worker adapter left both application workers available; every accepted operation completed. H2 showed the same admission result.

This is useful application integration, rather than an observed benefit requiring coroutine-aware engine state. It does not measure real application latency, throughput or bounded producer memory. The diagnostic adapter is not a production lifecycle/overload API.

**Decision for #19:** reject the proposed integrated execution/lifecycle expansion. Keep synchronous ownership and allow application-level offloading of whole synchronous operations. An integrated API would also need admission limits, shutdown barriers, parameter ownership and commit receipts, without a measured advantage over the external path yet.

Cancellation must remain separate from database outcome. The diagnostic cancelled a caller after a forced INSERT but before returning its result; the row survived reopening. Cancellation before Mutex admission executed no operation. [Mutex is non-reentrant](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/), and [withContext may discard a result during return cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html). Neither is a drop-in replacement for the current reentrant facade or a proof of rollback.

## Flow needs an incremental result contract first

The negative-control experiment constructed 10,000 models, converted the resulting List to Flow, and consumed one model. All 10,000 models had already been created. A cold wrapper around the same List query only postpones that work until collection.

True snapshot streaming could avoid unused mapping, but was not implemented or measured here. It would need snapshot-retention bounds, collector cleanup, parameter capture, custom-adapter concurrency rules and protection against escaping uncommitted transaction state. The existing adapter SPI provides no concurrent invocation guarantee. [Flow's collection and cancellation rules](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/flow.html) do not supply those database guarantees.

**Decision for #20:** reject a Flow-first public API. Choose an engine-level incremental result contract from a demonstrated early-termination workload before choosing its coroutine adapter. The eager-wrapper result does not prove true streaming is ineffective; no true-streaming versus cursor/H2 allocation or retained-heap comparison is claimed.

## Force cost is real, but independent group commit changes ownership and outcomes

The instrumented current path made exactly 1,000 appends and 1,000 forces for 1,000 independently acknowledged INSERTs. Median total time was 3,963.7 ms; force calls accounted for 93.6% of summed elapsed time across three rounds. One explicit 1,000-row transaction used one append/force and a median 10.2 ms. Every recovered row matched.

Those runs have different atomicity and acknowledgment boundaries. They demonstrate a synchronization cost, not group-commit performance. Shared force for independent transactions would need a staged prefix, hidden pending state, per-request uncertain outcomes and ordered close/checkpoint barriers. A sequential caller awaiting each commit offers no other pending transaction to group, and a batching window can add latency. [FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) remains part of the current acknowledgment contract.

**Decision for #21:** reject the proposed coroutine writer/grouping architecture for this scope. Preserve per-transaction force and caller-controlled explicit transactions. A future independent-grouping proposal needs an actual concurrent durable-write workload and a receipt/ownership contract; coroutines are not required to implement shared force. No force was skipped and no independent callers were silently merged in these diagnostics.

## Evidence and unresolved experiments

[Raw results, artifacts and reproduction commands](baselines/2026-10-18-coroutine-decisions/README.md) retain 19 coroutine contract observations and 12 checked force workloads. The study-only coroutine dependency is confined to the development comparison module; the core API and dependency graph are unchanged.

These are explicit rejections of the current proposals, not shipped features or completed versions of every proposed experiment. Integrated cancellation during scans, handoff/shutdown races, true snapshot streaming retention, concurrent independent group commit, and grouped crash/fault injection were not implemented or qualified. No production or general H2 superiority claim follows from closing these issues.
