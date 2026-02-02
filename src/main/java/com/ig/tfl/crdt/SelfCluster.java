package com.ig.tfl.crdt;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.ddata.SelfUniqueAddress;
import org.apache.pekko.cluster.typed.Cluster;

/**
 * Helper to get cluster self address for CRDT operations.
 */
public class SelfCluster {
    private final SelfUniqueAddress selfUniqueAddress;

    private SelfCluster(ActorSystem<?> system) {
        this.selfUniqueAddress = SelfUniqueAddress.apply(
                Cluster.get(system).selfMember().uniqueAddress());
    }

    public static SelfCluster get(ActorSystem<?> system) {
        return new SelfCluster(system);
    }

    public SelfUniqueAddress selfUniqueAddress() {
        return selfUniqueAddress;
    }
}
