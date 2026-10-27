package ma.tourisme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

@Service("places")
public class Places {
    public static final List<String> COLS = List.of("attractions", "hotels", "restaurants", "activities");
    private static final List<String> EXT = List.of("jpg", "jpeg", "png", "webp");
    private final MongoTemplate t;
    private final Path images;
    private final PhotoService photos;

    public Places(MongoTemplate t, PhotoService photos, @Value("${app.images-dir:images}") String dir) { this.t = t; this.photos = photos; this.images = Path.of(dir).toAbsolutePath(); }

    /** Crédit de la photo (texte + lien vers la page du fichier) ou null si l'image est une illustration. */
    public String[] credit(Document d) {
        String[] c = photos.credit(String.valueOf(d.getString("col")), String.valueOf(d.getString("slug")));
        if (c == null || localPhoto(d.getString("col"), d.getString("slug")) == null) return null;
        String who = (c[4] == null || c[4].isBlank() ? "" : c[4] + " · ") + (c[5] == null || c[5].isBlank() ? "Wikimedia Commons" : c[5]);
        return new String[]{who, c[3]};
    }

    // Real-photo fallbacks per category when no local JPG/PNG exists
    private static final Map<String, String> DEFAULT_PHOTOS = Map.of(
        "attractions", "https://upload.wikimedia.org/wikipedia/commons/thumb/9/90/Jemaa_el-Fnaa_at_dusk.jpg/1280px-Jemaa_el-Fnaa_at_dusk.jpg",
        "hotels",      "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3e/Hotel_La_Mamounia_Marrakesh.jpg/1280px-Hotel_La_Mamounia_Marrakesh.jpg",
        "restaurants", "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5b/Moroccan_tea_service.jpg/1280px-Moroccan_tea_service.jpg",
        "activities",  "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7e/Merzouga_desert_Morocco.jpg/1280px-Merzouga_desert_Morocco.jpg",
        "events",      "https://upload.wikimedia.org/wikipedia/commons/thumb/1/10/Gnaoua_Festival_Essaouira_2011.jpg/1280px-Gnaoua_Festival_Essaouira_2011.jpg",
        "transport",   "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8f/Train_Rabat_Maroc.jpg/1280px-Train_Rabat_Maroc.jpg",
        "cities",      "https://upload.wikimedia.org/wikipedia/commons/thumb/2/28/Hassan_II_Mosque%2C_Casablanca.jpg/1280px-Hassan_II_Mosque%2C_Casablanca.jpg"
    );

    // ---------- images : photo locale JPG/PNG > URL du document > photo par défaut réelle > SVG ----------
    private String localPhoto(String folder, String slug) {
        for (String e : EXT) if (Files.exists(images.resolve(folder).resolve(slug + "." + e))) return "/images/" + folder + "/" + slug + "." + e;
        return null;
    }

    private String externalUrl(Document d) {
        if (d.get("images") instanceof List<?> l) for (Object o : l) if (o instanceof String s && s.startsWith("http")) return s;
        return null;
    }

    public String img(Document d) {
        String folder = d.getString("col"), slug = d.getString("slug");
        if (folder == null || slug == null) return DEFAULT_PHOTOS.getOrDefault("cities", "/images/hero.png");
        String p = localPhoto(folder, slug);
        if (p != null) return p;
        String u = externalUrl(d);
        if (u != null) return u;
        // Skip SVG files for <img> tags — use real photo fallback instead
        return DEFAULT_PHOTOS.getOrDefault(folder, "/images/hero.png");
    }

    /** Requête Wikipédia à utiliser côté navigateur pour enrichir la photo si pas de photo locale. */
    public String wikiQuery(Document d) {
        String col = d.getString("col");
        if (col == null) return null;
        // Always try Wikipedia for attractions and cities that don't have a local real photo
        if (localPhoto(col, d.getString("slug")) != null) return null;
        if ("cities".equals(col)) return d.getString("name") + " Maroc";
        if ("attractions".equals(col)) return d.getString("name") + " " + d.getString("city");
        if ("hotels".equals(col)) return d.getString("name") + " " + d.getString("city") + " Maroc";
        return null;
    }

    // ---------- recherche ----------
    private Criteria budget(int level) {
        return new Criteria().orOperator(Criteria.where("price_level").lte(level), Criteria.where("price_level").is(null));
    }

    private static int lv(Document d, int none) { return d.get("price_level") instanceof Number n ? n.intValue() : none; }

