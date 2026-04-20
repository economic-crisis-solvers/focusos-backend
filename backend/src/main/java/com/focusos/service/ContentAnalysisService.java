package com.focusos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ContentAnalysisService
 * ----------------------
 * Analyzes what a user is actually viewing to give a more accurate
 * URL category than simple domain matching.
 *
 * Priority order for classification:
 *   1. pageTitle + pageDescription from Chrome extension DOM (most accurate, no scraping)
 *   2. YouTube Data API for YouTube URLs (when extension metadata unavailable)
 *   3. Reddit subreddit name lookup (rule-based, no API needed)
 *   4. Hardcoded rules for Twitter, Quora, LinkedIn (block scraping)
 *   5. Groq classification for Medium, Substack (allow scraping)
 */
@Service
public class ContentAnalysisService {

    @Value("${youtube.api-key:}")
    private String youtubeApiKey;

    @Value("${groq.api-key:}")
    private String groqApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private static final Pattern YOUTUBE_VIDEO_PATTERN =
        Pattern.compile("(?:youtube\\.com/watch\\?.*v=|youtu\\.be/)([a-zA-Z0-9_-]{11})");

    private static final Pattern REDDIT_SUBREDDIT_PATTERN =
        Pattern.compile("reddit\\.com/r/([a-zA-Z0-9_]+)");

    // ── Reddit subreddit maps ─────────────────────────────────────────────

    private static final Set<String> EDUCATIONAL_SUBREDDITS = Set.of(
        "learnprogramming", "learnjava", "learnpython", "learnjavascript",
        "learnmath", "science", "askscience", "explainlikeimfive", "eli5",
        "todayilearned", "til", "education", "edtech", "coursera", "udemy",
        "computerscience", "compsci", "datascience", "machinelearning",
        "artificialintelligence", "statistics", "physics", "chemistry",
        "biology", "history", "philosophy", "languagelearning", "math",
        "mathematics", "algebra", "calculus", "coding", "webdev",
        "androiddev", "iosprogramming", "devops", "cybersecurity",
        "netsec", "reverseengineering", "algorithms", "leetcode",
        "cscareerquestions", "programming", "java", "python", "javascript",
        "golang", "rust", "cpp", "csharp", "swift", "kotlin"
    );

    private static final Set<String> WORK_SUBREDDITS = Set.of(
        "productivity", "entrepreneur", "startups", "smallbusiness",
        "projectmanagement", "agile", "sysadmin", "aws", "azure",
        "googlecloud", "softwareengineering", "backend",
        "frontend", "fullstack", "dataengineering", "businessanalysis",
        "remotework", "freelance", "careerguidance", "jobs"
    );

    private static final Set<String> ENTERTAINMENT_SUBREDDITS = Set.of(
        "memes", "dankmemes", "funny", "gaming", "pcgaming", "games",
        "movies", "television", "netflix", "anime", "manga", "comics",
        "music", "videos", "gifs", "aww", "cats", "dogs", "sports",
        "nba", "nfl", "soccer", "football", "worldnews", "news",
        "politics", "entertainment", "celebrities", "pop", "hiphop",
        "askreddit", "tifu", "amitheasshole", "relationship_advice",
        "teenagers", "mildlyinteresting", "interestingasfuck",
        "facepalm", "cringe", "roastme", "showerthoughts"
    );

    private static final Set<String> SOCIAL_SUBREDDITS = Set.of(
        "casualconversation", "chat", "makefriends", "needafriend",
        "socialskills", "dating", "tinder", "relationships"
    );

