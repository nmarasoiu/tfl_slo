package com.ig.tfl.crdt;

import com.ig.tfl.client.TflGateway;
import com.ig.tfl.model.TubeStatus;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.apache.pekko.cluster.ddata.Key;
import org.apache.pekko.cluster.ddata.LWWRegister;
import org.apache.pekko.cluster.ddata.LWWRegisterKey;
import org.apache.pekko.cluster.ddata.typed.javadsl.DistributedData;
import org.apache.pekko.cluster.ddata.typed.javadsl.Replicator;
import org.apache.pekko.cluster.ddata.typed.javadsl.ReplicatorMessageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Actor that manages CRDT-replicated tube status with freshness logic.
 *
 * Uses Pekko Distributed Data with LWWRegister for tube status.
 * Handles freshness requirements from HTTP layer - Routes no longer
 * make caching decisions.
 */
public class TubeStatusReplicator extends AbstractBehavior<TubeStatusReplicator.Command> {
    private static final Logger log = LoggerFactory.getLogger(TubeStatusReplicator.class);

    // CRDT key for tube status
    private static final Key<LWWRegister<TubeStatus>> STATUS_KEY =
            LWWRegisterKey.create("tube-status");

    // Configuration
    private final Duration refreshInterval;
    private final Duration recentEnoughThreshold;
    private final Duration backgroundRefreshThreshold;
    private final String nodeId;

    // Dependencies
    private final ActorRef<TflGateway.Command> tflGateway;
    private final ReplicatorMessageAdapter<Command, LWWRegister<TubeStatus>> replicatorAdapter;
    private final SelfCluster selfCluster;

    // Current cached status
    private TubeStatus currentStatus;

    // Pending freshness requests waiting for TfL response
    private final Queue<PendingFreshnessRequest> pendingFreshnessRequests = new LinkedList<>();

    private record PendingFreshnessRequest(
            Long maxAgeMs,
            ActorRef<StatusResponse> replyTo
    ) {}

    // Message types
    public sealed interface Command {}

    public record RefreshTick() implements Command {}

    /**
     * Get current cached status (no freshness requirement).
     */
    public record GetStatus(ActorRef<StatusResponse> replyTo) implements Command {}

    /**
     * Get status with freshness requirement.
     * If cache is stale (older than maxAgeMs), will attempt TfL fetch.
     * If TfL fails, returns stale data with isStale=true.
     */
    public record GetStatusWithFreshness(
            Long maxAgeMs,  // null = no requirement (same as GetStatus)
            ActorRef<StatusResponse> replyTo
    ) implements Command {}

    /**
     * Fire-and-forget background refresh trigger.
     */
    public record TriggerBackgroundRefresh() implements Command {}

    /**
     * Response with freshness metadata.
     */
    public record StatusResponse(
            TubeStatus status,
            boolean isStale,        // true if data older than requested maxAgeMs
            Long requestedMaxAgeMs  // echo back for headers (null if no requirement)
    ) {}

    // Internal messages
    private record InternalGetResponse(
            Replicator.GetResponse<LWWRegister<TubeStatus>> response
    ) implements Command {}

    private record InternalUpdateResponse(
            Replicator.UpdateResponse<LWWRegister<TubeStatus>> response
    ) implements Command {}

    private record TflFetchComplete(TubeStatus status, Throwable error) implements Command {}

    public static Behavior<Command> create(
            ActorRef<TflGateway.Command> tflGateway,
            String nodeId,
            Duration refreshInterval,
            Duration recentEnoughThreshold) {
        return create(tflGateway, nodeId, refreshInterval, recentEnoughThreshold,
                Duration.ofSeconds(5)); // Default background refresh threshold
    }

    public static Behavior<Command> create(
            ActorRef<TflGateway.Command> tflGateway,
            String nodeId,
            Duration refreshInterval,
            Duration recentEnoughThreshold,
            Duration backgroundRefreshThreshold) {
        return Behaviors.setup(context ->
                Behaviors.withTimers(timers ->
                        new TubeStatusReplicator(context, timers, tflGateway, nodeId,
                                refreshInterval, recentEnoughThreshold, backgroundRefreshThreshold)));
    }

