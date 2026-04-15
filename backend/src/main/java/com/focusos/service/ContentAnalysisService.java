package com.focusos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ContentAnalysisService
 * ----------------------
 * Analyzes what a user is actually viewing on a URL to give a more accurate
 * URL category than simple domain matching.
 *
 * Supports:
 *   - YouTube: fetches video title + description via YouTube Data API
 *   - Any webpage: fetches page title + meta description from HTML
 *
 * Then classifies using Claude API: work / educational / entertainment / other
 *
 * Result overrides the generic url_category from Chrome extension.
 */
@Service
public class ContentAnalysisService {

    @Value("${youtube.api-key:}")
    private String youtubeApiKey;

    @Value("${anthropic.api-key:}")
    private String anthropicApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    // YouTube URL patterns
    private static final Pattern YOUTUBE_VIDEO_PATTERN =
        Pattern.compile("(?:youtube\\.com/watch\\?.*v=|youtu\\.be/)([a-zA-Z0-9_-]{11})");

    /**
     * Analyzes a URL and returns a refined category.
     * Returns null if analysis fails or URL isn't worth analyzing —
     * caller should fall back to Chrome extension's category.
     *
     * Categories returned: "work" | "educational" | "entertainment" | "social" | "other"
     */
    public String analyzeUrl(String url) {
        if (url == null || url.isEmpty()) return null;

        try {
            // YouTube — use YouTube Data API for accurate video metadata
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                return analyzeYouTubeUrl(url);
            }

            // Other sites — fetch page title + meta description
            return analyzeGenericUrl(url);

        } catch (Exception e) {
            System.out.println("[ContentAnalysis] Analysis failed for " + url + ": " + e.getMessage());
            return null;
        }
    }

    // ── YouTube Analysis ─────────────────────────────────────────────────

    private String analyzeYouTubeUrl(String url) throws Exception {
        if (youtubeApiKey == null || youtubeApiKey.isEmpty()) {
            System.out.println("[ContentAnalysis] YouTube API key not set — skipping YouTube analysis");
            return null;
        }

        String videoId = extractYouTubeVideoId(url);
        if (videoId == null) return null;

        // Fetch video metadata from YouTube Data API
        String apiUrl = "https://www.googleapis.com/youtube/v3/videos"
            + "?id=" + videoId
            + "&part=snippet"
            + "&fields=items(snippet(title,description,tags,categoryId))"
            + "&key=" + youtubeApiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("[ContentAnalysis] YouTube API error: " + response.statusCode());
            return null;
        }

        // Extract title and description from response
        String body = response.body();
        String title = extractJsonField(body, "title");
        String description = extractJsonField(body, "description");

        if (title == null) return null;

        // Truncate description to keep Claude prompt short
        String shortDesc = description != null && description.length() > 300
            ? description.substring(0, 300) : description;

        return classifyWithClaude(
            "YouTube video title: " + title + "\nDescription: " + shortDesc
        );
    }

    private String extractYouTubeVideoId(String url) {
        Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── Generic URL Analysis ─────────────────────────────────────────────

    private String analyzeGenericUrl(String url) throws Exception {
        // Only analyze sites that could be ambiguous
        if (!isAmbiguousSite(url)) return null;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        // Extract title tag
        String title = extractHtmlTag(html, "title");

        // Extract meta description
        String metaDesc = extractMetaDescription(html);

        if (title == null && metaDesc == null) return null;

        String content = "Page title: " + (title != null ? title : "unknown");
        if (metaDesc != null) content += "\nMeta description: " + metaDesc;

        return classifyWithClaude(content);
    }

    private boolean isAmbiguousSite(String url) {
        // Sites where content type matters — not obviously work or entertainment
        return url.contains("linkedin.com")
            || url.contains("reddit.com")
            || url.contains("twitter.com")
            || url.contains("x.com")
            || url.contains("medium.com")
            || url.contains("substack.com")
            || url.contains("quora.com");
    }

    // ── Claude Classification ────────────────────────────────────────────

    private String classifyWithClaude(String content) throws Exception {
        if (anthropicApiKey == null || anthropicApiKey.isEmpty()) {
            System.out.println("[ContentAnalysis] Anthropic API key not set — skipping classification");
            return null;
        }

        String prompt = """
            Classify the following web content into exactly one category.
            Reply with ONLY one word from this list: work, educational, entertainment, social, other

            Rules:
            - work: professional tools, coding, productivity, business tasks
            - educational: learning, tutorials, courses, academic research, how-to guides
            - entertainment: videos for fun, gaming, memes, news for leisure, social browsing
            - social: messaging, social media not for learning
            - other: unclear or mixed

            Content to classify:
            """ + content + """

            Reply with one word only.
            """;

        String requestBody = """
            {
                "model": "claude-haiku-4-5-20251001",
                "max_tokens": 10,
                "messages": [{"role": "user", "content": "%s"}]
            }
            """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("[ContentAnalysis] Claude API error: " + response.statusCode());
            return null;
        }

        String responseBody = response.body();
        String result = extractClaudeResponse(responseBody);

        if (result == null) return null;

        result = result.trim().toLowerCase();
        System.out.println("[ContentAnalysis] Classified as: " + result);

        // Validate response is one of our expected categories
        if (result.equals("work") || result.equals("educational") ||
            result.equals("entertainment") || result.equals("social") || result.equals("other")) {
            return result;
        }

        return null;
    }

    // ── HTML Parsing Helpers ─────────────────────────────────────────────

    private String extractHtmlTag(String html, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + "[^>]*>([^<]+)</" + tag + ">",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractMetaDescription(String html) {
        Pattern pattern = Pattern.compile(
            "<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) return matcher.group(1).trim();

        // Try reversed attribute order
        pattern = Pattern.compile(
            "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*name=[\"']description[\"']",
            Pattern.CASE_INSENSITIVE);
        matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractClaudeResponse(String json) {
        Pattern pattern = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