    /**
     * Main entry point.
     * Now accepts pageTitle and pageDescription directly from Chrome extension.
     * If provided, these are used directly for Groq classification — no scraping needed.
     * Falls back to URL-based analysis if not provided.
     */
    public String analyzeUrl(String url, String pageTitle, String pageDescription) {
        if (url == null || url.isEmpty()) return null;

        try {
            // ── Priority 1: Use Chrome extension DOM metadata if available ──
            // This is always accurate — extension reads directly from the live page
            boolean hasMetadata = (pageTitle != null && !pageTitle.isEmpty())
                || (pageDescription != null && !pageDescription.isEmpty());

            if (hasMetadata) {
                // For YouTube, still use YouTube API for richer metadata
                // unless we already have good page metadata
                boolean isYouTube = url.contains("youtube.com") || url.contains("youtu.be");

                if (!isYouTube) {
                    // For all non-YouTube sites, use extension metadata directly
                    String content = "";
                    if (pageTitle != null && !pageTitle.isEmpty())
                        content += "Page title: " + pageTitle;
                    if (pageDescription != null && !pageDescription.isEmpty())
                        content += "\nDescription: " + pageDescription;

                    System.out.println("[ContentAnalysis] Using Chrome extension metadata for: " + url);
                    return classifyWithGroq(content);
                }
            }

            // ── Priority 2: YouTube — use YouTube Data API ─────────────────
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                return analyzeYouTubeUrl(url);
            }

            // ── Priority 3: Reddit — subreddit name lookup ─────────────────
            if (url.contains("reddit.com")) {
                return analyzeRedditUrl(url);
            }

            // ── Priority 4: Hardcoded rules (block scrapers) ───────────────
            if (url.contains("twitter.com") || url.contains("x.com")) {
                System.out.println("[ContentAnalysis] Twitter/X → social (hardcoded)");
                return "social";
            }
            if (url.contains("quora.com")) {
                System.out.println("[ContentAnalysis] Quora → educational (hardcoded)");
                return "educational";
            }
            if (url.contains("linkedin.com")) {
                System.out.println("[ContentAnalysis] LinkedIn → work (hardcoded)");
                return "work";
            }

            // ── Priority 5: Medium, Substack — fetch + Groq ────────────────
            return analyzeGenericUrl(url);

        } catch (Exception e) {
            System.out.println("[ContentAnalysis] Analysis failed for " + url + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Backwards-compatible overload — called when no metadata provided.
     * EventController calls the 3-arg version; this exists as safety fallback.
     */
    public String analyzeUrl(String url) {
        return analyzeUrl(url, null, null);
    }

    // ── Reddit Analysis ───────────────────────────────────────────────────

    private String analyzeRedditUrl(String url) {
        Matcher matcher = REDDIT_SUBREDDIT_PATTERN.matcher(url);
        if (!matcher.find()) return "social";

        String subreddit = matcher.group(1).toLowerCase();
        System.out.println("[ContentAnalysis] Reddit subreddit: r/" + subreddit);

        if (EDUCATIONAL_SUBREDDITS.contains(subreddit)) {
            System.out.println("[ContentAnalysis] Classified as: educational");
            return "educational";
        }
        if (WORK_SUBREDDITS.contains(subreddit)) {
            System.out.println("[ContentAnalysis] Classified as: work");
            return "work";
        }
        if (ENTERTAINMENT_SUBREDDITS.contains(subreddit)) {
            System.out.println("[ContentAnalysis] Classified as: entertainment");
            return "entertainment";
        }
        if (SOCIAL_SUBREDDITS.contains(subreddit)) {
            System.out.println("[ContentAnalysis] Classified as: social");
            return "social";
        }

        // Unknown subreddit — ask Groq
        System.out.println("[ContentAnalysis] Unknown subreddit r/" + subreddit + " — asking Groq");
        try {
            return classifyWithGroq("Reddit community name: r/" + subreddit
                + ". Classify what kind of content this community likely discusses.");
        } catch (Exception e) {
            return "other";
        }
    }

    // ── YouTube Analysis ─────────────────────────────────────────────────

    private String analyzeYouTubeUrl(String url) throws Exception {
        if (youtubeApiKey == null || youtubeApiKey.isEmpty()) {
            System.out.println("[ContentAnalysis] YouTube API key not set");
            return null;
        }

        String videoId = extractYouTubeVideoId(url);
        if (videoId == null) return null;

        String apiUrl = "https://www.googleapis.com/youtube/v3/videos"
            + "?id=" + videoId
            + "&part=snippet"
            + "&fields=items(snippet(title,description))"
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

        String body = response.body();
        String title = extractJsonField(body, "title");
        String description = extractJsonField(body, "description");
        if (title == null) return null;

        String shortDesc = description != null && description.length() > 300
            ? description.substring(0, 300) : description;

        return classifyWithGroq("YouTube video title: " + title + "\nDescription: " + shortDesc);
    }

    private String extractYouTubeVideoId(String url) {
        Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── Generic URL Analysis (Medium, Substack) ───────────────────────────

    private String analyzeGenericUrl(String url) throws Exception {
        if (!isScrapableSite(url)) return null;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        String title = extractHtmlTag(html, "title");
        String metaDesc = extractMetaDescription(html);
        if (title == null && metaDesc == null) return null;

        String content = "Page title: " + (title != null ? title : "unknown");
        if (metaDesc != null) content += "\nMeta description: " + metaDesc;

        return classifyWithGroq(content);
    }

    private boolean isScrapableSite(String url) {
        return url.contains("medium.com") || url.contains("substack.com");
    }

    // ── Groq Classification ──────────────────────────────────────────────

    private String classifyWithGroq(String content) throws Exception {
        if (groqApiKey == null || groqApiKey.isEmpty()) {
            System.out.println("[ContentAnalysis] Groq API key not set");
            return null;
        }

        String prompt = "Classify the following web content into exactly one category. "
            + "Reply with ONLY one word from this list: work, educational, entertainment, social, other\\n\\n"
            + "Rules:\\n"
            + "- work: professional tools, coding, productivity, business tasks\\n"
            + "- educational: learning, tutorials, courses, academic research, how-to guides\\n"
            + "- entertainment: videos for fun, gaming, memes, news for leisure, social browsing\\n"
            + "- social: messaging, social media not for learning\\n"
            + "- other: unclear or mixed\\n\\n"
            + "Content to classify:\\n"
            + content.replace("\"", "\\\"").replace("\n", "\\n")
            + "\\n\\nReply with one word only.";

        String requestBody = "{"
            + "\"model\": \"llama-3.1-8b-instant\","
            + "\"max_tokens\": 10,"
            + "\"temperature\": 0,"
            + "\"messages\": [{\"role\": \"user\", \"content\": \"" + prompt + "\"}]"
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + groqApiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("[ContentAnalysis] Groq API error: " + response.statusCode() + " — " + response.body());
            return null;
        }

        String result = extractGroqResponse(response.body());
        if (result == null) return null;

        result = result.trim().toLowerCase();
        System.out.println("[ContentAnalysis] Classified as: " + result);

        if (result.equals("work") || result.equals("educational") ||
            result.equals("entertainment") || result.equals("social") || result.equals("other")) {
            return result;
        }

        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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

    private String extractGroqResponse(String json) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
