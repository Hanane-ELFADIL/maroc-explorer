package ma.tourisme;

import java.security.Principal;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class Web {
    static final List<String> ALL = List.of("attractions", "hotels", "restaurants", "activities", "events", "transport");
    private final MongoTemplate t; private final Places places; private final PasswordEncoder enc; private final int pageSize;

    public Web(MongoTemplate t, Places places, PasswordEncoder enc, @Value("${app.page-size:12}") int pageSize) {
        this.t = t; this.places = places; this.enc = enc; this.pageSize = pageSize;
    }

    // ----- attributs communs à toutes les pages -----
    private List<Document> cities() {
        Map<String, Document> byName = new LinkedHashMap<>();            // une seule fiche par ville, même si la base contient des doublons
        for (Document c : t.findAll(Document.class, "cities")) byName.putIfAbsent(String.valueOf(c.getString("name")).trim().toLowerCase(), c);
        List<Document> l = new ArrayList<>(byName.values());
        l.forEach(c -> c.put("col", "cities"));
        l.sort(Comparator.comparing(c -> c.getString("name")));
        return l;
    }

    @ModelAttribute("cityNames") List<String> cityNames() { return cities().stream().map(c -> c.getString("name")).toList(); }

    @ModelAttribute("regions")
    Map<String, List<String>> regions() {
        Map<String, List<String>> m = new TreeMap<>();
        for (Document c : cities()) m.computeIfAbsent(String.valueOf(c.getString("region")), k -> new ArrayList<>()).add(c.getString("name"));
        return m;
    }

    @ModelAttribute("cols") List<String> cols() { return Places.COLS; }
    @ModelAttribute("levels") List<Integer> levels() { return List.of(1, 2, 3, 4); }
    @ModelAttribute("whos") List<String> whos() { return List.of("couple", "famille", "solo", "groupe"); }
    @ModelAttribute("sorts") List<String> sorts() { return List.of("name", "price_asc", "price_desc", "city"); }

    @ModelAttribute("favs")
    Set<String> favs(Principal p) {
        if (p == null) return Set.of();
        Document u = t.findOne(Query.query(Criteria.where("username").is(p.getName())), Document.class, "users");
        return u == null ? Set.of() : new HashSet<>(u.getList("favorites", String.class, new ArrayList<>()));
    }

    // ----- pages -----
    @GetMapping("/")
    String home(Model m) {
        List<Document> cities = cities();
        long places = 0;
        for (Document c : cities) {
            long n = 0;
            for (String k : Places.COLS) n += t.count(Query.query(Criteria.where("city").is(c.getString("name"))), k);
            c.put("total", n); places += n;
        }
        List<Document> feat = new ArrayList<>(this.places.search(null, "attractions", null, null, null, "name"));
        Collections.shuffle(feat);
        Map<String, Long> stats = new LinkedHashMap<>();
        for (String c : Places.COLS) stats.put(c, t.getCollection(c).countDocuments());
        m.addAttribute("cities", cities); m.addAttribute("featured", feat.subList(0, Math.min(6, feat.size())));
        m.addAttribute("stats", stats); m.addAttribute("totalPlaces", places);
        return "home";
    }

    @GetMapping("/city/{name}")
    String city(@PathVariable String name, Model m) {
        Document c = t.findOne(Query.query(Criteria.where("name").is(name)), Document.class, "cities");
        if (c == null) return "redirect:/";
        c.put("col", "cities");
        Map<String, List<Document>> lists = new LinkedHashMap<>();
        List<String> sections = new ArrayList<>();
        List<Document> all = new ArrayList<>();
        for (String k : ALL) {
            List<Document> l = places.byCol(name, k);
            lists.put(k, l); all.addAll(l);
            if (!l.isEmpty()) sections.add(k);
        }
        List<String> known = cityNames();
        List<String> nearby = c.get("nearby_cities") instanceof List<?> l ? l.stream().map(String::valueOf).filter(known::contains).toList() : List.of();
        m.addAttribute("c", c); m.addAttribute("facts", places.facts(c, true)); m.addAttribute("lists", lists);
        m.addAttribute("sections", sections); m.addAttribute("nearbyCities", nearby); m.addAttribute("markers", places.markers(all));
        return "city";
    }

    @GetMapping("/place/{col}/{slug}")
    String place(@PathVariable String col, @PathVariable String slug, Model m) {
        if (!ALL.contains(col)) return "redirect:/";
        Document d = t.findOne(Query.query(Criteria.where("slug").is(slug)), Document.class, col);
        if (d == null) return "redirect:/";
        d.put("col", col);
        m.addAttribute("p", d); m.addAttribute("facts", places.facts(d, false)); m.addAttribute("markers", places.markers(List.of(d)));
        m.addAttribute("similar", places.byCol(d.getString("city"), col).stream().filter(x -> !slug.equals(x.getString("slug"))).limit(3).toList());
        return "place";
    }

    @GetMapping("/explore")
    String explore(@RequestParam(required = false) String city, @RequestParam(required = false) String col, @RequestParam(required = false) String q,
                   @RequestParam(required = false) String cat, @RequestParam(required = false) Integer level,
                   @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "1") int page, Model m) {
        List<Document> all = places.search(city, col, q, level, cat, sort);
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int p = Math.min(Math.max(page, 1), pages);
        Map<String, Object> f = new HashMap<>();
        f.put("city", city == null ? "" : city); f.put("col", col == null ? "" : col); f.put("q", q == null ? "" : q);
        f.put("cat", cat == null ? "" : cat); f.put("level", level); f.put("sort", sort);
        m.addAttribute("list", all.subList((p - 1) * pageSize, Math.min(all.size(), p * pageSize)));
        m.addAttribute("total", all.size()); m.addAttribute("page", p); m.addAttribute("pages", pages);
        m.addAttribute("markers", places.markers(all)); m.addAttribute("categories", places.categories()); m.addAttribute("f", f);
        return "explore";
    }

    @GetMapping("/itinerary")
    String itinerary(@RequestParam(required = false) String city, @RequestParam(defaultValue = "2") int days, @RequestParam(defaultValue = "2") int level,
                     @RequestParam(defaultValue = "") String who, Model m) {
        if (city != null && !city.isBlank()) m.addAttribute("plan", places.plan(city, Math.max(1, Math.min(days, 7)), level, who));
        m.addAttribute("sel", Map.of("city", city == null ? "" : city, "days", days, "level", level, "who", who));
        return "itinerary";
    }

    @GetMapping("/about") String about() { return "about"; }

    // ----- API JSON (réutilisable par une application mobile ou un autre front) -----
    @GetMapping("/api/nearby") @ResponseBody
    List<Map<String, Object>> nearby(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "3000") int m) {
        return places.markers(places.nearby(lon, lat, m));
    }

    @GetMapping("/api/places") @ResponseBody
    List<Map<String, Object>> apiPlaces(@RequestParam(required = false) String city, @RequestParam(required = false) String col, @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer level, @RequestParam(required = false) String cat) {
        return places.search(city, col, q, level, cat, "name").stream().map(d -> {
            Map<String, Object> o = new LinkedHashMap<>(d); o.remove("_id"); o.put("image", places.img(d)); return o;
        }).toList();
    }

    @GetMapping("/api/cities") @ResponseBody
    List<Map<String, Object>> apiCities() {
        return cities().stream().map(d -> { Map<String, Object> o = new LinkedHashMap<>(d); o.remove("_id"); o.put("image", places.img(d)); return o; }).toList();
    }

    // ----- favoris et comptes -----
    @PostMapping("/fav")
    String fav(@RequestParam String key, Principal p, @RequestHeader(value = "Referer", required = false) String ref) {
        Query q = Query.query(Criteria.where("username").is(p.getName()));
        List<String> f = t.findOne(q, Document.class, "users").getList("favorites", String.class, new ArrayList<>());
        if (!f.remove(key)) f.add(key);
        t.updateFirst(q, Update.update("favorites", f), "users");
        return "redirect:" + (ref == null ? "/favorites" : ref);
    }

    @GetMapping("/favorites")
    String favorites(Principal p, Model m) {
        List<Document> out = new ArrayList<>();
        for (String k : favs(p)) {
            String[] s = k.split(":", 2);
            Document d = t.findOne(Query.query(Criteria.where("slug").is(s[1])), Document.class, s[0]);
            if (d != null) { d.put("col", s[0]); out.add(d); }
        }
        m.addAttribute("list", out);
        return "favorites";
    }

    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/signup") String signup() { return "signup"; }

    @PostMapping("/signup")
    String register(@RequestParam String username, @RequestParam String password) {
        if (username.isBlank() || password.length() < 4 || t.exists(Query.query(Criteria.where("username").is(username)), "users")) return "redirect:/signup?error";
        t.insert(new Document("username", username).append("password", enc.encode(password)).append("favorites", new ArrayList<>()), "users");
        return "redirect:/login?registered";
    }
}