    private TubeStatusReplicator(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            ActorRef<TflGateway.Command> tflGateway,
            String nodeId,
            Duration refreshInterval,
            Duration recentEnoughThreshold,
            Duration backgroundRefreshThreshold) {
        super(context);

        this.tflGateway = tflGateway;
        this.nodeId = nodeId;
        this.refreshInterval = refreshInterval;
        this.recentEnoughThreshold = recentEnoughThreshold;
        this.backgroundRefreshThreshold = backgroundRefreshThreshold;
        this.selfCluster = SelfCluster.get(context.getSystem());

        // Set up replicator adapter for distributed data
        this.replicatorAdapter = new ReplicatorMessageAdapter<>(
                context,
                DistributedData.get(context.getSystem()).replicator(),
                Duration.ofSeconds(5));

        // Schedule periodic refresh with jitter
        Duration jitter = Duration.ofMillis((long) (Math.random() * 5000));
        timers.startTimerWithFixedDelay(
                "refresh",
                new RefreshTick(),
                jitter,  // Initial delay with jitter
                refreshInterval);

        log.info("TubeStatusReplicator started on node {}", nodeId);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(RefreshTick.class, this::onRefreshTick)
                .onMessage(GetStatus.class, this::onGetStatus)
                .onMessage(GetStatusWithFreshness.class, this::onGetStatusWithFreshness)
                .onMessage(TriggerBackgroundRefresh.class, this::onTriggerBackgroundRefresh)
                .onMessage(InternalGetResponse.class, this::onInternalGetResponse)
                .onMessage(InternalUpdateResponse.class, this::onInternalUpdateResponse)
                .onMessage(TflFetchComplete.class, this::onTflFetchComplete)
                .build();
    }

    private Behavior<Command> onGetStatus(GetStatus msg) {
        // Simple case: no freshness requirement
        msg.replyTo().tell(new StatusResponse(currentStatus, false, null));
        return this;
    }

    private Behavior<Command> onGetStatusWithFreshness(GetStatusWithFreshness msg) {
        // No freshness requirement = same as GetStatus
        if (msg.maxAgeMs() == null) {
            msg.replyTo().tell(new StatusResponse(currentStatus, false, null));
            return this;
        }

        // No data at all
        if (currentStatus == null) {
            msg.replyTo().tell(new StatusResponse(null, false, msg.maxAgeMs()));
            return this;
        }

        // Cache is fresh enough
        if (currentStatus.ageMs() <= msg.maxAgeMs()) {
            // Check soft threshold for proactive refresh
            if (currentStatus.ageMs() > backgroundRefreshThreshold.toMillis()) {
                log.debug("Data is {}ms old (> {}ms soft threshold) - triggering background refresh",
                        currentStatus.ageMs(), backgroundRefreshThreshold.toMillis());
                fetchFromTfl();
            }
            msg.replyTo().tell(new StatusResponse(currentStatus, false, msg.maxAgeMs()));
            return this;
        }

        // Cache too stale - attempt TfL fetch
        log.info("Cache is {}ms old, client wants max {}ms - attempting TfL fetch",
                currentStatus.ageMs(), msg.maxAgeMs());

        // Queue this request to be answered when TfL responds
        pendingFreshnessRequests.add(new PendingFreshnessRequest(msg.maxAgeMs(), msg.replyTo()));

        // Only fetch if this is the first pending request (avoid duplicate fetches)
        if (pendingFreshnessRequests.size() == 1) {
            fetchFromTfl();
        }

        return this;
    }

    private Behavior<Command> onTriggerBackgroundRefresh(TriggerBackgroundRefresh msg) {
        log.debug("Background refresh triggered");
        fetchFromTfl();
        return this;
    }

    private Behavior<Command> onRefreshTick(RefreshTick msg) {
        log.debug("Refresh tick - checking if we need fresh data");

        // First, read from CRDT to see if peers have fresh data
        replicatorAdapter.askGet(
                askReplyTo -> new Replicator.Get<>(
                        STATUS_KEY,
                        Replicator.readLocal(),
                        askReplyTo),
                InternalGetResponse::new);

        return this;
    }

