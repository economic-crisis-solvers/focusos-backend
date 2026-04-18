package com.focusos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ContentAnalysisService
 * ----------------------
 * Analyzes what a user is actually viewing on a URL to give a more accurate
 * URL category than simple domain matching.
 *
 * Supports:
 *   - YouTube: fetches video title + description via YouTube Data API → Groq
 *   - Reddit: classifies by subreddit name (no HTTP fetch needed)
 *   - Twitter/X: always social (blocks scraping anyway)
 *   - LinkedIn, Medium, Substack, Quora: fetch page meta → Groq
 *
 * Result overrides the generic url_category from Chrome extension.
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

    // ── Reddit subreddit category maps ────────────────────────────────────

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
        "googlecloud", "devops", "softwareengineering", "backend",
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
     * Analyzes a URL and returns a refined category.
     * Returns null if analysis fails or URL isn't worth analyzing —
     * caller should fall back to Chrome extension's category.
     */
    public String analyzeUrl(String url) {
        if (url == null || url.isEmpty()) return null;

        try {
            // YouTube — use YouTube Data API
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                return analyzeYouTubeUrl(url);
            }

            // Reddit — classify by subreddit name, no scraping needed
            if (url.contains("reddit.com")) {
                return analyzeRedditUrl(url);
            }

            // Twitter/X — always social, blocks scraping anyway
            if (url.contains("twitter.com") || url.contains("x.com")) {
                System.out.println("[ContentAnalysis] Twitter/X detected → social");
                return "social";
            }

            // LinkedIn, Medium, Substack, Quora — fetch page meta → Groq
            return analyzeGenericUrl(url);

        } catch (Exception e) {
            System.out.println("[ContentAnalysis] Analysis failed for " + url + ": " + e.getMessage());
            return null;
        }
    }

    // ── Reddit Analysis ───────────────────────────────────────────────────

    private String analyzeRedditUrl(String url) {
        Matcher matcher = REDDIT_SUBREDDIT_PATTERN.matcher(url);
        if (!matcher.find()) {
            // No subreddit in URL (e.g. reddit.com homepage) — treat as social
            return "social";
        }

        String subreddit = matcher.group(1).toLowerCase();
        System.out.println("[ContentAnalysis] Reddit subreddit detected: r/" + subreddit);

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

        // Unknown subreddit — fall back to Groq with just the subreddit name
        System.out.println("[ContentAnalysis] Unknown subreddit r/" + subreddit + " — asking Groq");
        try {
            return classifyWithGroq("Reddit community name: r/" + subreddit
                + ". Classify what kind of content this community likely discusses.");
        } catch (Exception e) {
            System.out.println("[ContentAnalysis] Groq fallback failed: " + e.getMessage());
            return "other";
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

        String body = response.body();
        String title = extractJsonField(body, "title");
        String description = extractJsonField(body, "description");

        if (title == null) return null;

        String shortDesc = description != null && description.length() > 300
            ? description.substring(0, 300) : description;

        return classifyWithGroq(
            "YouTube video title: " + title + "\nDescription: " + shortDesc
        );
    }

    private String extractYouTubeVideoId(String url) {
        Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── Generic URL Analysis (LinkedIn, Medium, Substack, Quora) ─────────

    private String analyzeGenericUrl(String url) throws Exception {
        if (!isAmbiguousSite(url)) return null;

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

    private boolean isAmbiguousSite(String url) {
        return url.contains("linkedin.com")
            || url.contains("medium.com")
            || url.contains("substack.com")
            || url.contains("quora.com");
    }

    // ── Groq Classification ──────────────────────────────────────────────

    private String classifyWithGroq(String content) throws Exception {
        if (groqApiKey == null || groqApiKey.isEmpty()) {
            System.out.println("[ContentAnalysis] Groq API key not set — skipping classification");
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

    // OpenAI-compatible response: {"choices": [{"message": {"content": "educational"}}]}
    private String extractGroqResponse(String json) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
