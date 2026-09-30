package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, clock-driven request queue. A runtime adapter will pump due work on one scheduler. */
public final class RecruitRequestCoordinator {
    public static final int MESSAGE_BUDGET = 5;
    public static final long EXPIRY_MS = 15_000;
    public static final long FIRST_REPLY_MS = 900;
    public static final long REPLY_GAP_MS = 750;

    public record Candidate(int botId, CompanionTaskService.PriorActivity prior) {}
    public record Context(int ownerId, CompanionTaskService.PartyKey party, int channelId,
                          long mapInstanceId, String fingerprint) {}
    public interface Gateway {
        int memberCount(Context context);
        boolean validOwner(Context context);
        boolean eligible(Context context, int botId);
        boolean canonicalJoin(Context context, int botId);
        /** The sole bot message for this acceptance; do not add join/follow acknowledgments. */
        void accepted(Context context, CompanionTaskService.Task task);
    }
    private record Key(int ownerId, CompanionTaskService.PartyKey party, String fingerprint) {}
    private record Pending(CompanionTaskService.Reservation reservation, long dueAtMs) {}
    private static final class Request {
        final long id, expiresAtMs;
        final Context context;
        final List<Pending> pending = new ArrayList<>();
        int messages;
        Request(long id, long expiresAtMs, Context context) {
            this.id = id; this.expiresAtMs = expiresAtMs; this.context = context;
        }
    }

    private final Map<Key, Request> requests = new HashMap<>();
    private final CompanionTaskService tasks;
    private final LongSupplier clock;
    private final Gateway gateway;
    private long nextRequest;

    public RecruitRequestCoordinator(CompanionTaskService tasks, LongSupplier clock, Gateway gateway) {
        if (tasks == null || clock == null || gateway == null) throw new IllegalArgumentException("dependencies");
        this.tasks = tasks; this.clock = clock; this.gateway = gateway;
    }

    /** Candidates already ranked by role/suitability/distance/fairness by the runtime adapter. */
    public synchronized long request(Context context, List<Candidate> rankedCandidates) {
        purge();
        if (context == null || context.party() == null || context.fingerprint() == null
                || rankedCandidates == null || !gateway.validOwner(context)) return 0;
        Key key = key(context);
        Request existing = requests.get(key);
        if (existing != null) {
            if (existing.context.equals(context)) return existing.id;
            // A fresh map/channel context cannot inherit delayed actions from the old one.
            tasks.cancelRequest(existing.id);
            requests.remove(key, existing);
        }
        long now = clock.getAsLong();
        Request request = new Request(++nextRequest, now + EXPIRY_MS, context);
        for (Candidate candidate : rankedCandidates) {
            if (request.pending.size() == MESSAGE_BUDGET) break;
            if (!gateway.eligible(context, candidate.botId())) continue;
            var reserved = tasks.reserve(request.id, candidate.botId(), context.party(), context.ownerId(),
                    context.channelId(), gateway.memberCount(context), request.expiresAtMs, candidate.prior());
            if (reserved.isPresent()) request.pending.add(new Pending(reserved.get(),
                    now + FIRST_REPLY_MS + REPLY_GAP_MS * request.pending.size()));
        }
        if (request.pending.isEmpty()) return 0; // no empty requests or unbounded cooldown history
        requests.put(key, request);
        return request.id;
    }

    /** Due callbacks revalidate context immediately before join, then speak once only on success. */
    public synchronized void pump() {
        purge();
        long now = clock.getAsLong();
        for (Request request : List.copyOf(requests.values())) {
            for (Pending pending : List.copyOf(request.pending)) {
                if (now < pending.dueAtMs()) continue;
                request.pending.remove(pending);
                var task = tasks.commit(pending.reservation(), () -> gateway.validOwner(request.context)
                        && gateway.eligible(request.context, pending.reservation().botId())
                        && gateway.canonicalJoin(request.context, pending.reservation().botId()));
                if (task.isPresent() && request.messages < MESSAGE_BUDGET) {
                    request.messages++;
                    gateway.accepted(request.context, task.get());
                }
            }
            if (request.pending.isEmpty()) requests.remove(key(request.context), request);
        }
    }

    /** Cancels queued actions, not existing membership; task release is a separate canonical operation. */
    public synchronized void cancel(int ownerId, CompanionTaskService.PartyKey party) {
        for (Request request : List.copyOf(requests.values())) {
            if (request.context.ownerId() == ownerId && request.context.party().equals(party)) {
                tasks.cancelRequest(request.id);
                requests.remove(key(request.context), request);
            }
        }
    }

    public synchronized int queuedRequests() { purge(); return requests.size(); }
    public synchronized void cancelChannel(int worldId, int channelId) {
        for (Request request : List.copyOf(requests.values())) {
            if (request.context.party().worldId() == worldId && request.context.channelId() == channelId) {
                tasks.cancelRequest(request.id);
                requests.remove(key(request.context), request);
            }
        }
    }
    private void purge() {
        long now = clock.getAsLong();
        for (Request request : List.copyOf(requests.values())) {
            if (now >= request.expiresAtMs) {
                tasks.cancelRequest(request.id);
                requests.remove(key(request.context), request);
            }
        }
        tasks.expire();
    }
    private static Key key(Context context) {
        return new Key(context.ownerId(), context.party(), context.fingerprint());
    }
}
