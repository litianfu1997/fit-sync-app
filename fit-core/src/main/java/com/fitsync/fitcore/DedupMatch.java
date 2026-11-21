package com.fitsync.fitcore;

public record DedupMatch(String action, Long matchedCanonicalId, int score, String reason) {}
