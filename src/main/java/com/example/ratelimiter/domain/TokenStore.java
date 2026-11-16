package com.example.ratelimiter.domain;

import java.util.List;

public interface TokenStore {
    Decision consume(Context context, List<Policy> policies, long revision, long cost);
}