    public List<Document> search(String city, String col, String q, Integer level, String cat, String sort) {
        List<Criteria> l = new ArrayList<>();
        if (city != null && !city.isBlank()) l.add(Criteria.where("city").is(city));
        if (q != null && !q.isBlank()) {
            String re = Pattern.quote(q.trim());
            l.add(new Criteria().orOperator(Criteria.where("name").regex(re, "i"), Criteria.where("tags").regex(re, "i"), Criteria.where("description").regex(re, "i")));
        }
        if (level != null) l.add(budget(level));
        if (cat != null && !cat.isBlank()) l.add(Criteria.where("category").is(cat));
        Query qu = new Query();
        if (!l.isEmpty()) qu.addCriteria(new Criteria().andOperator(l));
        List<Document> out = new ArrayList<>();
        for (String k : (col == null || col.isBlank()) ? COLS : List.of(col))
            for (Document d : t.find(qu, Document.class, k)) { d.put("col", k); out.add(d); }
        Comparator<Document> byName = Comparator.comparing(d -> String.valueOf(d.getString("name")).toLowerCase());
        Comparator<Document> cmp = switch (sort == null ? "" : sort) {
            case "price_asc" -> Comparator.comparingInt((Document d) -> lv(d, 99)).thenComparing(byName);
            case "price_desc" -> Comparator.comparingInt((Document d) -> lv(d, -1)).reversed().thenComparing(byName);
            case "city" -> Comparator.comparing((Document d) -> String.valueOf(d.getString("city"))).thenComparing(byName);
            default -> byName;
        };
        out.sort(cmp);
        return out;
    }

    public List<String> categories() {
        return t.findDistinct(new Query(), "category", "attractions", String.class).stream().filter(Objects::nonNull).sorted().toList();
    }

    public List<Document> byCol(String city, String col) {
        List<Document> out = t.find(Query.query(Criteria.where("city").is(city)), Document.class, col);
        out.forEach(d -> d.put("col", col));
        out.sort(Comparator.comparing(d -> String.valueOf(d.getString("name")).toLowerCase()));
        return out;
    }

    public List<Document> nearby(double lon, double lat, int meters) {
        String js = String.format(Locale.ROOT, "{location:{$nearSphere:{$geometry:{type:'Point',coordinates:[%f,%f]},$maxDistance:%d}}}", lon, lat, meters);
        List<Document> out = new ArrayList<>();
        for (String k : COLS)
            for (Document d : t.find(new BasicQuery(js).limit(15), Document.class, k)) { d.put("col", k); out.add(d); }
        return out;
    }

    // ---------- coordonnées et marqueurs ----------
    @SuppressWarnings("unchecked")
    static List<Number> xy(Document d) { return (List<Number>) ((Map<String, Object>) d.get("location")).get("coordinates"); }

    public List<Map<String, Object>> markers(List<Document> ds) {
        List<Map<String, Object>> m = new ArrayList<>();
        for (Document d : ds) {
            if (d.get("location") == null) continue;
            List<Number> c = xy(d);
            m.add(Map.of("name", String.valueOf(d.getString("name")), "col", String.valueOf(d.getString("col")), "slug", String.valueOf(d.getString("slug")),
                    "city", String.valueOf(d.getString("city")), "img", img(d), "lon", c.get(0), "lat", c.get(1)));
        }
        return m;
    }

    public String directions(Document d) {
        if (d.get("location") == null) return null;
        List<Number> c = xy(d);
        return "https://www.google.com/maps/dir/?api=1&destination=" + c.get(1) + "," + c.get(0);
    }

    // ---------- prix et fiches ----------
    private static String range(Map<?, ?> m) {
        Object mn = m.get("min"), mx = m.get("max");
        if (mn == null && mx == null) return "";
        return mn == null ? "≤ " + mx + " MAD" : mx == null ? "≥ " + mn + " MAD" : mn.equals(mx) ? mn + " MAD" : mn + "–" + mx + " MAD";
    }

    public String price(Document d) {
        for (String k : List.of("price_per_night_mad", "avg_price_per_person_mad"))
            if (d.get(k) instanceof Map<?, ?> m && !range(m).isEmpty()) return range(m);
        if (d.get("entry_price_mad") instanceof Map<?, ?> m && m.get("adult") != null) {
            Object adult = m.get("adult");
            if (Integer.valueOf(0).equals(adult) || Double.valueOf(0).equals(adult)) return "Gratuit";
            return adult + " MAD";
        }
        if (d.get("price_mad") instanceof Number n) {
            double v = n.doubleValue();
            return v == 0 ? "Gratuit" : n.intValue() + " MAD";
        }
        if (d.get("price_mad") instanceof Map<?, ?> m) return range(m);
        return "";
    }

