package com.bjarne.genericgraphql.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class JdbcTracingTest {

    @Test
    void labelNamesVerbTableAndInList() {
        assertThat(JdbcTracing.label("select p1_0.object_id,p1_0.city from gql_profile p1_0 where p1_0.object_ref_id in (?,?,?)"))
                .isEqualTo("SELECT gql_profile IN (3)");
        assertThat(JdbcTracing.label("select count(p1_0.object_id) from gql_profile p1_0")).isEqualTo("SELECT count(*) gql_profile");
        assertThat(JdbcTracing.label("insert into gql_profile (a,b) values (?,?)")).isEqualTo("INSERT gql_profile");
        assertThat(JdbcTracing.label("delete from gql_profile where object_id=?")).isEqualTo("DELETE gql_profile");
        assertThat(JdbcTracing.label("update gql_profile set city=? where object_id=?")).isEqualTo("UPDATE gql_profile");
    }

    @Test
    void inlineSetsParametersOutsideOfLiterals() {
        Map<Integer, Object> parameters = new TreeMap<>();
        parameters.put(1, "PROF'1");
        parameters.put(2, 42);
        parameters.put(3, null);
        assertThat(JdbcTracing.inline("select '?' from t where a=? and b=? and c=?", parameters))
                .isEqualTo("select '?' from t where a='PROF''1' and b=42 and c=null");
    }
}
