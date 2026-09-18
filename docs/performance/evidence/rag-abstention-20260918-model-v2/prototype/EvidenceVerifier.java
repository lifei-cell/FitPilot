package com.fitpilot.rag.application;

/** Judges whether one retrieved passage directly supports answering a query. */
public interface EvidenceVerifier {
    boolean available();
    boolean supports(String query, String passage);
}
