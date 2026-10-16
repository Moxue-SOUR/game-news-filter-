import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Small feasibility probe, not the application's production collector. Uses only JDK 21. */
public class FeedSourceProbe {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    record Target(String id, String name, URI uri) {}
    record Response(int status, String type, String finalUrl, byte[] body, String error) {}
    record Entry(String title, String url, String id, String rawDate, Instant date, int textChars) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException("Usage: FeedSourceProbe.java sources.tsv output-dir [--offline response-dir]");
        }
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Path offline = args.length == 4 && args[2].equals("--offline") ? Path.of(args[3]) : null;
        if (args.length == 4 && offline == null) throw new IllegalArgumentException("Unknown option");
        List<String> summary = new ArrayList<>();
        List<String> articles = new ArrayList<>();
        summary.add(csv("source_id", "source_name", "requested_url", "final_url", "checked_at_utc", "http_status",
            "content_type", "bytes", "feed_kind", "items", "with_title", "with_link", "with_id", "with_date",
            "with_text", "min_text_chars", "median_text_chars", "max_text_chars", "oldest_date", "newest_date", "error"));
        articles.add(csv("source_id", "source_name", "item_number", "title", "url", "entry_id", "raw_published",
            "published_utc", "text_chars"));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("id\t") || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 3 || !parts[0].matches("[a-z0-9-]+")) throw new IllegalArgumentException("Bad source row");
                Target target = new Target(parts[0], parts[1], URI.create(parts[2]));
                if (!target.uri().getScheme().equals("https")) throw new IllegalArgumentException("HTTPS required");
                String checkedAt = Instant.now().toString();
                Response response;
                try {
                    response = offline != null ? readOffline(target, offline) : fetch(client, target);
                } catch (Exception exception) {
                    response = new Response(0, "", target.uri().toString(), new byte[0], error(exception));
                }
                List<Entry> entries = List.of();
                String kind = "NOT_PARSED";
                String parseError = response.error();
                if (response.status() == 200 && response.body().length > 0) {
                    if (response.type().toLowerCase().contains("text/html")) {
                        kind = "HTML_NOT_FEED";
                        parseError = "HTTP 200 returned HTML; no feed validated";
                    } else {
                        try {
                            Element root = parse(response.body());
                            String rootName = local(root);
                            if (rootName.equalsIgnoreCase("rss") || rootName.equalsIgnoreCase("RDF")) {
                                kind = "RSS";
                                entries = readEntries(root, "item", false, response.finalUrl());
                            } else if (rootName.equalsIgnoreCase("feed")) {
                                kind = "ATOM";
                                entries = readEntries(root, "entry", true, response.finalUrl());
                            } else {
                                kind = "XML_NOT_FEED";
                                parseError = "Unexpected XML root: " + rootName;
                            }
                        } catch (Exception exception) {
                            kind = "XML_PARSE_ERROR";
                            parseError = error(exception);
                        }
                    }
                } else if (response.status() != 200 && parseError.isEmpty()) {
                    parseError = "HTTP status " + response.status();
                }
                List<Integer> lengths = new ArrayList<>();
                List<Instant> dates = new ArrayList<>();
                int titles = 0, links = 0, ids = 0, validDates = 0, texts = 0, number = 0;
                for (Entry entry : entries) {
                    number++;
                    if (!entry.title().isBlank()) titles++;
                    if (!entry.url().isBlank()) links++;
                    if (!entry.id().isBlank()) ids++;
                    if (entry.date() != null) { validDates++; dates.add(entry.date()); }
                    if (entry.textChars() > 0) texts++;
                    lengths.add(entry.textChars());
                    articles.add(csv(target.id(), target.name(), number, entry.title(), entry.url(), entry.id(),
                        entry.rawDate(), entry.date() == null ? "" : entry.date(), entry.textChars()));
                }
                Collections.sort(lengths);
                Collections.sort(dates);
                summary.add(csv(target.id(), target.name(), target.uri(), response.finalUrl(), checkedAt, response.status(),
                    response.type(), response.body().length, kind, entries.size(), titles, links, ids, validDates, texts,
                    lengths.isEmpty() ? "" : lengths.getFirst(), lengths.isEmpty() ? "" : lengths.get(lengths.size() / 2),
                    lengths.isEmpty() ? "" : lengths.getLast(), dates.isEmpty() ? "" : dates.getFirst(),
                    dates.isEmpty() ? "" : dates.getLast(), parseError));
                System.out.printf("%s HTTP=%d type=%s items=%d dates=%d texts=%d%n", target.id(), response.status(), kind,
                    entries.size(), validDates, texts);
            }
        }
        Files.write(output.resolve("feed-summary.csv"), summary, StandardCharsets.UTF_8);
        Files.write(output.resolve("article-index.csv"), articles, StandardCharsets.UTF_8);
    }

    static Response fetch(HttpClient client, Target target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(target.uri()).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "GameNewsSourceValidation/0.1")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1")
            .GET().build();
        HttpResponse<byte[]> response = client.sendAsync(request, info -> new LimitedBodySubscriber())
            .orTimeout(25, TimeUnit.SECONDS).get();
        return new Response(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
            response.uri().toString(), response.body(), "");
    }

    static Response readOffline(Target target, Path root) throws Exception {
        Path body = root.resolve(target.id() + ".body");
        Path headers = root.resolve(target.id() + ".headers.txt");
        int status = 0;
        String type = "";
        if (Files.exists(headers)) {
            for (String line : Files.readAllLines(headers, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("HTTP/")) status = Integer.parseInt(line.split(" +")[1]);
                if (line.toLowerCase().startsWith("content-type:")) type = line.substring(line.indexOf(':') + 1).strip();
            }
        }
        Path errorFile = root.resolve(target.id() + ".error.txt");
        String problem = Files.exists(errorFile) ? Files.readString(errorFile).strip() : "";
        if (Files.exists(body) && Files.size(body) > MAX_BYTES) throw new IllegalStateException("Body exceeds limit");
        return new Response(status, type, target.uri().toString(), Files.exists(body) ? Files.readAllBytes(body) : new byte[0], problem);
    }

    static Element parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException exception) throws SAXParseException { throw exception; }
            @Override public void fatalError(SAXParseException exception) throws SAXParseException { throw exception; }
        });
        return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }

    static List<Entry> readEntries(Element root, String name, boolean atom, String base) {
        NodeList nodes = root.getElementsByTagNameNS("*", name);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String title = childText(item, "title");
            String link = childText(item, "link");
            if (atom) {
                for (Node child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element element && local(element).equals("link") &&
                        (element.getAttribute("rel").isEmpty() || element.getAttribute("rel").equals("alternate"))) {
                        link = element.getAttribute("href"); break;
                    }
                }
            }
            try {
                if (!link.isBlank()) {
                    URI uri = URI.create(base).resolve(link.strip());
                    link = uri.getScheme().equals("https") || uri.getScheme().equals("http") ? uri.toString() : "";
                }
            } catch (IllegalArgumentException exception) { link = ""; }
            String rawDate = first(childText(item, "pubDate"), childText(item, "published"),
                childText(item, "date"), childText(item, "updated"));
            String content = first(childText(item, "encoded"), childText(item, "content"),
                childText(item, "description"), childText(item, "summary"));
            // Length estimate only; this is not an HTML sanitizer for rendering.
            String plain = content.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
            entries.add(new Entry(title, link, first(childText(item, "guid"), childText(item, "id")),
                rawDate, parseDate(rawDate), plain.codePointCount(0, plain.length())));
        }
        return entries;
    }

    static String childText(Element item, String name) {
        for (Node child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && local(element).equals(name)) return element.getTextContent().strip();
        }
        return "";
    }
    static String local(Element element) { return element.getLocalName() == null ? element.getTagName() : element.getLocalName(); }
    static String first(String... values) { return Arrays.stream(values).filter(value -> !value.isBlank()).findFirst().orElse(""); }
    static Instant parseDate(String text) {
        if (text.isBlank()) return null;
        try { return Instant.parse(text); } catch (Exception ignored) {}
        try { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(text).toInstant(); } catch (Exception ignored) {}
        return null;
    }
    static String csv(Object... values) {
        return String.join(",", Arrays.stream(values).map(value -> "\"" + String.valueOf(value).replace("\"", "\"\"")
            .replace("\r", " ").replace("\n", " ") + "\"").toList());
    }
    static String error(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        String text = root.getClass().getSimpleName() + ": " + root.getMessage();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
    static class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> future = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return future; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); future.completeExceptionally(new IllegalStateException("Body exceeds 2 MiB")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable exception) { future.completeExceptionally(exception); }
        public void onComplete() { future.complete(bytes.toByteArray()); }
    }
}

