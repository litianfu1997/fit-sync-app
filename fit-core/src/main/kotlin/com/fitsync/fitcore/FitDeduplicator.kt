package com.fitsync.fitcore

import com.fitsync.app.domain.dedup.DedupEngine
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.fit.LatLng

/** 向 Java 服务暴露现有去重引擎，保留传感器评分与运动类型兼容规则。 */
class FitDeduplicator {
    fun decide(input: ByteArray, candidates: List<DedupCandidate>): DedupMatch {
        val bytes = FitParser.normalize(input)
        FitParser().parse(bytes)
        val incoming = FitEngine().parseBytes(bytes)
        val engine = DedupEngine()
        val filtered = engine.filterCandidates(candidates, incoming.startTimeEpochMs!!, incoming.distanceMeters, incoming.sportType)
        val decision = engine.decide(incoming, filtered.map { candidate ->
            candidate to candidate.track.map { LatLng(it.lat(), it.lon()) }
        })
        return DedupMatch(decision.action.name, decision.matchedCanonicalId, decision.score, decision.reason)
    }
}