    private Behavior<Command> onInternalGetResponse(InternalGetResponse msg) {
        var response = msg.response();

        if (response instanceof Replicator.GetSuccess<LWWRegister<TubeStatus>> success) {
            TubeStatus peerStatus = success.get(STATUS_KEY).getValue();

            if (isFreshEnough(peerStatus)) {
                log.debug("Peer data is fresh enough ({}ms old), using it",
                        peerStatus.ageMs());
                currentStatus = peerStatus;
                return this;
            }

            log.debug("Peer data is stale ({}ms old), fetching from TfL",
                    peerStatus != null ? peerStatus.ageMs() : "null");
        } else if (response instanceof Replicator.NotFound<?>) {
            log.debug("No peer data found, fetching from TfL");
        } else if (response instanceof Replicator.GetFailure<?>) {
            log.warn("Failed to read from peers, fetching from TfL");
        }

        // Fetch from TfL
        fetchFromTfl();
        return this;
    }

    private void fetchFromTfl() {
        // Create adapter to receive response
        ActorRef<TflGateway.FetchResponse> responseAdapter = getContext().messageAdapter(
                TflGateway.FetchResponse.class,
                resp -> new TflFetchComplete(resp.status(), resp.error()));

        tflGateway.tell(new TflGateway.FetchAllLines(responseAdapter));
    }

    private Behavior<Command> onTflFetchComplete(TflFetchComplete msg) {
        if (msg.error() != null) {
            log.error("Failed to fetch from TfL: {}", msg.error().getMessage());

            // Answer pending requests with stale data
            answerPendingRequestsWithStaleData();
            return this;
        }

        TubeStatus freshStatus = msg.status();
        log.info("Got fresh data from TfL, {} lines, updating CRDT", freshStatus.lines().size());

        // Update local cache IMMEDIATELY
        currentStatus = freshStatus;

        // Answer pending freshness requests with fresh data
        answerPendingRequestsWithFreshData();

        // Write to CRDT with WriteMajority for aggressive replication
        replicatorAdapter.askUpdate(
                askReplyTo -> new Replicator.Update<>(
                        STATUS_KEY,
                        LWWRegister.create(selfCluster.selfUniqueAddress(), freshStatus),
                        new Replicator.WriteMajority(Duration.ofSeconds(2)),
                        askReplyTo,
                        register -> register.withValue(
                                selfCluster.selfUniqueAddress(),
                                freshStatus)),
                InternalUpdateResponse::new);

        return this;
    }

    private void answerPendingRequestsWithFreshData() {
        while (!pendingFreshnessRequests.isEmpty()) {
            PendingFreshnessRequest req = pendingFreshnessRequests.poll();
            req.replyTo().tell(new StatusResponse(currentStatus, false, req.maxAgeMs()));
        }
    }

    private void answerPendingRequestsWithStaleData() {
        while (!pendingFreshnessRequests.isEmpty()) {
            PendingFreshnessRequest req = pendingFreshnessRequests.poll();
            // Return stale data with isStale=true so Routes can add warning headers
            req.replyTo().tell(new StatusResponse(currentStatus, true, req.maxAgeMs()));
        }
    }

    private Behavior<Command> onInternalUpdateResponse(InternalUpdateResponse msg) {
        if (msg.response() instanceof Replicator.UpdateSuccess<?>) {
            log.debug("CRDT write replicated to majority");
        } else if (msg.response() instanceof Replicator.UpdateTimeout<?>) {
            log.debug("CRDT write to majority timed out - gossip will propagate eventually");
        } else {
            log.debug("CRDT write result: {} - local cache serving, gossip will propagate",
                    msg.response().getClass().getSimpleName());
        }
        return this;
    }

    private boolean isFreshEnough(TubeStatus status) {
        if (status == null) {
            return false;
        }
        return status.queriedAt().isAfter(
                Instant.now().minus(recentEnoughThreshold));
    }
}
