package com.example.ratelimiter.rules;

public record Rule(String algorithm, long limit, long windowMs) {

    public String encode() {
        return algorithm + "|" + limit + "|" + windowMs;
    }

    public static Rule decode(String text) {
        String[] p = text.split("\\|");
        return new Rule(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]));
    }
}
