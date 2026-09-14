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
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    // --- Seed-Daten ----------------------------------------------------------
    private record InterestSeed(String name, InterestCategory category, String description) {}

    private record PhotoSeed(String url, String caption, int sortOrder, boolean primaryPhoto) {}

    private record InterestLink(String interest, int intensity) {}

    private record ProfileSeed(String username, String firstName, String lastName, String email,
                               String bio, String city, LocalDate birthDate, int heightCm, Gender lookingFor,
                               Duration lastActiveAgo, String rankingScore,
                               List<PhotoSeed> photos, List<InterestLink> interests) {}

    private record MessageSeed(String sender, String body, Duration sentAgo) {}

    private record MatchSeed(String profileA, String profileB, Duration matchedAgo, String score,
                             List<MessageSeed> messages) {}

    private static final List<InterestSeed> INTERESTS = List.of(
            new InterestSeed("Klettern", InterestCategory.OUTDOOR, "Boulderhalle, Fels, alles was hoch geht"),
            new InterestSeed("Reisen", InterestCategory.TRAVEL, "Am liebsten mit Zug und ohne Plan"),
            new InterestSeed("Jazz", InterestCategory.MUSIC, "Von Mingus bis Modern"));

    private static final List<ProfileSeed> PROFILES = List.of(
            new ProfileSeed("mara", "Mara", "Lindqvist", "mara@example.test",
                    "Klettert am Wochenende, kocht unter der Woche.", "Hamburg",
                    LocalDate.of(1994, 4, 17), 171, Gender.ANY, Duration.ofHours(2), "0.8140",
                    List.of(new PhotoSeed("https://cdn.example.test/mara-1.jpg", "Am Fels in Arco", 1, true),
                            new PhotoSeed("https://cdn.example.test/mara-2.jpg", "Kuechenexperiment", 2, false)),
                    List.of(new InterestLink("Klettern", 5), new InterestLink("Reisen", 3))),
            new ProfileSeed("jonas", "Jonas", "Weber", "jonas@example.test",
                    "Jazzplatten, lange Spaziergaenge, schlechte Wortwitze.", "Hamburg",
                    LocalDate.of(1991, 11, 3), 184, Gender.FEMALE, Duration.ofMinutes(20), "0.7725",
                    List.of(new PhotoSeed("https://cdn.example.test/jonas-1.jpg", "Plattenladen", 1, true)),
                    List.of(new InterestLink("Jazz", 5), new InterestLink("Reisen", 4))),
            new ProfileSeed("sam", "Sam", "Okafor", "sam@example.test",
                    "Reist viel, klettert manchmal, fotografiert immer.", "Berlin",
                    LocalDate.of(1996, 2, 28), 178, Gender.ANY, Duration.ofDays(3), "0.6410",
                    List.of(new PhotoSeed("https://cdn.example.test/sam-1.jpg", "Lissabon", 1, true)),
                    List.of(new InterestLink("Klettern", 2), new InterestLink("Reisen", 5))));

    private static final List<MatchSeed> MATCHES = List.of(
            new MatchSeed("mara", "jonas", Duration.ofDays(9), "0.8800", List.of(
                    new MessageSeed("jonas", "Moin! Welche Boulderhalle ist denn deine?",
                            Duration.ofDays(9).minusHours(1)),
                    new MessageSeed("mara", "Meistens die am Hafen. Du kletterst auch?", Duration.ofDays(8)))),
            new MatchSeed("mara", "sam", Duration.ofDays(2), "0.7100", List.of(
                    new MessageSeed("sam", "Deine Arco-Bilder sind grossartig.", Duration.ofDays(1)))));

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
        OffsetDateTime now = OffsetDateTime.now();

        // --- User -------------------------------------------------------------
        Map<String, AppUser> users = new HashMap<>();
        for (ProfileSeed seed : PROFILES) {
            users.put(seed.username(), user(seed.username(), seed.firstName(), seed.lastName(), seed.email()));
        }
        entityManager.flush();

        // --- Interessen -------------------------------------------------------
        Map<String, String> interestIds = new HashMap<>();
        for (InterestSeed seed : INTERESTS) {
            interestIds.put(seed.name(), create(Interest.class, map(
                    "name", seed.name(),
                    "category", seed.category(),
                    "description", seed.description())));
        }

        // --- Profile mit Fotos und Interessen ---------------------------------
        Map<String, String> profileIds = new HashMap<>();
        for (ProfileSeed seed : PROFILES) {
            String profileId = create(Profile.class, map(
                    "owner", String.valueOf(users.get(seed.username()).getId()),
                    "displayName", seed.firstName(),
                    "bio", seed.bio(),
                    "city", seed.city(),
                    "country", "DE",
                    "birthDate", seed.birthDate(),
                    "heightCm", seed.heightCm(),
                    "lookingFor", seed.lookingFor(),
                    "lastActiveAt", now.minus(seed.lastActiveAgo()),
                    "rankingScore", new BigDecimal(seed.rankingScore())));
            profileIds.put(seed.username(), profileId);

            for (PhotoSeed photo : seed.photos()) {
                create(Photo.class, map(
                        "profile", profileId,
                        "url", photo.url(),
                        "caption", photo.caption(),
                        "sortOrder", photo.sortOrder(),
                        "primaryPhoto", photo.primaryPhoto()));
            }

            for (InterestLink link : seed.interests()) {
                create(ProfileInterest.class, map(
                        "profile", profileId,
                        "interest", interestIds.get(link.interest()),
                        "intensity", link.intensity()));
            }
        }

        String maraProfile = profileIds.get("mara");
        update(Profile.class, maraProfile, LocalDate.now().minusMonths(6), map(
                "city", "Leipzig",
                "bio", "Neu in Leipzig. Suche Kletterpartner:innen."));
        update(Profile.class, maraProfile, LocalDate.now().minusMonths(2), map(
                "verifiedAt", now.minusMonths(2),
                "bio", "In Leipzig angekommen. Boulderhalle gefunden."));

        // --- Matches und Nachrichten -------------------------------------------
        for (MatchSeed seed : MATCHES) {
            String matchId = create(Match.class, map(
                    "profileA", profileIds.get(seed.profileA()),
                    "profileB", profileIds.get(seed.profileB()),
                    "matchedAt", now.minus(seed.matchedAgo()),
                    "score", new BigDecimal(seed.score())));

            for (MessageSeed message : seed.messages()) {
                create(Message.class, map(
                        "matchRef", matchId,
                        "sender", profileIds.get(message.sender()),
                        "body", message.body(),
                        "sentAt", now.minus(message.sentAgo())));
            }
        }

        log.info("Demodaten angelegt. GraphQL: POST http://localhost:8080/graphql - IDE: http://localhost:8080/graphiql.html");
    }

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
        return handle(entityClass, payload).objectBezugsId();
    }

    private void update(Class<?> entityClass, String objectBezugsId, LocalDate validFrom, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("event", EventType.UPDATE);
        payload.put("objectBezugsId", objectBezugsId);
        payload.put("gueltigVon", validFrom);
        handle(entityClass, payload);
    }

    private EventMutationService.Result handle(Class<?> entityClass, Map<String, Object> payload) {
        EventMutationService.Result result =
                mutations.handle(metaProvider.meta(entityClass), payload, context());
        if (!"ok".equals(result.result())) {
            throw new IllegalStateException("Seeding fehlgeschlagen: " + result.message());
        }
        return result;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }
}
