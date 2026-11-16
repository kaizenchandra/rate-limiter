package com.example.ratelimiter.application;

import com.example.ratelimiter.domain.*;

import java.util.*;

import org.springframework.stereotype.Component;

@Component
public class PolicyResolver {
    public List<Policy> resolve(Context c, List<Policy> policies) {
        Map<String, Policy> selected = new TreeMap<>();
        for (Policy p : policies) {
            if (!(p.tenant().equals("*") || p.tenant().equals(c.tenant()))
                    || !(p.tier().equals("*") || p.tier().equals(c.tier()))
                    || !(p.endpoint().equals("*") || p.endpoint().equals(c.endpoint()))) continue;
            selected.merge(
                    p.ruleName(),
                    p,
                    (a, b) ->
                            score(a) > score(b) || (score(a) == score(b) && a.id().compareTo(b.id()) < 0)
                                    ? a
                                    : b);
        }
        return selected.values().stream().filter(Policy::enabled).toList();
    }

    private int score(Policy p) {
        return (p.tenant().equals("*") ? 0 : 4)
                + (p.tier().equals("*") ? 0 : 2)
                + (p.endpoint().equals("*") ? 0 : 1);
    }
}
