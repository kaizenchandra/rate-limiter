package com.example.ratelimiter.infrastructure.persistence;

import com.example.ratelimiter.domain.Policy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PolicyRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public PolicyRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load() {
        long revision =
                db.queryForObject("select revision from policy_revision where singleton", Long.class);
        return new Snapshot(
                revision,
                db.query(
                        "select document::text from policies order by id limit 10001",
                        (r, n) -> decode(r.getString(1))),
                System.nanoTime());
    }

    @Transactional
    public Policy save(Policy p, long expected, String actor) {
        p.validateSemantics();
        long revision =
                db.queryForObject(
                        "select revision from policy_revision where singleton for update", Long.class);
        var old = db.query("select version from policies where id=?", (r, n) -> r.getLong(1), p.id());
        if ((old.isEmpty() ? 0 : old.getFirst()) != expected) throw new Conflict();
        if (old.isEmpty() && db.queryForObject("select count(*) from policies", Long.class) >= 10000)
            throw new IllegalArgumentException("Policy count limit reached");
        if (revision >= 9_000_000_000_000_000L)
            throw new IllegalStateException("Revision numeric bound reached");
        var catalog = db.query("select document::text from policies", (r, n) -> decode(r.getString(1)));
        if (catalog.stream()
                .anyMatch(v -> v.ruleName().equals(p.ruleName()) && v.dimension() != p.dimension()))
            throw new IllegalArgumentException("Rule dimension must stay stable");
        if (catalog.stream().map(Policy::ruleName).distinct().count() >= 16
                && catalog.stream().noneMatch(v -> v.ruleName().equals(p.ruleName())))
            throw new IllegalArgumentException("At most sixteen rule names");
        Policy saved = p.withVersion(revision + 1);
        String doc = encode(saved);
        db.update(
                "insert into policies values(?,?,?::jsonb) on conflict(id) do update set"
                        + " version=excluded.version, document=excluded.document",
                p.id(),
                revision + 1,
                doc);
        db.update("update policy_revision set revision=? where singleton", revision + 1);
        db.update(
                "insert into policy_history(revision,policy_id,actor,document) values(?,?,?,?::jsonb)",
                revision + 1,
                p.id(),
                actor,
                doc);
        return saved;
    }

    public List<Policy> history(String id) {
        return db.query(
                "select document::text from policy_history where policy_id=? order by revision desc limit"
                        + " 100",
                (r, n) -> decode(r.getString(1)),
                id);
    }

    private Policy decode(String s) {
        try {
            return json.readValue(s, Policy.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored policy", e);
        }
    }

    private String encode(Policy p) {
        try {
            return json.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public record Snapshot(long revision, List<Policy> policies, long loadedNanos) {
    }

    public static class Conflict extends RuntimeException {
    }
}
