package com.ig.tfl.client;

import com.ig.tfl.model.TubeStatus;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Actor gateway for all TfL API communication.
 *
 * Centralizes:
 * - TfL API calls (via TflApiClient)
 * - Circuit breaker state exposure
 * - Retry logic (handled by TflApiClient)
 *
 * Both TubeStatusReplicator (for cache refresh) and TubeStatusRoutes
 * (for date-range queries) communicate through this gateway.
 */
public class TflGateway extends AbstractBehavior<TflGateway.Command> {
    private static final Logger log = LoggerFactory.getLogger(TflGateway.class);

    // Commands
    public sealed interface Command {}

    /**
     * Fetch all tube line statuses.
     * Used by TubeStatusReplicator for cache refresh.
     */
    public record FetchAllLines(ActorRef<FetchResponse> replyTo) implements Command {}

    /**
     * Fetch status for a specific line with date range.
     * Used by Routes for future/planned disruptions - bypasses cache.
     */
    public record FetchLineWithDateRange(
            String lineId,
            LocalDate from,
            LocalDate to,
            ActorRef<FetchResponse> replyTo
    ) implements Command {}

    /**
     * Get circuit breaker state for health checks.
     */
    public record GetCircuitState(ActorRef<CircuitStateResponse> replyTo) implements Command {}

    // Internal message for async completion
    private record FetchComplete(
            TubeStatus status,
            Throwable error,
            ActorRef<FetchResponse> replyTo
    ) implements Command {}

    // Responses
    public record FetchResponse(TubeStatus status, Throwable error) {
        public boolean isSuccess() {
            return error == null && status != null;
        }
    }

    /**
     * Circuit breaker state for health checks.
     */
    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public record CircuitStateResponse(CircuitState state) {}

    // Dependencies
    private final TflApiClient client;

    public static Behavior<Command> create(TflApiClient client) {
        return Behaviors.setup(context -> new TflGateway(context, client));
    }

    private TflGateway(ActorContext<Command> context, TflApiClient client) {
        super(context);
        this.client = client;
        log.info("TflGateway started");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(FetchAllLines.class, this::onFetchAllLines)
                .onMessage(FetchLineWithDateRange.class, this::onFetchLineWithDateRange)
                .onMessage(GetCircuitState.class, this::onGetCircuitState)
                .onMessage(FetchComplete.class, this::onFetchComplete)
                .build();
    }

    private Behavior<Command> onFetchAllLines(FetchAllLines msg) {
        log.debug("Fetching all lines from TfL");

        client.fetchAllLinesAsync()
                .whenComplete((status, error) ->
                        getContext().getSelf().tell(new FetchComplete(status, error, msg.replyTo())));

        return this;
    }

    private Behavior<Command> onFetchLineWithDateRange(FetchLineWithDateRange msg) {
        log.debug("Fetching line {} status from {} to {}", msg.lineId(), msg.from(), msg.to());

        client.fetchLineStatusAsync(msg.lineId(), msg.from(), msg.to())
                .whenComplete((status, error) ->
                        getContext().getSelf().tell(new FetchComplete(status, error, msg.replyTo())));

        return this;
    }

    private Behavior<Command> onGetCircuitState(GetCircuitState msg) {
        CircuitState state;
        if (client.isCircuitOpen()) {
            state = CircuitState.OPEN;
        } else if (client.isCircuitHalfOpen()) {
            state = CircuitState.HALF_OPEN;
        } else {
            state = CircuitState.CLOSED;
        }
        msg.replyTo().tell(new CircuitStateResponse(state));
        return this;
    }

    private Behavior<Command> onFetchComplete(FetchComplete msg) {
        if (msg.error() != null) {
            log.warn("TfL fetch failed: {}", msg.error().getMessage());
        } else {
            log.debug("TfL fetch completed successfully");
        }
        msg.replyTo().tell(new FetchResponse(msg.status(), msg.error()));
        return this;
    }
}
