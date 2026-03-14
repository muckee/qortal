package org.qortal.notification;

import java.util.List;

/**
 * Rich event fired when a QDN resource has been published/updated and its
 * cache entry has been refreshed.
 * <p>
 * Carries every field that {@link ResourcePublishedFilter} can test against so
 * that no database round-trip is needed during dispatch.
 */
public class ResourcePublishedEvent {

    // Core identity
    public final String service;
    public final String name;
    public final String identifier;

    // Transaction signature encoded as Base58 — used as dedup key
    public final String signature;

    // Metadata fields (may be null if no metadata was stored)
    public final String title;
    public final String description;
    public final List<String> tags;
    public final String category;

    // Timestamps (epoch ms)
    public final Long created;
    public final Long updated;

    // Creator's account level at the time the event fires (may be null if lookup failed)
    public final Integer creatorLevel;

    public ResourcePublishedEvent(
            String service,
            String name,
            String identifier,
            String signature,
            String title,
            String description,
            List<String> tags,
            String category,
            Long created,
            Long updated,
            Integer creatorLevel) {
        this.service      = service;
        this.name         = name;
        this.identifier   = identifier;
        this.signature    = signature;
        this.title        = title;
        this.description  = description;
        this.tags         = tags;
        this.category     = category;
        this.created      = created;
        this.updated      = updated;
        this.creatorLevel = creatorLevel;
    }
}
