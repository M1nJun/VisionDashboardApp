package com.visiondash.server.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * contracts/topology.json - which physical PC serves which (line, vision_key).
 *
 * A PC lists the vision keys it hosts rather than being labelled with a type of
 * its own: one Example A PC runs a single process that reports as two separate
 * inspectors, and no component should have to know a translation table to work
 * that out.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Topology(List<String> lines, List<Pc> pcs) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pc(String ip, String line, List<String> hosts) {
    }
}
