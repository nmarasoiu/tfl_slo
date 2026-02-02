package com.ig.tfl.crdt;

import com.ig.tfl.client.TflClient;
import com.ig.tfl.model.TubeStatus;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.cluster.ddata.*;
import org.apache.pekko.cluster.ddata.typed.javadsl.DistributedData;
import org.apache.pekko.cluster.ddata.typed.javadsl.Replicator;
import org.apache.pekko.cluster.ddata.typed.javadsl.ReplicatorMessageAdapter;
import static org.apache.pekko.cluster.ddata.typed.javadsl.Replicator.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Actor that manages CRDT-replicated tube status.
 *
 * Uses Pekko Distributed Data with LWWRegister for tube status.
 * Implements scatter-gather: queries peers and TfL, takes freshest.
 */
public class TubeStatusReplicator extends AbstractBehavior<TubeStatusReplicator.Command> {
    private static final Logger log = LoggerFactory.getLogger(TubeStatusReplicator.class);

    // CRDT key for tube status
    private static final Key<LWWRegister<TubeStatus>> STATUS_KEY =
            LWWRegisterKey.create("tube-status");

    // Configuration
    private final Duration refreshInterval;
    private final Duration recentEnoughThreshold;
    private final Duration scatterGatherTimeout;
    private final String nodeId;

    // Dependencies
    private final TflClient tflClient;
    private final ReplicatorMessageAdapter<Command, LWWRegister<TubeStatus>> replicatorAdapter;
    private final SelfCluster selfCluster;

    // Current cached status
    private TubeStatus currentStatus;

    // Message types
    public sealed interface Command {}

    public record RefreshTick() implements Command {}
    public record GetStatus(ActorRef<StatusResponse> replyTo) implements Command {}
    public record StatusResponse(TubeStatus status) {}

    // Internal messages
    private record InternalGetResponse(
            Replicator.GetResponse<LWWRegister<TubeStatus>> response
    ) implements Command {}

    private record InternalUpdateResponse(
            Replicator.UpdateResponse<LWWRegister<TubeStatus>> response
    ) implements Command {}

    private record TflFetchComplete(TubeStatus status, Throwable error) implements Command {}

    public static Behavior<Command> create(
            TflClient tflClient,
            String nodeId,
            Duration refreshInterval,
            Duration recentEnoughThreshold) {
        return Behaviors.setup(context ->
                Behaviors.withTimers(timers ->
                        new TubeStatusReplicator(context, timers, tflClient, nodeId,
                                refreshInterval, recentEnoughThreshold)));
    }

    private TubeStatusReplicator(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            TflClient tflClient,
            String nodeId,
            Duration refreshInterval,
            Duration recentEnoughThreshold) {
        super(context);

        this.tflClient = tflClient;
        this.nodeId = nodeId;
        this.refreshInterval = refreshInterval;
        this.recentEnoughThreshold = recentEnoughThreshold;
        this.scatterGatherTimeout = Duration.ofSeconds(2);
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
                .onMessage(InternalGetResponse.class, this::onInternalGetResponse)
                .onMessage(InternalUpdateResponse.class, this::onInternalUpdateResponse)
                .onMessage(TflFetchComplete.class, this::onTflFetchComplete)
                .build();
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
        // Pekko HTTP is non-blocking, just pipe result back to actor
        tflClient.fetchAllLinesAsync()
                .whenComplete((status, error) ->
                        getContext().getSelf().tell(new TflFetchComplete(status, error)));
    }

    private Behavior<Command> onTflFetchComplete(TflFetchComplete msg) {
        if (msg.error() != null) {
            log.error("Failed to fetch from TfL: {}", msg.error().getMessage());
            // Keep serving stale data if we have any
            return this;
        }

        TubeStatus freshStatus = msg.status();
        log.info("Got fresh data from TfL, {} lines, updating CRDT", freshStatus.lines().size());

        // Update local cache IMMEDIATELY - HTTP responses don't wait for CRDT
        currentStatus = freshStatus;

        // Write to CRDT with WriteMajority for aggressive replication
        // This is async - we don't block, but majority of nodes will have data
        // within ~200ms (gossip interval). Falls back to eventual consistency
        // if majority unreachable (partition).
        //
        // Multi-DC note: WriteMajority counts ALL nodes across DCs.
        // For DC-locality, use WriteMajorityPlus(timeout, minCap) which ensures
        // majority from local DC + additional from remote DCs.
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

    private Behavior<Command> onInternalUpdateResponse(InternalUpdateResponse msg) {
        // AP system: WriteMajority is best-effort optimization, not a requirement.
        // Data is already in local cache and will spread via gossip eventually.
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

    private Behavior<Command> onGetStatus(GetStatus msg) {
        if (currentStatus != null) {
            msg.replyTo().tell(new StatusResponse(currentStatus));
        } else {
            // No data yet, return empty with degraded confidence
            msg.replyTo().tell(new StatusResponse(null));
        }
        return this;
    }

    private boolean isFreshEnough(TubeStatus status) {
        if (status == null) return false;
        return status.queriedAt().isAfter(
                Instant.now().minus(recentEnoughThreshold));
    }
}
