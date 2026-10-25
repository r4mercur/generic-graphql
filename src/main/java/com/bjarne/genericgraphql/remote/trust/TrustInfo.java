package com.bjarne.genericgraphql.remote.trust;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Antwort des Trust-Service zu einem Profil - so, wie sie ueber die Leitung kommt. */
public record TrustInfo(TrustLevel level,
                        BigDecimal score,
                        Integer openReports,
                        LocalDate lastReviewedOn,
                        String note) {
}
