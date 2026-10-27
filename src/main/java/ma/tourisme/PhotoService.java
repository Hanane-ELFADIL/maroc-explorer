package ma.tourisme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Télécharge automatiquement de VRAIES photos (Wikipédia / Wikimedia Commons) dans images/<collection>/<slug>.jpg.
 * - une photo n'est jamais utilisée deux fois (suivi dans images/credits.tsv) ;
 * - les crédits (auteur, licence, page du fichier) sont enregistrés et affichés dans l'application ;
 * - s'exécute en arrière-plan après le démarrage ; les éléments sans photo gardent leur illustration.
 * Désactivation : app.photos=off
 */
@Service
public class PhotoService {
    private static final List<String> COLS = List.of("cities", "attractions", "hotels", "restaurants", "activities", "events", "transport");
    private static final List<String> EXT = List.of("jpg", "jpeg", "png", "webp");
    private static final Pattern BAD = Pattern.compile("(?i)(logo|flag|drapeau|blason|coat.of.arms|carte|map[_ .-]|locator|diagram|schema|icon|stamp|poster|affiche|screenshot|chart|signature|seal|emblem|banner|banknote|timbre|portrait|passport)");

    private final MongoTemplate t;
    private final ObjectMapper om;
    private final Path images;
    private final boolean enabled, retry;
    private final String ua;
    private final long delay;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();
    private final Map<String, String[]> credits = new ConcurrentHashMap<>();

    static class RateLimited extends RuntimeException { RateLimited(String m) { super(m); } }

    record Cand(String key, String imgUrl, String pageUrl, String artist, String license) {}

    public PhotoService(MongoTemplate t, ObjectMapper om, @Value("${app.images-dir:images}") String dir,
                        @Value("${app.photos:auto}") String mode, @Value("${app.photos.retry:false}") boolean retry,
                        @Value("${app.photos.contact:projet-etudiant}") String contact, @Value("${app.photos.delay-ms:1500}") long delay) {
        this.t = t; this.om = om; this.images = Path.of(dir).toAbsolutePath(); this.enabled = !"off".equals(mode); this.retry = retry;
        this.ua = "MarocExplorer/1.0 (educational project; contact: " + contact + ")"; this.delay = delay;
    }

