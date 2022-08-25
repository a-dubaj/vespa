// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.node;

import com.google.common.collect.ImmutableSet;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancer;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancerInstance;
import com.yahoo.vespa.hosted.provision.lb.LoadBalancers;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A node ACL declares which nodes, networks and ports a node should trust.
 *
 * @author mpolden
 */
public record NodeAcl(Node node,
                      Set<Node> trustedNodes,
                      Set<String> trustedNetworks,
                      Set<Integer> trustedPorts) {

    public NodeAcl {
        Objects.requireNonNull(node, "node must be non-null");
        ImmutableSet.copyOf(Objects.requireNonNull(trustedNodes, "trustedNodes must be non-null"));
        ImmutableSet.copyOf(Objects.requireNonNull(trustedNetworks, "trustedNetworks must be non-null"));
        ImmutableSet.copyOf(Objects.requireNonNull(trustedPorts, "trustedPorts must be non-null"));
    }

    public static NodeAcl from(Node node, NodeList allNodes, LoadBalancers loadBalancers) {
        Set<Node> trustedNodes = new TreeSet<>(Comparator.comparing(Node::hostname));
        Set<Integer> trustedPorts = new LinkedHashSet<>();
        Set<String> trustedNetworks = new LinkedHashSet<>();

        // For all cases below, trust:
        // - SSH: If the host has one container, and it is using the host's network namespace,
        //   opening up SSH to the host is done here as a trusted port. For simplicity all nodes have
        //   SSH opened (which is safe for 2 reasons: SSH daemon is not run inside containers, and NPT networks
        //   will (should) not forward port 22 traffic to container).
        // - parent host (for health checks and metrics)
        // - nodes in same application
        // - load balancers allocated to application
        trustedPorts.add(22);
        allNodes.parentOf(node).ifPresent(trustedNodes::add);
        node.allocation().ifPresent(allocation -> {
            trustedNodes.addAll(allNodes.owner(allocation.owner()).asList());
            loadBalancers.list(allocation.owner()).asList()
                         .stream()
                         .map(LoadBalancer::instance)
                         .flatMap(Optional::stream)
                         .map(LoadBalancerInstance::networks)
                         .forEach(trustedNetworks::addAll);
        });

        switch (node.type()) {
            case tenant:
                // Tenant nodes in other states than ready, trust:
                // - config servers
                // - proxy nodes
                // - parents of the nodes in the same application: If some nodes are on a different IP version
                //   or only a subset of them are dual-stacked, the communication between the nodes may be NAT-ed
                //   via parent's IP address
                trustedNodes.addAll(allNodes.nodeType(NodeType.config).asList());
                trustedNodes.addAll(allNodes.nodeType(NodeType.proxy).asList());
                node.allocation().ifPresent(allocation ->
                                                    trustedNodes.addAll(allNodes.parentsOf(allNodes.owner(allocation.owner())).asList()));

                if (node.state() == Node.State.ready) {
                    // Tenant nodes in state ready, trust:
                    // - All tenant nodes in zone. When a ready node is allocated to an application there's a brief
                    //   window where current ACLs have not yet been applied on the node. To avoid service disruption
                    //   during this window, ready tenant nodes trust all other tenant nodes
                    trustedNodes.addAll(allNodes.nodeType(NodeType.tenant).asList());
                }
                break;

            case config:
                // Config servers trust:
                // - all nodes
                // - port 4443 from the world
                trustedNodes.addAll(allNodes.asList());
                trustedPorts.add(4443);
                break;

            case proxy:
                // Proxy nodes trust:
                // - config servers
                // - all connections from the world on 443 (production traffic) and 4443 (health checks)
                trustedNodes.addAll(allNodes.nodeType(NodeType.config).asList());
                trustedPorts.add(443);
                trustedPorts.add(4443);
                break;

            case controller:
                // Controllers:
                // - port 4443 (HTTPS + Athenz) from the world
                // - port 443 (HTTPS + Okta) from the world
                trustedPorts.add(4443);
                trustedPorts.add(443);
                break;

            default:
                throw new IllegalArgumentException("Don't know how to create ACL for " + node +
                                                   " of type " + node.type());
        }
        return new NodeAcl(node, trustedNodes, trustedNetworks, trustedPorts);
    }

}