    static final List<String> PK = List.of("address", "opening_hours", "entry_price_mad", "price_per_night_mad", "avg_price_per_person_mad", "price_mad", "stars",
        "visit_duration_min", "duration_hours", "best_time_to_visit", "season", "indoor_outdoor", "accessibility", "guided_tour_available", "amenities", "rooms_types",
        "breakfast_included", "distance_to_center_km", "cuisine", "specialties", "halal", "vegetarian_options", "alcohol_served", "reservation_required", "difficulty",
        "min_age", "provider_name", "group_size", "typical_period", "month", "date_start", "date_end", "date_confidence", "from_city", "to_city", "mode", "duration_min",
        "frequency", "suitable_for", "tags", "rating", "phone", "website");
    static final List<String> CK = List.of("population", "best_season", "climate", "average_daily_budget_mad", "typical_duration_days", "languages", "airport_nearby",
        "train_station", "local_specialties", "safety_tips", "nearby_cities");

    public List<String[]> facts(Document d, boolean city) {
        List<String[]> o = new ArrayList<>();
        for (String k : city ? CK : PK) {
            String v = fmt(d.get(k));
            if (!v.isBlank()) o.add(new String[]{k, v});
        }
        return o;
    }

    static String fmt(Object v) {
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "✔" : "✘";
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).collect(Collectors.joining(", "));
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("min") || m.containsKey("max")) return range(m);
            if (m.containsKey("adult")) {
                Object adult = m.get("adult");
                boolean allFree = List.of("adult","child","foreigner").stream().allMatch(k -> {
                    Object val = m.get(k); return val == null || Integer.valueOf(0).equals(val) || Double.valueOf(0).equals(val);
                });
                if (allFree) return "Gratuit";
                return m.get("adult") == null ? "" : "👤 " + m.get("adult") + " · 🧒 " + m.get("child") + " · 🌍 " + m.get("foreigner") + " MAD";
            }
            return m.entrySet().stream().filter(e -> e.getValue() != null).map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(" · "));
        }
        return String.valueOf(v);
    }

    // ---------- itinéraire ----------
    public Map<String, Object> plan(String city, int days, int level, String who) {
        Query q = Query.query(new Criteria().andOperator(Criteria.where("city").is(city), budget(level)));
        List<Document> pool = new ArrayList<>(t.find(q, Document.class, "attractions"));
        pool.removeIf(d -> d.get("location") == null);
        if (who != null && !who.isBlank()) pool.removeIf(d -> d.get("suitable_for") instanceof List<?> s && !s.contains(who));
        pool.forEach(d -> d.put("col", "attractions"));
        List<Document> meals = t.find(q, Document.class, "restaurants");
        List<Document> hotels = t.find(q, Document.class, "hotels");
        Document hotel = hotels.stream().max(Comparator.comparingInt(d -> d.get("stars") instanceof Number n ? n.intValue() : 0)).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        Document cur = null;
        for (int i = 1; i <= days; i++) {
            List<Document> stops = new ArrayList<>();
            int left = 360;
            while (!pool.isEmpty() && left > 0) {
                Document n = nearest(pool, cur);
                int dur = n.get("visit_duration_min") instanceof Number x ? x.intValue() : 60;
                if (dur > left && !stops.isEmpty()) break;
                pool.remove(n); stops.add(n); left -= dur; cur = n;
            }
            Document meal = meals.isEmpty() ? null : meals.get((i - 1) % meals.size());
            if (meal != null) meal.put("col", "restaurants");
            Document mealDoc = meal == null ? new Document() : meal;
            out.add(Map.of("n", i, "stops", stops, "meal", mealDoc));
        }
        if (hotel != null) hotel.put("col", "hotels");
        Document hotelDoc = hotel == null ? new Document() : hotel;
        return Map.of("days", out, "hotel", hotelDoc);
    }

    private Document nearest(List<Document> pool, Document from) {
        if (from == null) return pool.get(0);
        List<Number> a = xy(from);
        return pool.stream().min(Comparator.comparingDouble(d -> {
            List<Number> b = xy(d);
            return Math.pow(a.get(0).doubleValue() - b.get(0).doubleValue(), 2) + Math.pow(a.get(1).doubleValue() - b.get(1).doubleValue(), 2);
        })).get();
    }
}