    /** [col, slug, fichier, page du fichier, auteur, licence, url] ou null. */
    public String[] credit(String col, String slug) { return credits.get(col + "/" + slug); }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loadState();
        if (!enabled) return;
        Thread th = new Thread(this::run, "photo-sync");
        th.setDaemon(true);
        th.start();
    }

    // ---------------------------------------------------------------- état (crédits, échecs)
    private void loadState() {
        try {
            Path c = images.resolve("credits.tsv");
            if (Files.exists(c)) for (String l : Files.readAllLines(c, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length >= 7) { credits.put(p[0] + "/" + p[1], p); used.add(norm(p[2])); }
            }
            Path m = images.resolve("photo-misses.txt");
            if (!retry && Files.exists(m)) misses.addAll(Files.readAllLines(m, StandardCharsets.UTF_8));
        } catch (Exception e) { System.out.println("[photos] lecture de l'état : " + e.getMessage()); }
    }

    private void append(String file, String line) throws Exception {
        Files.createDirectories(images);
        Files.writeString(images.resolve(file), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private boolean hasLocal(String col, String slug) {
        for (String e : EXT) if (Files.exists(images.resolve(col).resolve(slug + "." + e))) return true;
        return false;
    }

    // ---------------------------------------------------------------- boucle principale
    private void run() {
        int ok = 0, miss = 0, netFails = 0;
        System.out.println("[photos] recherche de photos reelles en arriere-plan (une photo toutes les ~" + (delay / 1000.0) + " s, ctrl-C sans risque : la reprise est automatique)...");
        try {
            for (String col : COLS) {
                Files.createDirectories(images.resolve(col));
                for (Document d : t.findAll(Document.class, col)) {
                    String slug = d.getString("slug"), key = col + "/" + slug;
                    if (slug == null || hasLocal(col, slug) || misses.contains(key)) continue;
                    boolean done = false;
                    int limited = 0;
                    while (!done) {
                        try {
                            if (fetchFor(col, d)) ok++; else { miss++; misses.add(key); append("photo-misses.txt", key); }
                            netFails = 0; done = true;
                        } catch (RateLimited e) {
                            if (++limited > 4) { System.out.println("[photos] Wikimedia limite toujours le debit : arret pour cette session. Relancez l'application plus tard, la reprise est automatique."); return; }
                            System.out.println("[photos] limite de debit Wikimedia (" + e.getMessage() + ") : pause de 2 minutes puis reprise (" + limited + "/4)...");
                            Thread.sleep(120_000);
                        } catch (java.io.IOException e) {
                            if (++netFails >= 5) { System.out.println("[photos] pas de connexion a Wikimedia, arret : " + e.getMessage()); return; }
                            done = true;
                        } catch (Exception e) { done = true; }
                    }
                    Thread.sleep(delay);
                }
            }
        } catch (Exception e) { System.out.println("[photos] erreur : " + e.getMessage()); }
        System.out.println("[photos] termine : " + ok + " photo(s) ajoutee(s), " + miss + " sans resultat (illustration conservee). Actualisez la page.");
    }

    private boolean fetchFor(String col, Document d) throws Exception {
        String name = d.getString("name"), city = String.valueOf(d.getString("city")), slug = d.getString("slug");
        Cand c = null;
        if (col.equals("cities")) c = wikipedia(name + " Maroc", name);
        else if (col.equals("attractions")) c = wikipedia(name + " " + city, name);
        if (c == null) for (String q : queries(col, d)) { c = commons(q); if (c != null) break; Thread.sleep(delay); }
        if (c == null) return false;
        byte[] img = download(c.imgUrl());
        boolean jpg = img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8, png = img.length > 4 && img[1] == 'P' && img[2] == 'N';
        if (img.length < 15_000 || !(jpg || png)) return false;
        Files.write(images.resolve(col).resolve(slug + (jpg ? ".jpg" : ".png")), img);
        used.add(c.key());
        String[] row = {col, slug, c.key(), c.pageUrl(), c.artist(), c.license(), c.imgUrl()};
        credits.put(col + "/" + slug, row);
        append("credits.tsv", String.join("\t", Arrays.stream(row).map(s -> s.replace('\t', ' ').replace('\n', ' ')).toList()));
        System.out.println("[photos] OK " + name);
        return true;
    }

    // ---------------------------------------------------------------- requêtes de secours par catégorie
    private List<String> queries(String col, Document d) {
        String name = String.valueOf(d.getString("name")), city = String.valueOf(d.getString("city"));
        String type = String.valueOf(d.getString("type")), low = name.toLowerCase(), cuisine = String.valueOf(d.getString("cuisine")).toLowerCase();
        List<String> q = new ArrayList<>();
        switch (col) {
            case "cities" -> { q.add(name + " Maroc"); q.add(name + " Morocco medina"); }
            case "attractions" -> { q.add(name + " " + city); q.add(name); }
            case "hotels" -> {
                q.add(name + " " + city);
                q.add(type.equals("Riad") || type.startsWith("Maison") ? "riad " + city : "hôtel " + city);
                q.add(type.equals("Riad") || type.startsWith("Maison") ? "riad patio Maroc" : "hotel piscine Maroc");
            }
            case "restaurants" -> {
                q.add(name + " " + city);
                if (type.equals("Café") || low.contains("café")) { q.add("café terrasse " + city); q.add("thé à la menthe Maroc"); }
                else if (cuisine.contains("fruits de mer") || low.contains("poisson")) { q.add("poisson grillé " + city); q.add("grillades poisson port Maroc"); }
                else if (type.equals("Street food") || low.contains("street")) { q.add("street food " + city); q.add("brochettes Maroc marché"); }
                else if (type.equals("Rooftop")) { q.add("terrasse rooftop " + city); q.add("restaurant terrasse Maroc"); }
                else { q.add("tajine " + city); q.add("tajine Maroc cuisine"); q.add("couscous Maroc"); }
            }
            case "activities" -> {
                q.add(name + " " + city);
                String[][] kw = {{"surf", "surf"}, {"kitesurf", "kitesurf"}, {"hammam", "hammam Maroc"}, {"cuisine", "cuisine marocaine atelier tajine"}, {"dromadaire", "dromadaire plage"},
                    {"quad", "quad désert Maroc"}, {"randonnée", "randonnée"}, {"tissage", "tissage Maroc artisan"}, {"photo", "ruelles"}, {"vélo", "vélo palmeraie Maroc"},
                    {"bateau", "bateau excursion"}, {"4x4", "4x4 Atlas Maroc"}, {"cheval", "cheval plage Maroc"}, {"golf", "golf Maroc"}, {"marqueterie", "marqueterie thuya"},
                    {"argan", "argan coopérative"}, {"désert", "bivouac désert Maroc"}, {"excursion", "excursion"}, {"dégustation", "jben miel Maroc"}};
                for (String[] k : kw) if (low.contains(k[0])) { q.add(k[1] + " " + city); q.add(k[1]); }
                q.add("Maroc " + String.valueOf(d.getString("activity_type")) + " " + city);
            }
            case "events" -> { q.add(name); q.add(name + " " + city); q.add("festival " + city); }
            default -> {
                String mode = String.valueOf(d.getString("mode")).toLowerCase();
                if (type.equals("route")) {
                    if (mode.contains("train")) { q.add("Al Boraq train"); q.add("train ONCF Maroc"); }
                    else if (mode.contains("taxi")) q.add("grand taxi Maroc");
                    q.add("autocar CTM Maroc"); q.add("bus Maroc route");
                } else if (type.contains("aéroport")) { q.add(name); q.add("aéroport " + city); q.add("aéroport Maroc terminal"); }
                else if (type.contains("gare")) { q.add(name); q.add("gare routière " + city); q.add("gare Maroc"); }
                else if (type.equals("taxi")) { q.add("petit taxi " + city); q.add("taxi Maroc"); }
                else if (type.equals("tramway")) q.add("tramway Rabat Salé");
                else { q.add(name); q.add(name + " " + city); }
            }
        }
        return q;
    }

    // ---------------------------------------------------------------- sources
    private Cand wikipedia(String query, String mustContain) throws Exception {
        String url = "https://fr.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=4&prop=pageimages&piprop=thumbnail%7Cname&pithumbsize=960&gsrsearch=" + enc(query);
        for (JsonNode p : sorted(api(url))) {
            String title = p.path("title").asText(""), thumb = p.path("thumbnail").path("source").asText(""), file = p.path("pageimage").asText("");
            if (thumb.isEmpty() || file.isEmpty() || BAD.matcher(file).find() || file.toLowerCase().endsWith(".svg") || p.path("thumbnail").path("width").asInt(0) < 600) continue;
            if (!plausible(title, mustContain)) continue;
            String key = norm(file);
            if (used.contains(key)) continue;
            return new Cand(key, thumb, "https://commons.wikimedia.org/wiki/File:" + file.replace(' ', '_'), "", "Wikimedia Commons");
        }
        return null;
    }

    private Cand commons(String query) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=15&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=960&gsrsearch="
                + enc(query + " filetype:bitmap");
        for (JsonNode p : sorted(api(url))) {
            String title = p.path("title").asText("");
            JsonNode ii = p.path("imageinfo").path(0);
            int w = ii.path("width").asInt(0), h = ii.path("height").asInt(1);
            double ratio = w / (double) Math.max(h, 1);
            if (!ii.path("mime").asText("").equals("image/jpeg") || w < 1000 || ratio < 1.15 || ratio > 2.4 || BAD.matcher(title).find()) continue;
            String key = norm(title);
            if (used.contains(key)) continue;
            String thumb = ii.path("thumburl").asText(ii.path("url").asText(""));
            JsonNode meta = ii.path("extmetadata");
            return new Cand(key, thumb, ii.path("descriptionurl").asText(""), clean(meta.path("Artist").path("value").asText("")), meta.path("LicenseShortName").path("value").asText(""));
        }
        return null;
    }

    // ---------------------------------------------------------------- utilitaires
    private List<JsonNode> sorted(JsonNode root) {
        List<JsonNode> l = new ArrayList<>();
        root.path("query").path("pages").elements().forEachRemaining(l::add);
        l.sort(Comparator.comparingInt(n -> n.path("index").asInt(999)));
        return l;
    }

    private static long retryAfter(HttpResponse<?> r, long fallbackSec) {
        try { return Math.min(Math.max(Long.parseLong(r.headers().firstValue("retry-after").orElse("")), 5), 180); }
        catch (NumberFormatException e) { return fallbackSec; }
    }

    private JsonNode api(String url) throws Exception {
        for (int i = 1; ; i++) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", ua).timeout(Duration.ofSeconds(25)).build(), HttpResponse.BodyHandlers.ofString());
            int sc = r.statusCode();
            if (sc == 200) return om.readTree(r.body());
            if (sc != 429 && sc < 500) throw new IllegalStateException("HTTP " + sc);
            if (i >= 3) throw new RateLimited("HTTP " + sc);
            Thread.sleep(retryAfter(r, 15L * i) * 1000);
        }
    }

    private byte[] download(String url) throws Exception {
        for (int i = 1; ; i++) {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", ua).timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
            int sc = r.statusCode();
            if (sc == 200) return r.body();
            if (sc != 429 && sc < 500) throw new IllegalStateException("HTTP " + sc);
            if (i >= 3) throw new RateLimited("HTTP " + sc);
            Thread.sleep(retryAfter(r, 15L * i) * 1000);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String clean(String html) { String s = html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim(); return s.length() > 90 ? s.substring(0, 90) : s; }

    static String norm(String s) {
        String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return x.replaceAll("^file:", "").replaceAll("[^a-z0-9]+", "");
    }

    private static boolean plausible(String title, String name) {
        String t = norm(title);
        for (String w : Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().split("[^a-z0-9]+"))
            if (w.length() > 3 && t.contains(w)) return true;
        return false;
    }
}
