package com.example.ratelimiter.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {

    @GetMapping("/api/search")
    public Map<String, String> search() {
        return Map.of("api", "search", "message", "here are your search results");
    }

    @GetMapping("/api/export")
    public Map<String, String> export() {
        return Map.of("api", "export", "message", "here is your (expensive) export");
    }
}
