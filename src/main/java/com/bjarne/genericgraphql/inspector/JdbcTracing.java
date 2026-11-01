package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.engine.probe.EngineProbe;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misst SQL auf JDBC-Ebene: Text, Dauer und Zeilen. Die Connection wird nur dann um einen
 * Proxy erweitert, wenn auf dem Thread gerade ein Request mitgeschnitten wird - sonst bekommt
 * Hibernate die Statements des Pools unveraendert.
 */
final class JdbcTracing {

    private static final Pattern FROM = Pattern.compile("\\bfrom\\s+([A-Za-z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTO = Pattern.compile("\\binto\\s+([A-Za-z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile("^\\s*update\\s+([A-Za-z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_LIST = Pattern.compile("\\bin\\s*\\(([^()]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SQL = 4000;

    private JdbcTracing() {
    }

    static Connection wrap(Connection target) {
        if (target == null || Proxy.isProxyClass(target.getClass()) && Proxy.getInvocationHandler(target) instanceof ConnectionHandler) {
            return target;
        }
        return (Connection) Proxy.newProxyInstance(JdbcTracing.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionHandler(target));
    }

    /** Kurzform fuer die Timeline, z. B. "SELECT gql_profile IN (3)". */
    static String label(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL";
        }
        String text = sql.strip();
        int space = text.indexOf(' ');
        String verb = (space > 0 ? text.substring(0, space) : text).toUpperCase(Locale.ROOT);
        String table = null;
        Matcher matcher = switch (verb) {
            case "INSERT" -> INTO.matcher(text);
            case "UPDATE" -> UPDATE.matcher(text);
            case "SELECT", "DELETE", "WITH" -> FROM.matcher(text);
            default -> null;
        };
        if (matcher != null && matcher.find()) {
            table = matcher.group(1).replace("\"", "");
        }
        StringBuilder label = new StringBuilder(verb);
        if ("SELECT".equals(verb) && text.toLowerCase(Locale.ROOT).startsWith("select count(")) {
            label.append(" count(*)");
        }
        if (table != null) {
            label.append(' ').append(table);
        }
        Matcher in = IN_LIST.matcher(text);
        if (in.find()) {
            String list = in.group(1).trim();
            int items = list.isEmpty() ? 0 : list.split(",").length;
            label.append(" IN (").append(items).append(')');
        }
        return label.toString();
    }

    /** Setzt die gebundenen Parameter in den Text ein - nur fuer die Anzeige. */
    static String inline(String sql, Map<Integer, Object> parameters) {
        if (sql == null) {
            return "";
        }
        if (parameters.isEmpty()) {
            return limit(sql);
        }
        StringBuilder out = new StringBuilder(sql.length() + parameters.size() * 8);
        boolean quoted = false;
        int index = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == '?' && !quoted) {
                index++;
                out.append(parameters.containsKey(index) ? literal(parameters.get(index)) : "?");
            } else {
                out.append(c);
            }
        }
        return limit(out.toString());
    }

    private static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " Bytes>";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String limit(String text) {
        return text.length() <= MAX_SQL ? text : text.substring(0, MAX_SQL) + " \u2026";
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static Object identity(Object proxy, Method method, Object[] args, Object target) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "Traced[" + target + "]";
        };
    }

    private static boolean isIdentity(Method method) {
        String name = method.getName();
        int count = method.getParameterCount();
        return name.equals("equals") && count == 1 || (name.equals("hashCode") || name.equals("toString")) && count == 0;
    }

    // ------------------------------------------------------------------ Connection

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isIdentity(method)) {
                return identity(proxy, method, args, target);
            }
            Object result = call(target, method, args);
            if (result instanceof Statement statement) {
                EngineProbe probe = EngineProbe.current();
                if (probe == EngineProbe.NOOP) {
                    return result;
                }
                String sql = method.getName().startsWith("prepare") && args != null && args.length > 0
                        && args[0] instanceof String text ? text : null;
                Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                        : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(JdbcTracing.class.getClassLoader(), new Class<?>[] {type},
                        new StatementHandler(statement, sql, probe));
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ Statement

    private static final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final String sql;
        private final EngineProbe probe;
        private final Map<Integer, Object> parameters = new TreeMap<>();
        private EngineProbe.Span open;
        private long rows;

        StatementHandler(Statement target, String sql, EngineProbe probe) {
            this.target = target;
            this.sql = sql;
            this.probe = probe;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isIdentity(method)) {
                return identity(proxy, method, args, target);
            }
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                parameters.put(index, name.equals("setNull") ? null : args[1]);
            } else if (name.equals("clearParameters")) {
                parameters.clear();
            } else if (name.startsWith("execute")) {
                return execute(proxy, method, args);
            } else if (name.equals("getResultSet")) {
                Object result = call(target, method, args);
                return result instanceof ResultSet resultSet && open != null ? resultSet(resultSet) : result;
            } else if (name.equals("close")) {
                finish();
            }
            return call(target, method, args);
        }

        private Object execute(Object proxy, Method method, Object[] args) throws Throwable {
            finish();
            String text = args != null && args.length > 0 && args[0] instanceof String given ? given : sql;
            EngineProbe.Span span = probe.sql(text);
            span.note(inline(text, parameters));
            Object result;
            try {
                result = call(target, method, args);
            } catch (Throwable ex) {
                span.fail(ex);
                span.close();
                throw ex;
            }
            if (result instanceof ResultSet resultSet) {
                open = span;
                rows = 0;
                return resultSet(resultSet);
            }
            if (result instanceof Boolean hasResultSet && hasResultSet) {
                open = span;
                rows = 0;
                return result;
            }
            long count = switch (result) {
                case Integer n -> n;
                case Long n -> n;
                case int[] counts -> java.util.Arrays.stream(counts).filter(n -> n > 0).asLongStream().sum();
                case long[] counts -> java.util.Arrays.stream(counts).filter(n -> n > 0).sum();
                case null, default -> safeUpdateCount();
            };
            span.result(count);
            span.close();
            return result;
        }

        private long safeUpdateCount() {
            try {
                return target.getUpdateCount();
            } catch (Exception ex) {
                return -1;
            }
        }

        private ResultSet resultSet(ResultSet resultSet) {
            return (ResultSet) Proxy.newProxyInstance(JdbcTracing.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> {
                        if (isIdentity(method)) {
                            return identity(proxy, method, args, resultSet);
                        }
                        Object result = call(resultSet, method, args);
                        if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
                            rows++;
                        } else if (method.getName().equals("close")) {
                            finish();
                        }
                        return result;
                    });
        }

        private void finish() {
            if (open != null) {
                open.result(rows);
                open.close();
                open = null;
            }
        }
    }
}
