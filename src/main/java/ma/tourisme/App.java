package ma.tourisme;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeospatialIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootApplication
public class App {
    public static void main(String[] a) { SpringApplication.run(App.class, a); }

    /** Import des JSON de data/ dans MongoDB. Mode "missing" : n'ajoute que les éléments dont le slug n'existe pas encore. */
    @Bean
    CommandLineRunner seed(MongoTemplate t, ObjectMapper om, @Value("${app.seed:missing}") String mode) {
        return a -> {
            if (mode.equals("off")) return;
            for (String c : List.of("cities", "attractions", "hotels", "restaurants", "activities", "events", "transport")) {
                if (mode.equals("reset")) t.dropCollection(c);
                List<Map<String, Object>> l = om.readValue(new ClassPathResource("data/" + c + ".json").getInputStream(), new TypeReference<>() {});
                int added = 0;
                for (Map<String, Object> m : l)
                    if (!known(t, c, m)) { t.insert(new Document(m), c); added++; }
                dedupe(t, c);
                try {
                    t.indexOps(c).ensureIndex(new Index().on("slug", Sort.Direction.ASC).unique());
                    t.indexOps(c).ensureIndex(new GeospatialIndex("location").typed(GeoSpatialIndexType.GEO_2DSPHERE));
                } catch (Exception e) { System.out.println("[seed] index " + c + " : " + e.getMessage()); }
                if (added > 0) System.out.println("[seed] " + c + " : +" + added);
            }
        };
    }

    /** Un élément existe déjà s'il a le même slug OU le même nom (dans la même ville). */
    static boolean known(MongoTemplate t, String col, Map<String, Object> m) {
        Criteria byName = col.equals("cities") ? Criteria.where("name").is(m.get("name"))
                : new Criteria().andOperator(Criteria.where("city").is(m.get("city")), Criteria.where("name").is(m.get("name")));
        return t.exists(Query.query(new Criteria().orOperator(Criteria.where("slug").is(m.get("slug")), byName)), col);
    }

    static String norm(Object s) {
        return Normalizer.normalize(String.valueOf(s), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    /** Supprime les doublons déjà présents en base (ex. import manuel avec mongoimport + import automatique). */
    static void dedupe(MongoTemplate t, String col) {
        Map<String, List<Document>> groups = new LinkedHashMap<>();
        for (Document d : t.findAll(Document.class, col))
            groups.computeIfAbsent(col.equals("cities") ? norm(d.get("name")) : norm(d.get("city")) + "|" + norm(d.get("name")), k -> new ArrayList<>()).add(d);
        int removed = 0;
        for (List<Document> g : groups.values()) {
            if (g.size() < 2) continue;
            // on garde la fiche la mieux remplie (avec slug en priorité)
            Document keep = g.stream().max(Comparator.comparingInt((Document d) -> (d.get("slug") != null ? 1000 : 0) + (int) d.values().stream().filter(Objects::nonNull).count())).get();
            for (Document d : g) if (d != keep) { t.remove(Query.query(Criteria.where("_id").is(d.get("_id"))), col); removed++; }
        }
        if (removed > 0) System.out.println("[seed] " + col + " : " + removed + " doublon(s) supprimé(s)");
    }
}
