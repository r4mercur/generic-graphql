package com.bjarne.genericgraphql.config;

import com.bjarne.genericgraphql.domain.AppUser;
import com.bjarne.genericgraphql.domain.EventType;
import com.bjarne.genericgraphql.domain.Gender;
import com.bjarne.genericgraphql.domain.Interest;
import com.bjarne.genericgraphql.domain.InterestCategory;
import com.bjarne.genericgraphql.domain.Match;
import com.bjarne.genericgraphql.domain.Message;
import com.bjarne.genericgraphql.domain.Photo;
import com.bjarne.genericgraphql.domain.Profile;
import com.bjarne.genericgraphql.domain.ProfileInterest;
import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.mutation.EventMutationService;
import graphql.GraphQLContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final EntityManager entityManager;
    private final MetaProvider metaProvider;
    private final EventMutationService mutations;

    public DataSeeder(EntityManager entityManager, MetaProvider metaProvider, EventMutationService mutations) {
        this.entityManager = entityManager;
        this.metaProvider = metaProvider;
        this.mutations = mutations;
    }

    private FilterEngine.FilterContext context() {
        return new FilterEngine.FilterContext(LocalDate.now(), null, GraphQLContext.newContext().build());
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser mara = user("mara", "Mara", "Lindqvist", "mara@example.test");
        AppUser jonas = user("jonas", "Jonas", "Weber", "jonas@example.test");
        AppUser sam = user("sam", "Sam", "Okafor", "sam@example.test");
        entityManager.flush();

        // --- Interessen -------------------------------------------------------
        String klettern = create(Interest.class, Map.of(
                "name", "Klettern",
                "category", InterestCategory.OUTDOOR,
                "description", "Boulderhalle, Fels, alles was hoch geht"));
        String reisen = create(Interest.class, Map.of(
                "name", "Reisen",
                "category", InterestCategory.TRAVEL,
                "description", "Am liebsten mit Zug und ohne Plan"));
        String jazz = create(Interest.class, Map.of(
                "name", "Jazz",
                "category", InterestCategory.MUSIC,
                "description", "Von Mingus bis Modern"));

        // --- Profile ----------------------------------------------------------
        String maraProfile = create(Profile.class, map(
                "owner", String.valueOf(mara.getId()),
                "displayName", "Mara",
                "bio", "Klettert am Wochenende, kocht unter der Woche.",
                "city", "Hamburg",
                "country", "DE",
                "birthDate", LocalDate.of(1994, 4, 17),
                "heightCm", 171,
                "lookingFor", Gender.ANY,
                "lastActiveAt", OffsetDateTime.now().minusHours(2),
                "rankingScore", new BigDecimal("0.8140")));

        String jonasProfile = create(Profile.class, map(
                "owner", String.valueOf(jonas.getId()),
                "displayName", "Jonas",
                "bio", "Jazzplatten, lange Spaziergaenge, schlechte Wortwitze.",
                "city", "Hamburg",
                "country", "DE",
                "birthDate", LocalDate.of(1991, 11, 3),
                "heightCm", 184,
                "lookingFor", Gender.FEMALE,
                "lastActiveAt", OffsetDateTime.now().minusMinutes(20),
                "rankingScore", new BigDecimal("0.7725")));

        String samProfile = create(Profile.class, map(
                "owner", String.valueOf(sam.getId()),
                "displayName", "Sam",
                "bio", "Reist viel, klettert manchmal, fotografiert immer.",
                "city", "Berlin",
                "country", "DE",
                "birthDate", LocalDate.of(1996, 2, 28),
                "heightCm", 178,
                "lookingFor", Gender.ANY,
                "lastActiveAt", OffsetDateTime.now().minusDays(3),
                "rankingScore", new BigDecimal("0.6410")));

        // --- Historie: Mara zieht um und wird spaeter verifiziert --------------
        update(Profile.class, maraProfile, LocalDate.now().minusMonths(6), map(
                "city", "Leipzig",
                "bio", "Neu in Leipzig. Suche Kletterpartner:innen."));
        update(Profile.class, maraProfile, LocalDate.now().minusMonths(2), map(
                "verifiedAt", OffsetDateTime.now().minusMonths(2),
                "bio", "In Leipzig angekommen. Boulderhalle gefunden."));

        // --- Fotos ------------------------------------------------------------
        create(Photo.class, map("profile", maraProfile, "url", "https://cdn.example.test/mara-1.jpg",
                "caption", "Am Fels in Arco", "sortOrder", 1, "primaryPhoto", true));
        create(Photo.class, map("profile", maraProfile, "url", "https://cdn.example.test/mara-2.jpg",
                "caption", "Kuechenexperiment", "sortOrder", 2, "primaryPhoto", false));
        create(Photo.class, map("profile", jonasProfile, "url", "https://cdn.example.test/jonas-1.jpg",
                "caption", "Plattenladen", "sortOrder", 1, "primaryPhoto", true));
        create(Photo.class, map("profile", samProfile, "url", "https://cdn.example.test/sam-1.jpg",
                "caption", "Lissabon", "sortOrder", 1, "primaryPhoto", true));

        // --- Interessenzuordnung ----------------------------------------------
        create(ProfileInterest.class, map("profile", maraProfile, "interest", klettern, "intensity", 5));
        create(ProfileInterest.class, map("profile", maraProfile, "interest", reisen, "intensity", 3));
        create(ProfileInterest.class, map("profile", jonasProfile, "interest", jazz, "intensity", 5));
        create(ProfileInterest.class, map("profile", jonasProfile, "interest", reisen, "intensity", 4));
        create(ProfileInterest.class, map("profile", samProfile, "interest", klettern, "intensity", 2));
        create(ProfileInterest.class, map("profile", samProfile, "interest", reisen, "intensity", 5));

        // --- Matches und Nachrichten -------------------------------------------
        String matchMaraJonas = create(Match.class, map(
                "profileA", maraProfile,
                "profileB", jonasProfile,
                "matchedAt", OffsetDateTime.now().minusDays(9),
                "score", new BigDecimal("0.8800")));

        String matchMaraSam = create(Match.class, map(
                "profileA", maraProfile,
                "profileB", samProfile,
                "matchedAt", OffsetDateTime.now().minusDays(2),
                "score", new BigDecimal("0.7100")));

        create(Message.class, map("matchRef", matchMaraJonas, "sender", jonasProfile,
                "body", "Moin! Welche Boulderhalle ist denn deine?",
                "sentAt", OffsetDateTime.now().minusDays(9).plusHours(1)));
        create(Message.class, map("matchRef", matchMaraJonas, "sender", maraProfile,
                "body", "Meistens die am Hafen. Du kletterst auch?",
                "sentAt", OffsetDateTime.now().minusDays(8)));
        create(Message.class, map("matchRef", matchMaraSam, "sender", samProfile,
                "body", "Deine Arco-Bilder sind grossartig.",
                "sentAt", OffsetDateTime.now().minusDays(1)));

        log.info("Demodaten angelegt. GraphQL: POST http://localhost:8080/graphql - IDE: http://localhost:8080/graphiql.html");
    }

    // -------------------------------------------------------------------------

    private AppUser user(String username, String firstName, String lastName, String email) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        entityManager.persist(user);
        return user;
    }

    private String create(Class<?> entityClass, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("event", EventType.CREATE);
        EventMutationService.Result result =
                mutations.handle(metaProvider.meta(entityClass), payload, context());
        if (!"ok".equals(result.result())) {
            throw new IllegalStateException("Seeding fehlgeschlagen: " + result.message());
        }
        return result.objectBezugsId();
    }

    private void update(Class<?> entityClass, String objectBezugsId, LocalDate validFrom, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("event", EventType.UPDATE);
        payload.put("objectBezugsId", objectBezugsId);
        payload.put("gueltigVon", validFrom);
        EventMutationService.Result result =
                mutations.handle(metaProvider.meta(entityClass), payload, context());
        if (!"ok".equals(result.result())) {
            throw new IllegalStateException("Seeding fehlgeschlagen: " + result.message());
        }
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }
}
